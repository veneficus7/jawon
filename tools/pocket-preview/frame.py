"""
The pocket player's face as the phone shows it: straight on, life size, lit by the room in
`tools/models/pocket_light.py` — what `PocketLighting.kt` mirrors.

    python frame.py <out.npy> [width samples lcd.png finish]      (bpy as a module: pip install bpy)

Writes linear RGB, top row first, for grade.py. The face is the shipped `pocket_face.glb`, with the
app's finish put on it the way `PocketFinish` puts it on (silver unless [finish] says black or red):
**the roughness and metalness it sets are factors on the wear map**, as glTF's are, so the preview
multiplies them in rather than replacing them. The live screen is an emissive plane carrying
[lcd.png] if one is given; without one the screen is off, which is how to judge the glass.
"""
import json, math, os, sys
import bpy  # noqa: F401 — before bmesh and friends
import numpy as np
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(HERE, "..", "..")
sys.path.insert(0, os.path.join(REPO, "tools", "models"))
import pocket_light  # noqa: E402

F = json.load(open(os.path.join(HERE, "framing.json")))
out = sys.argv[1]
W = int(sys.argv[2]) if len(sys.argv) > 2 else 720
SPP = int(sys.argv[3]) if len(sys.argv) > 3 else 64
LCD = sys.argv[4] if len(sys.argv) > 4 and sys.argv[4] != "-" else None
FINISH = (sys.argv[5] if len(sys.argv) > 5 else "silver").upper()
H = round(W * F["screen_h_px"] / F["screen_w_px"])
scene_h = F["screen_h_px"] / F["ydpi"] * 0.0254
dist = scene_h * F["focal_mm"] / F["sensor_h_mm"]
MODELS = os.path.join(REPO, "app", "src", "main", "assets", "models")

# PocketFinish, as numbers: front colour, front roughness factor, front metallic factor, wheel,
# button, print. Keep in step with PocketFinish.kt.
FINISHES = {
    "SILVER": ((0.83, 0.83, 0.84), 0.56, 0.4, (0.80, 0.80, 0.79), (0.82, 0.82, 0.81), (0.30, 0.30, 0.31)),
    "BLACK": ((0.205, 0.200, 0.196), 0.34, 1.0, (0.072, 0.071, 0.070), (0.062, 0.061, 0.060), (0.62, 0.62, 0.63)),
    "RED": ((0.62, 0.045, 0.05), 0.30, 0.6, (0.80, 0.80, 0.79), (0.42, 0.02, 0.03), (0.36, 0.36, 0.37)),
}
# The picture, in the face's frame: PocketFace.kt's numbers.
LCD_W = 0.05885
LCD_H = LCD_W * 475.0 / 640.0
LCD_Y = 0.04193
LCD_Z = -0.0009


def B(x, y, z):
    """App axes -> Blender's (Y-up glTF imported into Z-up)."""
    return Vector((x, -z, y))


bpy.ops.wm.read_factory_settings(use_empty=True)
sc = bpy.context.scene
bpy.ops.import_scene.gltf(filepath=os.path.join(MODELS, "pocket_face.glb"))

front, rough_f, metal_f, wheel, button, ink = FINISHES[FINISH]
for mat in bpy.data.materials:
    if not mat.use_nodes:
        continue
    nt = mat.node_tree
    bsdf = next((n for n in nt.nodes if n.type == "BSDF_PRINCIPLED"), None)
    if bsdf is None:
        continue
    colour = {"PocketFront": front, "PocketWheel": wheel, "PocketButton": button, "PocketPrint": ink}.get(mat.name)
    if colour:
        bsdf.inputs["Base Color"].default_value = (*colour, 1.0)
    if mat.name == "PocketFront":
        # Factors on the wear map, as glTF's are.
        for name, factor in (("Roughness", rough_f), ("Metallic", metal_f)):
            sock = bsdf.inputs[name]
            if sock.is_linked:
                src = sock.links[0].from_socket
                mul = nt.nodes.new("ShaderNodeMath")
                mul.operation = "MULTIPLY"
                mul.inputs[1].default_value = factor
                nt.links.new(src, mul.inputs[0])
                nt.links.new(mul.outputs[0], sock)
            else:
                sock.default_value *= factor

if LCD:
    bpy.ops.mesh.primitive_plane_add(size=1.0, location=B(0, LCD_Y, LCD_Z))
    p = bpy.context.active_object
    p.rotation_euler = (math.radians(90), 0, 0)
    p.scale = (LCD_W, LCD_H, 1)
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

img = pocket_light.equirect_blender(1024, 512)
bimg = bpy.data.images.new("room", 1024, 512, alpha=False, float_buffer=True)
bimg.pixels.foreach_set(np.concatenate([img, np.ones(img.shape[:2] + (1,))], axis=2).astype(np.float32).ravel())
world = bpy.data.worlds.new("room")
sc.world = world
world.use_nodes = True
bg = world.node_tree.nodes["Background"]
tex = world.node_tree.nodes.new("ShaderNodeTexEnvironment")
tex.image = bimg
world.node_tree.links.new(tex.outputs["Color"], bg.inputs["Color"])
bg.inputs["Strength"].default_value = pocket_light.AMBIENT_INTENSITY * pocket_light.EXPOSURE

# The key, as the app's spot: power is 4*pi*candela in Cycles' terms.
bpy.ops.object.light_add(type="SPOT", location=B(*pocket_light.KEY_POSITION))
key = bpy.context.active_object
key.data.energy = 4 * math.pi * pocket_light.KEY_CANDELA * pocket_light.EXPOSURE
key.data.color = pocket_light.KEY_COLOUR
key.data.spot_size = math.radians(pocket_light.KEY_CONE_DEG)
key.data.spot_blend = 1.0
key.data.shadow_soft_size = 0.03
key.rotation_euler = (B(*pocket_light.KEY_AIM) - B(*pocket_light.KEY_POSITION)).to_track_quat("-Z", "Y").to_euler()

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
print(f"face {W}x{H} at {dist:.4f}m, {FINISH.lower()} -> {out}")
