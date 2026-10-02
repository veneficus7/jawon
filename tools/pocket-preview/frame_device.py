"""
The device as the phone frames it: life size, the room's camera and pose, lit by the carriage.

    python frame_device.py <out.npy> [width samples lcd.png]      (bpy as a module: pip install bpy)

Writes premultiplied linear RGBA, top row first, for grade.py to put over the window. Cycles,
not Filament — but at Filament's exposure (SceneView's sunny-16 camera), with the carriage IBL
from carriage_env.py at `AMBIENT_INTENSITY` and the key spot at its candela, so the device sits
in the frame at about the brightness the phone shows it. The live screen is an emissive plane
carrying [lcd.png] if one is given; the passing lamps are not drawn (they come and go).
"""
import json, math, os, sys
import bpy  # noqa: F401 — before bmesh and friends
import numpy as np
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import carriage_env as env  # noqa: E402

F = json.load(open(os.path.join(HERE, "framing.json")))
out = sys.argv[1]
W = int(sys.argv[2]) if len(sys.argv) > 2 else 720
SPP = int(sys.argv[3]) if len(sys.argv) > 3 else 64
LCD = sys.argv[4] if len(sys.argv) > 4 else None
H = round(W * F["screen_h_px"] / F["screen_w_px"])
EXPOSURE = 1.0 / (1.2 * 32000.0)
scene_h = F["screen_h_px"] / F["ydpi"] * 0.0254
dist = scene_h * F["focal_mm"] / F["sensor_h_mm"]


def B(x, y, z):
    """App axes -> Blender's (Y-up glTF imported into Z-up)."""
    return (x, -z, y)


bpy.ops.wm.read_factory_settings(use_empty=True)
sc = bpy.context.scene
bpy.ops.import_scene.gltf(filepath=os.path.join(HERE, "../../app/src/main/assets/models/pocket.glb"))
dev = bpy.data.objects.new("device", None)
sc.collection.objects.link(dev)
for o in list(sc.objects):
    if o.type == "MESH":
        o.parent = dev
# Rotation(x = pitch, y = yaw): about app X is about Blender X, about app Y is about Blender Z.
dev.rotation_mode = "ZXY"
dev.rotation_euler = (math.radians(F["device_pitch_deg"]), 0.0, math.radians(F["device_yaw_deg"]))

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

img = env.equirect_blender(1024, 512)
bimg = bpy.data.images.new("carriage", 1024, 512, alpha=False, float_buffer=True)
bimg.pixels.foreach_set(np.concatenate([img, np.ones(img.shape[:2] + (1,))], axis=2).astype(np.float32).ravel())
world = bpy.data.worlds.new("carriage")
sc.world = world
world.use_nodes = True
bg = world.node_tree.nodes["Background"]
tex = world.node_tree.nodes.new("ShaderNodeTexEnvironment")
tex.image = bimg
world.node_tree.links.new(tex.outputs["Color"], bg.inputs["Color"])
bg.inputs["Strength"].default_value = F["ambient_intensity"] * EXPOSURE

kp = F["key_position_m"]
bpy.ops.object.light_add(type="SPOT", location=B(*kp))
key = bpy.context.active_object
# A Cycles point/spot of power P has intensity P/4pi, so a candela value at Filament's exposure is:
key.data.energy = 4 * math.pi * F["key_candela"] * EXPOSURE
key.data.color = F["key_colour"]
inner, outer = F["key_cone_rad"]
key.data.spot_size = 2 * outer
key.data.spot_blend = (outer - inner) / outer
key.data.shadow_soft_size = 0.02
key.rotation_euler = (-Vector(B(*kp))).to_track_quat("-Z", "Y").to_euler()

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
sc.cycles.use_denoising = True
sc.render.resolution_x = W
sc.render.resolution_y = H
sc.render.film_transparent = True
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
print(f"device {W}x{H} at {dist:.4f}m -> {out}")
