"""
The pocket room as the phone frames it: the device lying on the desk at life size, from the
room's own camera, lit by the room's own lamp and environment.

    python frame.py <out.npy> [width samples lcd.png]      (bpy as a module: pip install bpy)
    python frame.py --probe [width]

Writes linear RGB, top row first, for grade.py. **The desk is the shipped `pocket_desk.glb`**,
its lighting already baked in and drawn unlit, exactly as the app draws it; the device and the
cable are lit here by `tools/models/pocket_room.py` — the lamp at its candela and the room at
`AMBIENT_INTENSITY`, at SceneView's sunny-16 exposure — which is what `PocketLighting.kt`
mirrors. The live screen is an emissive plane carrying [lcd.png] if one is given.

**`--probe` answers "what does this part of the device reflect?"** — the question every
lighting change here turns on. It renders the device as a perfect mirror inside an environment
whose colour *is* the direction (the device's own frame, app axes: +Z up off the desk), and
prints the direction each part sees.
"""
import json, math, os, sys
import bpy  # noqa: F401 — before bmesh and friends
import numpy as np
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(HERE, "..", "..")
sys.path.insert(0, os.path.join(REPO, "tools", "models"))
import pocket_room  # noqa: E402

F = json.load(open(os.path.join(HERE, "framing.json")))
PROBE = len(sys.argv) > 1 and sys.argv[1] == "--probe"
out = os.path.join(os.getcwd(), "probe.npy") if PROBE else sys.argv[1]
W = int(sys.argv[2]) if len(sys.argv) > 2 else 720
SPP = 16 if PROBE else (int(sys.argv[3]) if len(sys.argv) > 3 else 64)
LCD = None if PROBE else (sys.argv[4] if len(sys.argv) > 4 else None)
H = round(W * F["screen_h_px"] / F["screen_w_px"])
EXPOSURE = 1.0 / (1.2 * 32000.0)
scene_h = F["screen_h_px"] / F["ydpi"] * 0.0254
dist = scene_h * F["focal_mm"] / F["sensor_h_mm"]
MODELS = os.path.join(REPO, "app", "src", "main", "assets", "models")


def B(x, y, z):
    """App axes -> Blender's (Y-up glTF imported into Z-up)."""
    return Vector((x, -z, y))


bpy.ops.wm.read_factory_settings(use_empty=True)
sc = bpy.context.scene
dev = bpy.data.objects.new("device", None)
sc.collection.objects.link(dev)
for glb in ("pocket.glb",) if PROBE else ("pocket.glb", "pocket_desk.glb"):
    before = set(sc.objects)
    bpy.ops.import_scene.gltf(filepath=os.path.join(MODELS, glb))
    for o in set(sc.objects) - before:
        if o.parent is None:
            o.parent = dev
# Rotation(x = pitch, y = yaw), as SceneView applies it: kotlin-math's Quaternion.fromEuler is
# Ry(yaw) . Rx(pitch) — pitch first. About app X is about Blender X and about app Y is about
# Blender Z, so that is Blender's XYZ order. (This was ZXY until 2026-10-02, the turns the other
# way round: a degree and a half off at the device's corners. PocketLightingTest pins the order.)
dev.rotation_mode = "XYZ"
dev.rotation_euler = (math.radians(F["device_pitch_deg"]), 0.0, math.radians(F["device_yaw_deg"]))
bpy.context.view_layer.update()
R = np.array(dev.matrix_world.to_3x3())          # device frame -> world, Blender axes

if PROBE:
    mirror = bpy.data.materials.new("mirror")
    mirror.use_nodes = True
    bsdf = mirror.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Metallic"].default_value = 1.0
    bsdf.inputs["Roughness"].default_value = 0.0
    bsdf.inputs["Base Color"].default_value = (1, 1, 1, 1)
    for o in list(sc.objects):
        if o.type == "MESH":
            o.data.materials.clear()
            o.data.materials.append(mirror)

if LCD:
    w = 0.0443
    h = w * 475 / 640
    bpy.ops.mesh.primitive_plane_add(size=1.0, location=B(0, 0.0255, 0.00525 - 0.0010 + 0.0001))
    p = bpy.context.active_object
    p.rotation_euler = (math.radians(90), 0, 0)
    p.scale = (w, h, 1)
    p.parent = dev
    m = bpy.data.materials.new("lcd")
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    t = nt.nodes.new("ShaderNodeTexImage")
    t.image = bpy.data.images.load(os.path.abspath(LCD))
    e = nt.nodes.new("ShaderNodeEmission")
    o_ = nt.nodes.new("ShaderNodeOutputMaterial")
    nt.links.new(t.outputs["Color"], e.inputs["Color"])
    nt.links.new(e.outputs[0], o_.inputs[0])
    p.data.materials.append(m)

# The room's light, in the device's frame: world directions are taken into it by R^T.
if PROBE:
    u, v = np.meshgrid((np.arange(1024) + 0.5) / 1024, (np.arange(512) + 0.5) / 512)
    phi, th = (0.5 - u) * 2 * np.pi, (v - 0.5) * np.pi
    d = np.stack([np.cos(th) * np.cos(phi), np.cos(th) * np.sin(phi), np.sin(th)], axis=-1) @ R
    img = np.stack([(d[..., 0] + 1) / 2, (d[..., 2] + 1) / 2, (-d[..., 1] + 1) / 2], axis=-1)
else:
    img = pocket_room.equirect_blender(1024, 512, to_device=R.T)
bimg = bpy.data.images.new("room", 1024, 512, alpha=False, float_buffer=True)
bimg.pixels.foreach_set(np.concatenate([img, np.ones(img.shape[:2] + (1,))], axis=2).astype(np.float32).ravel())
world = bpy.data.worlds.new("room")
sc.world = world
world.use_nodes = True
bg = world.node_tree.nodes["Background"]
tex = world.node_tree.nodes.new("ShaderNodeTexEnvironment")
tex.image = bimg
world.node_tree.links.new(tex.outputs["Color"], bg.inputs["Color"])
bg.inputs["Strength"].default_value = 1.0 if PROBE else F["ambient_intensity"] * EXPOSURE

if not PROBE:
    # The lamp, a child of the device as it is in the app. Spot power is 4*pi*candela here.
    bpy.ops.object.light_add(type="SPOT", location=B(*pocket_room.LAMP_POSITION))
    lamp = bpy.context.active_object
    lamp.data.energy = 4 * math.pi * pocket_room.LAMP_CANDELA * EXPOSURE
    lamp.data.color = pocket_room.LAMP_COLOUR
    lamp.data.spot_size = math.radians(pocket_room.LAMP_CONE_DEG)
    lamp.data.spot_blend = pocket_room.LAMP_BLEND
    lamp.data.shadow_soft_size = pocket_room.LAMP_RADIUS
    lamp.rotation_euler = (B(*pocket_room.LAMP_AIM) - B(*pocket_room.LAMP_POSITION)).to_track_quat("-Z", "Y").to_euler()
    lamp.parent = dev
    # The desk's light is in its texture already: it must not be lit again, or shadow anything.
    for o in sc.objects:
        if o.name.startswith("pocket_desk"):
            o.visible_shadow = False

bpy.ops.object.camera_add(location=B(0, 0, dist))
cam = bpy.context.active_object
cam.rotation_euler = (math.radians(90), 0, 0)
cam.data.lens = F["focal_mm"]
cam.data.sensor_fit = "VERTICAL"
cam.data.sensor_height = F["sensor_h_mm"]
cam.data.clip_start = 0.01
sc.camera = cam

sc.render.engine = "CYCLES"
sc.cycles.device = "CPU"
sc.cycles.samples = SPP
sc.cycles.use_denoising = not PROBE
sc.render.resolution_x = W
sc.render.resolution_y = H
sc.render.film_transparent = PROBE
sc.view_settings.view_transform = "Standard"
sc.render.image_settings.file_format = "OPEN_EXR"
sc.render.image_settings.color_depth = "32"
exr = os.path.splitext(os.path.abspath(out))[0] + ".exr"
sc.render.filepath = exr
bpy.ops.render.render(write_still=True)
im = bpy.data.images.load(exr)
px = np.empty(W * H * 4, dtype=np.float32)
im.pixels.foreach_get(px)
np.save(out, px.reshape(H, W, 4)[::-1])
os.remove(exr)
print(f"frame {W}x{H} at {dist:.4f}m -> {out}")

if PROBE:
    d = np.load(out)
    k = W / 720.0
    for name, (x0, y0, x1, y1) in {
        "front, above the screen": (220, 292, 540, 326),
        "front, beside the screen": (606, 430, 640, 620),
        "front, left of the wheel": (90, 760, 160, 900),
        "front, at the foot": (220, 1215, 520, 1262),
        "wheel, left of the ring": (196, 960, 268, 1040),
        "polished left side": (52, 520, 64, 1100),
    }.items():
        reg = d[int(y0 * k):int(y1 * k), int(x0 * k):int(x1 * k)]
        solid = reg[..., 3] > 0.98
        if not solid.any():
            print(f"{name:26s} (not on the device at this framing)")
            continue
        dirs = reg[..., :3][solid] / reg[..., 3:4][solid] * 2 - 1
        dirs /= np.linalg.norm(dirs, axis=1, keepdims=True)
        zen = np.degrees(np.arccos(np.clip(dirs[:, 2], -1, 1)))
        az = np.degrees(np.arctan2(dirs[:, 1], dirs[:, 0]))
        print(f"{name:26s} from straight up {np.percentile(zen, 10):5.1f} .. {np.percentile(zen, 90):5.1f}"
              f"   toward azimuth {np.median(az):7.1f}  (device frame, atan2(y, x))")
