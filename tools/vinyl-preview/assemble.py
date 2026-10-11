"""
The shipped vinyl models, put together as the app puts them together, in Cycles.

    blender --background --factory-startup --python tools/vinyl-preview/assemble.py -- \
        <out dir> tools/emulator/covers [width] [samples] [views]

Views: deck, thumb, crate, corner (comma separated). Unlike concept.py, which draws its own
geometry to choose a direction by, this loads `app/src/main/assets/models/vinyl_*.glb` — so a
mistake in the model script (a part the wrong way up, a label mapped backwards, a stylus that is
not where VinylDimens says) is seen here, before it is a morning on a phone.

The placing below is VinylDimens's arithmetic, in Blender's axes (Z up, Y away).
"""
import glob
import math
import os
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:]
OUT, COVERS = os.path.abspath(argv[0]), os.path.abspath(argv[1])
WIDTH = int(argv[2]) if len(argv) > 2 else 540
SAMPLES = int(argv[3]) if len(argv) > 3 else 64
VIEWS = argv[4].split(",") if len(argv) > 4 else ["deck", "thumb", "crate", "corner"]
HEIGHT = int(round(WIDTH * 3120 / 1440))
MODELS = os.path.abspath(os.path.join("app", "src", "main", "assets", "models"))   # run from the repository root
BACK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "back.png")

# VinylDimens, as the model script has them.
TOP = 0.080
SPINDLE = Vector((-0.050, 0.012))
PLATTER_H, MAT_H, LP_H = 0.020, 0.003, 0.002
RECORD_TOP = TOP + PLATTER_H + MAT_H + LP_H
PIVOT = Vector((0.152, 0.106))
PIVOT_H = TOP + 0.050
ARM_LENGTH = 0.230
STYLUS_ANGLE = math.radians(4.1)
STYLUS = Vector((-ARM_LENGTH * math.sin(STYLUS_ANGLE), -ARM_LENGTH * math.cos(STYLUS_ANGLE)))
SLEEVE = 0.315
TABLE_H = 0.56
CRATE_AT = Vector((0.0, -1.02, -TABLE_H))

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
covers = sorted(glob.glob(os.path.join(COVERS, "*.jpg")))


def load(name, location=(0, 0, 0), rotation=(0, 0, 0)):
    """Imports a model under one empty, which is what gets placed; returns (empty, its meshes)."""
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=os.path.join(MODELS, name))
    new = [o for o in bpy.data.objects if o not in before]
    root = bpy.data.objects.new(name, None)
    scene.collection.objects.link(root)
    for o in new:
        if o.parent is None:
            o.parent = root
    root.location = location
    root.rotation_euler = rotation
    return root, [o for o in new if o.type == "MESH"]


def paint(meshes, material_name, path):
    """Gives the material called [material_name] in [meshes] its picture, on a copy of its own."""
    for o in meshes:
        for slot in o.material_slots:
            if slot.material and slot.material.name.split(".")[0] == material_name:
                m = slot.material.copy()
                slot.material = m
                for node in m.node_tree.nodes:
                    if node.type == "TEX_IMAGE":
                        node.image = bpy.data.images.load(path, check_existing=True)
                        node.extension = "EXTEND"


def stylus_at(radius):
    d = (PIVOT - SPINDLE).length
    a = (d * d + radius * radius - ARM_LENGTH * ARM_LENGTH) / (2 * d)
    h = math.sqrt(max(radius * radius - a * a, 0.0))
    u = (PIVOT - SPINDLE) / d
    return SPINDLE + u * a + Vector((u.y, -u.x)) * h


def arm_yaw(radius):
    to = stylus_at(radius) - PIVOT
    return math.atan2(to.y, to.x) - math.atan2(STYLUS.y, STYLUS.x)


def aim(obj, target):
    obj.rotation_euler = (Vector(target) - obj.location).to_track_quat("-Z", "Y").to_euler()


def area(name, energy, size, loc, target, color):
    light = bpy.data.lights.new(name, "AREA")
    light.energy, light.size, light.color = energy, size, color
    o = bpy.data.objects.new(name, light)
    scene.collection.objects.link(o)
    o.location = loc
    aim(o, target)


playing = covers[13 % len(covers)]
load("vinyl_room.glb")
load("vinyl_deck.glb")
load("vinyl_platter.glb", (SPINDLE.x, SPINDLE.y, TOP), (0, 0, math.radians(20)))
_, record = load("vinyl_record.glb", (SPINDLE.x, SPINDLE.y, TOP + PLATTER_H + MAT_H), (0, 0, math.radians(-35)))
paint(record, "LabelA", playing)
load("vinyl_arm.glb", (PIVOT.x, PIVOT.y, PIVOT_H), (0, 0, arm_yaw(0.108)))

# The sleeve of what is playing, laid out in front of the deck: its back up, its front under it.
_, back = load("vinyl_sleeve.glb", (0.030, -0.560, 0.0070), (math.radians(90), 0, math.radians(178)))
paint(back, "SleeveBack", BACK)
paint(back, "SleeveFront", playing)
_, front = load("vinyl_sleeve.glb", (-0.062, -0.535, 0.0034), (math.radians(-90), 0, math.radians(5)))
paint(front, "SleeveFront", playing)

# The crate, and the records in it: five flipped toward you, the one being read, the rest behind.
load("vinyl_crate.glb", CRATE_AT)
k = 3
y = CRATE_AT.y - 0.420 / 2 + 0.030
for i in range(5):
    _, s = load("vinyl_sleeve.glb", (CRATE_AT.x, y, CRATE_AT.z), (math.radians(24 - i), 0, 0))
    paint(s, "SleeveFront", covers[k % len(covers)])
    y += 0.0105
    k += 1
behind = 24
y = CRATE_AT.y + 0.420 / 2 - 0.020 - behind * 0.0098
for i in range(behind + 1):
    _, s = load("vinyl_sleeve.glb", (CRATE_AT.x, y + i * 0.0098, CRATE_AT.z), (math.radians(-13 - (i % 4) * 0.5), 0, 0))
    paint(s, "SleeveFront", covers[k % len(covers)])
    k += 1

world = bpy.data.worlds.new("world")
world.use_nodes = True
world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.010, 0.011, 0.014, 1)
world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.10
scene.world = world
area("key", 46.0, 0.34, (-0.70, -0.42, 1.05), (0.0, -0.05, 0.05), (1.0, 0.78, 0.52))
area("fill", 2.2, 1.2, (1.3, -0.9, 0.7), (0.0, -0.1, -0.1), (0.55, 0.70, 1.0))
area("wall_pool", 9.0, 0.30, (-0.36, 0.06, 0.50), (-0.22, 0.28, 0.42), (1.0, 0.70, 0.40))

scene.render.engine = "CYCLES"
scene.cycles.samples = SAMPLES
scene.cycles.use_denoising = True
prefs = bpy.context.preferences.addons["cycles"].preferences
for kind in ("OPTIX", "CUDA"):
    try:
        prefs.compute_device_type = kind
        prefs.get_devices()
        if any(d.type == kind for d in prefs.devices):
            for d in prefs.devices:
                d.use = d.type == kind
            scene.cycles.device = "GPU"
            break
    except Exception:
        pass
scene.render.resolution_x, scene.render.resolution_y = WIDTH, HEIGHT
scene.render.image_settings.file_format = "PNG"
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "AgX - Medium High Contrast"
ASPECT = HEIGHT / WIDTH


def camera(name, loc, target, lens):
    cam = bpy.data.cameras.new(name)
    cam.lens, cam.sensor_fit, cam.sensor_height = lens, "VERTICAL", 36.0
    o = bpy.data.objects.new(name, cam)
    scene.collection.objects.link(o)
    o.location = loc
    aim(o, target)
    return o


def above(name, centre, width, plane_z, height=3.0):
    return camera(name, (centre[0], centre[1], height), (centre[0], centre[1], 0.0), 36.0 * (height - plane_z) / (width * ASPECT))


crate_look = Vector((0.0, CRATE_AT.y + 0.035, CRATE_AT.z + 0.185))
SHOTS = {
    "deck": lambda: above("deck", (0.0, -0.215), 0.490, TOP),
    "thumb": lambda: above("thumb", (SPINDLE.x + 0.014, SPINDLE.y - 0.022), 0.212, RECORD_TOP),
    "crate": lambda: camera("crate", tuple(crate_look + Vector((0.0, -0.62, 0.78)).normalized() * 1.36), crate_look, 58),
    "corner": lambda: camera("corner", (0.0, -2.75, 0.95), (0.0, -0.45, -0.20), 62),
}
os.makedirs(OUT, exist_ok=True)
for view in VIEWS:
    scene.camera = SHOTS[view]()
    scene.render.filepath = os.path.join(OUT, "built_" + view + ".png")
    bpy.ops.render.render(write_still=True)
    print("wrote", scene.render.filepath)
