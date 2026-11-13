"""
The shipped cassette models, put together as the app puts them together, in Cycles.

    blender --background --factory-startup --python tools/tape-preview/assemble.py -- \
        <out dir> tools/emulator/covers [width] [samples] [views]

Views: deck, rack, close (comma separated). This loads `app/src/main/assets/models/tape_*.glb`,
so a mistake in the model script — a part the wrong way round, a label mapped backwards, a
cassette not where TapeDimens says the door holds it — is seen here before it is seen on a phone.

The placing is TapeDimens's arithmetic, in Blender's axes (Z up, Y away): app (x, y, z) is
Blender (x, -z, y). The lens is the app's: 50mm on a 24mm-tall frame.
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
VIEWS = argv[4].split(",") if len(argv) > 4 else ["deck", "rack", "close"]
HEIGHT = int(round(WIDTH * 3120 / 1440))
MODELS = os.path.abspath(os.path.join("app", "src", "main", "assets", "models"))

# TapeDimens, as the model script has them.
DECK_TOP = 0.136
SEAT = (0.0, 0.0675, -0.012)                 # app
HUB_Z, HUB_PITCH, HUB_R = 0.0015, 0.0425, 0.0110
CASE_Z = -0.041                              # the box's middle, on the deck's top
HINGE = (0.0015, 0.0335)
RACK_W, RACK_GAP, RACK_DEPTH, RACKS_BOTTOM, WALL = 0.136, 0.030, 0.082, 0.300, 0.320
RACK_BOTTOM, PITCH, SLOTS = 0.012, 0.019, 24

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
covers = sorted(glob.glob(os.path.join(COVERS, "*.jpg")))


def app(x, y, z):
    return (x, -z, y)


def load(name, location=(0, 0, 0), rotation=(0, 0, 0), scale=(1, 1, 1)):
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
    root.scale = scale
    return root, [o for o in new if o.type == "MESH"]


def paint(meshes, material_name, path):
    for o in meshes:
        for slot in o.material_slots:
            if slot.material and slot.material.name.split(".")[0] == material_name:
                m = slot.material.copy()
                slot.material = m
                for node in m.node_tree.nodes:
                    if node.type == "TEX_IMAGE":
                        node.image = bpy.data.images.load(path, check_existing=True)
                        node.extension = "EXTEND"


def pack_radius(seconds, side=1800.0, thickness=16e-6):
    metres = seconds * 0.047625
    return math.sqrt(HUB_R * HUB_R + metres * thickness / math.pi)


load("tape_room.glb")
load("tape_deck.glb")

# The cassette in the shut door, a third of the way through side A.
cx, cy, cz = app(*SEAT)
_, cas = load("tape_cassette.glb", (cx, cy, cz))
if covers:
    paint(cas, "CassetteLabelA", covers[0])
for sx, held in ((-1, 1800.0 - 600.0), (1, 600.0)):
    hx, hy, hz = app(SEAT[0] + sx * HUB_PITCH / 2, SEAT[1] + HUB_Z, SEAT[2])
    load("tape_hub.glb", (hx, hy, hz))
    r = pack_radius(held)
    load("tape_pack.glb", (hx, hy, hz), scale=(r, 1.0, r))

# The box on the deck's top, open, its J-card's inside toward you.
_, tray = load("tape_case_tray.glb", app(0.0, DECK_TOP + 0.0005, CASE_Z))
lx, ly, lz = app(0.0, DECK_TOP + 0.0005 + HINGE[0], CASE_Z - HINGE[1])
_, lid = load("tape_case_lid.glb", (lx, ly, lz), rotation=(math.radians(-100.0), 0, 0))
if covers:
    paint(lid, "JCardInside", covers[min(1, len(covers) - 1)])
    paint(lid, "JCardFront", covers[0])

# Three racks on the wall, tapes in most of their slots.
for k in range(3):
    rx = k * (RACK_W + RACK_GAP)
    load("tape_rack.glb", app(rx, RACKS_BOTTOM, -WALL + RACK_DEPTH))
    for i in range(SLOTS - (6 if k == 0 else 10 + 3 * k)):
        j = SLOTS - 1 - i
        y = RACKS_BOTTOM + RACK_BOTTOM + j * PITCH + 0.0014
        _, case = load("tape_case_closed.glb", app(rx, y, -WALL + RACK_DEPTH - 0.004 - 0.035), rotation=(0, 0, math.radians(180.0)))
        if covers:
            paint(case, "JCardSpine", covers[(i + k * 7) % len(covers)])

# ---- Light ------------------------------------------------------------------------------------------


def aim(obj, target):
    d = Vector(target) - obj.location
    obj.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()


def spot(name, energy, loc, target, color, size=0.05, angle=50.0):
    data = bpy.data.lights.new(name, "SPOT")
    data.energy = energy
    data.color = color
    data.shadow_soft_size = size
    data.spot_size = math.radians(angle)
    data.spot_blend = 0.6
    obj = bpy.data.objects.new(name, data)
    scene.collection.objects.link(obj)
    obj.location = loc
    aim(obj, target)
    return obj


def point(name, energy, loc, color, size=0.004):
    data = bpy.data.lights.new(name, "POINT")
    data.energy = energy
    data.color = color
    data.shadow_soft_size = size
    obj = bpy.data.objects.new(name, data)
    scene.collection.objects.link(obj)
    obj.location = loc


# The lamp: up, to the left and in front — over your left shoulder.
spot("key", 55.0, app(-0.55, 1.10, 0.70), app(0.05, 0.25, -0.10), (1.0, 0.80, 0.58), size=0.12, angle=60.0)
# The rack's own: a picture light's worth, low and in front, so the spines' faces are lit square.
spot("racks", 16.0, app(0.18, 1.05, 0.55), app(0.18, 0.55, -0.24), (1.0, 0.86, 0.68), size=0.10, angle=48.0)
# Inside the deck: the lamp that lights a cassette in its well.
point("well", 0.08, app(0.0, 0.100, 0.0005), (1.0, 0.78, 0.50))

# What the room behind you is to a polished face: a wide, soft, warm brightness — the lit room —
# which the app paints into its environment as TapeLighting's "room".
room = bpy.data.lights.new("room", "AREA")
room.shape, room.size, room.size_y = "RECTANGLE", 2.2, 1.2
room.energy, room.color = 60.0, (1.0, 0.86, 0.70)
room_obj = bpy.data.objects.new("room", room)
scene.collection.objects.link(room_obj)
room_obj.location = app(0.25, 0.75, 2.2)
aim(room_obj, app(0.0, 0.25, 0.0))

world = bpy.data.worlds.new("w")
scene.world = world
world.use_nodes = True
world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.012, 0.012, 0.014, 1)
world.node_tree.nodes["Background"].inputs["Strength"].default_value = 1.0

scene.render.engine = "CYCLES"
scene.cycles.samples = SAMPLES
scene.cycles.use_denoising = True
try:
    prefs = bpy.context.preferences.addons["cycles"].preferences
    prefs.compute_device_type = "OPTIX"
    prefs.get_devices()
    for d in prefs.devices:
        d.use = True
    scene.cycles.device = "GPU"
except Exception:
    pass
scene.render.resolution_x, scene.render.resolution_y = WIDTH, HEIGHT
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "AgX - Medium High Contrast"


def camera(name, loc, target):
    data = bpy.data.cameras.new(name)
    data.lens, data.sensor_fit, data.sensor_height = 50.0, "VERTICAL", 24.0
    data.clip_start = 0.02
    cam = bpy.data.objects.new(name, data)
    scene.collection.objects.link(cam)
    cam.location = loc
    aim(cam, target)
    return cam


shots = {
    # The deck: the frame 150mm wide at its face, from 0.68m.
    "deck": lambda: camera("deck", app(0.0, 0.110, 0.68), app(0.0, 0.110, 0.0)),
    # A rack: 170mm wide at its front.
    "rack": lambda: camera("rack", app(0.0, 0.55, -0.238 + 0.78), app(0.0, 0.55, -0.238)),
    # The cassette up close: pinched in.
    "close": lambda: camera("close", app(0.0, 0.0675, 0.30), app(0.0, 0.0675, 0.0)),
    # The counter and a meter, as close as a pinch goes.
    "counter": lambda: camera("counter", app(-0.012, 0.120, 0.11), app(-0.012, 0.120, 0.0)),
}
os.makedirs(OUT, exist_ok=True)
for view in VIEWS:
    scene.camera = shots[view]()
    scene.render.filepath = os.path.join(OUT, "tape_" + view + ".png")
    bpy.ops.render.render(write_still=True)
    print("JAVON-PREVIEW wrote", scene.render.filepath)
