"""
The vinyl room, as stills to choose between — a first look, not the model.

    python tools/vinyl-preview/make_back.py "tools/emulator/covers/Kind of Blue.jpg" tools/vinyl-preview/back.png
    blender --background --factory-startup --python tools/vinyl-preview/concept.py --         <out dir> tools/emulator/covers [width] [samples] [views]

Views: corner, crate, deck, thumb, deck34 (comma separated; all of them if not said). The picture
is the phone's shape, 1440 x 3120. Five of them at 720 wide and 160 samples take under two minutes
on a GPU. `back.png` is the back of the sleeve that lies under the deck, and is made first.

Real dimensions throughout (metres, Z up): a 302mm record on a 332mm platter, a 453 x 353mm
direct-drive deck, 315mm sleeves in a crate. If a view is chosen, what is here gets rebuilt
properly in tools/models/build_models.py; this file only has to be good enough to choose by.
"""
import glob
import math
import os
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:]
OUT, COVERS = argv[0], argv[1]
WIDTH = int(argv[2]) if len(argv) > 2 else 540
SAMPLES = int(argv[3]) if len(argv) > 3 else 48
VIEWS = argv[4].split(",") if len(argv) > 4 else ["corner", "crate", "deck", "thumb", "deck34"]
BACK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "back.png")
HEIGHT = int(round(WIDTH * 3120 / 1440))

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
covers = sorted(glob.glob(os.path.join(COVERS, "*.jpg")))

# ---- materials ---------------------------------------------------------------------------------


def mat(name, color, metallic=0.0, rough=0.5, coat=0.0, aniso=0.0, emit=None, strength=0.0, sheen=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*color, 1)
    b.inputs["Metallic"].default_value = metallic
    b.inputs["Roughness"].default_value = rough
    b.inputs["Coat Weight"].default_value = coat
    b.inputs["Anisotropic"].default_value = aniso
    b.inputs["Sheen Weight"].default_value = sheen
    if emit:
        b.inputs["Emission Color"].default_value = (*emit, 1)
        b.inputs["Emission Strength"].default_value = strength
    return m


def image_mat(name, path, rough=0.45, coat=0.15):
    m = mat(name, (0.5, 0.5, 0.5), rough=rough, coat=coat)
    nt = m.node_tree
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = bpy.data.images.load(path, check_existing=True)
    tex.extension = "EXTEND"
    nt.links.new(tex.outputs["Color"], nt.nodes["Principled BSDF"].inputs["Base Color"])
    return m


def wood(name, dark, light, scale=3.0, stretch=(1.0, 14.0, 1.0), rough=0.38, coat=0.25):
    m = mat(name, light, rough=rough, coat=coat)
    nt = m.node_tree
    coord = nt.nodes.new("ShaderNodeTexCoord")
    mapping = nt.nodes.new("ShaderNodeMapping")
    mapping.inputs["Scale"].default_value = stretch
    noise = nt.nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = scale
    noise.inputs["Detail"].default_value = 9.0
    noise.inputs["Roughness"].default_value = 0.62
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position = 0.36
    ramp.color_ramp.elements[0].color = (*dark, 1)
    ramp.color_ramp.elements[1].position = 0.68
    ramp.color_ramp.elements[1].color = (*light, 1)
    bump = nt.nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.08
    nt.links.new(coord.outputs["Object"], mapping.inputs["Vector"])
    nt.links.new(mapping.outputs["Vector"], noise.inputs["Vector"])
    nt.links.new(noise.outputs["Fac"], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs["Color"], nt.nodes["Principled BSDF"].inputs["Base Color"])
    nt.links.new(noise.outputs["Fac"], bump.inputs["Height"])
    nt.links.new(bump.outputs["Normal"], nt.nodes["Principled BSDF"].inputs["Normal"])
    return m


def vinyl_mat(bands):
    """Black vinyl: grooves that stretch a highlight round the record, and the smooth rings between tracks."""
    m = mat("Vinyl", (0.004, 0.004, 0.0045), rough=0.20, aniso=0.92)
    nt = m.node_tree
    b = nt.nodes["Principled BSDF"]
    tangent = nt.nodes.new("ShaderNodeTangent")
    tangent.direction_type = "RADIAL"
    tangent.axis = "Z"
    nt.links.new(tangent.outputs["Tangent"], b.inputs["Tangent"])
    coord = nt.nodes.new("ShaderNodeTexCoord")
    length = nt.nodes.new("ShaderNodeVectorMath")
    length.operation = "LENGTH"
    nt.links.new(coord.outputs["Object"], length.inputs[0])
    # A ring is smooth where the radius is within a hair of a band: the gap between two tracks,
    # the run-in at the edge, the dead wax round the label.
    smooth = None
    for lo, hi in bands:
        a = nt.nodes.new("ShaderNodeMath")
        a.operation = "GREATER_THAN"
        a.inputs[1].default_value = lo
        z = nt.nodes.new("ShaderNodeMath")
        z.operation = "LESS_THAN"
        z.inputs[1].default_value = hi
        both = nt.nodes.new("ShaderNodeMath")
        both.operation = "MULTIPLY"
        nt.links.new(length.outputs["Value"], a.inputs[0])
        nt.links.new(length.outputs["Value"], z.inputs[0])
        nt.links.new(a.outputs[0], both.inputs[0])
        nt.links.new(z.outputs[0], both.inputs[1])
        if smooth is None:
            smooth = both
        else:
            add = nt.nodes.new("ShaderNodeMath")
            add.operation = "MAXIMUM"
            nt.links.new(smooth.outputs[0], add.inputs[0])
            nt.links.new(both.outputs[0], add.inputs[1])
            smooth = add
    rough = nt.nodes.new("ShaderNodeMapRange")
    rough.inputs["To Min"].default_value = 0.20
    rough.inputs["To Max"].default_value = 0.045
    aniso = nt.nodes.new("ShaderNodeMapRange")
    aniso.inputs["To Min"].default_value = 0.92
    aniso.inputs["To Max"].default_value = 0.0
    nt.links.new(smooth.outputs[0], rough.inputs["Value"])
    nt.links.new(smooth.outputs[0], aniso.inputs["Value"])
    nt.links.new(rough.outputs["Result"], b.inputs["Roughness"])
    nt.links.new(aniso.outputs["Result"], b.inputs["Anisotropic"])
    # The grooves themselves, as far as a picture this size can hold them: a fine ripple in the normal.
    wave = nt.nodes.new("ShaderNodeMath")
    wave.operation = "SINE"
    freq = nt.nodes.new("ShaderNodeMath")
    freq.operation = "MULTIPLY"
    freq.inputs[1].default_value = 2600.0
    nt.links.new(length.outputs["Value"], freq.inputs[0])
    nt.links.new(freq.outputs[0], wave.inputs[0])
    bump = nt.nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.035
    bump.inputs["Distance"].default_value = 0.0002
    nt.links.new(wave.outputs[0], bump.inputs["Height"])
    nt.links.new(bump.outputs["Normal"], b.inputs["Normal"])
    return m


ALU = mat("Aluminium", (0.62, 0.62, 0.63), metallic=1.0, rough=0.34, aniso=0.5)
ALU_SPUN = mat("Platter", (0.70, 0.70, 0.71), metallic=1.0, rough=0.22, aniso=0.85)
TOP = mat("TopPlate", (0.22, 0.22, 0.225), metallic=1.0, rough=0.40, aniso=0.35)
BODY = mat("Body", (0.018, 0.018, 0.019), rough=0.55)
RUBBER = mat("Rubber", (0.012, 0.012, 0.012), rough=0.85)
FELT = mat("Felt", (0.016, 0.016, 0.017), rough=0.95, sheen=0.6)
CHROME = mat("Chrome", (0.86, 0.86, 0.87), metallic=1.0, rough=0.07)
BLACK_GLOSS = mat("BlackGloss", (0.012, 0.012, 0.013), rough=0.22, coat=0.6)
WALNUT = wood("Walnut", (0.050, 0.022, 0.010), (0.190, 0.090, 0.040))
PINE = wood("Pine", (0.42, 0.26, 0.12), (0.66, 0.47, 0.25), scale=2.2, stretch=(14.0, 1.0, 1.0), rough=0.6, coat=0.0)
FLOOR = wood("Floor", (0.030, 0.016, 0.009), (0.085, 0.045, 0.024), scale=1.4, stretch=(1.0, 9.0, 1.0), rough=0.5, coat=0.1)
WALL = mat("Wall", (0.030, 0.040, 0.040), rough=0.92)
CARD = mat("Card", (0.72, 0.69, 0.62), rough=0.8)
STROBE = mat("Strobe", (0.3, 0.02, 0.01), emit=(1.0, 0.10, 0.04), strength=14.0)
TARGET = mat("Target", (0.8, 0.8, 0.7), emit=(1.0, 0.93, 0.78), strength=30.0)
WHITE_INK = mat("Ink", (0.75, 0.75, 0.74), rough=0.6)

# ---- primitives --------------------------------------------------------------------------------


def done(obj, name, material, smooth=False, bevel=0.0):
    obj.name = name
    obj.data.materials.append(material)
    if bevel:
        mod = obj.modifiers.new("bevel", "BEVEL")
        mod.width = bevel
        mod.segments = 3
        mod.limit_method = "ANGLE"
    if smooth:
        for p in obj.data.polygons:
            p.use_smooth = True
        split = obj.modifiers.new("split", "EDGE_SPLIT")
        split.split_angle = math.radians(40)
    return obj


def box(name, size, loc, material, bevel=0.0, rot=(0, 0, 0)):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc, rotation=rot)
    o = bpy.context.object
    o.scale = size
    bpy.ops.object.transform_apply(scale=True)
    return done(o, name, material, bevel=bevel)


def cyl(name, r, h, loc, material, verts=128, r2=None, rot=(0, 0, 0), bevel=0.0):
    bpy.ops.mesh.primitive_cone_add(vertices=verts, radius1=r, radius2=r if r2 is None else r2, depth=h, location=loc, rotation=rot)
    return done(bpy.context.object, name, material, smooth=True, bevel=bevel)


def tube(name, points, radius, material):
    cu = bpy.data.curves.new(name, "CURVE")
    cu.dimensions = "3D"
    sp = cu.splines.new("BEZIER")
    sp.bezier_points.add(len(points) - 1)
    for bp, p in zip(sp.bezier_points, points):
        bp.co = p
        bp.handle_left_type = bp.handle_right_type = "AUTO"
    cu.bevel_depth = radius
    cu.bevel_resolution = 8
    cu.use_fill_caps = True
    o = bpy.data.objects.new(name, cu)
    scene.collection.objects.link(o)
    o.data.materials.append(material)
    return o


# ---- the deck ------------------------------------------------------------------------------------

DECK_W, DECK_D = 0.453, 0.353
FOOT_H, BODY_H, PLATE_H = 0.018, 0.044, 0.018
TOP_Z = FOOT_H + BODY_H + PLATE_H
SPINDLE = Vector((-0.050, 0.012, 0.0))
PLATTER_R, PLATTER_H = 0.166, 0.020
MAT_H, LP_R, LP_H = 0.003, 0.151, 0.002
RECORD_Z = TOP_Z + PLATTER_H + MAT_H + LP_H
PIVOT = Vector((0.152, 0.106, TOP_Z + 0.050))
ARM_LENGTH = 0.230


def stylus_at(radius):
    """Where the stylus sits when the arm has swung in to [radius] from the spindle."""
    c, p = SPINDLE.xy, PIVOT.xy
    d = (p - c).length
    a = (d * d + radius * radius - ARM_LENGTH * ARM_LENGTH) / (2 * d)
    h = math.sqrt(max(radius * radius - a * a, 0.0))
    u = (p - c) / d
    n = Vector((u.y, -u.x))
    return c + u * a + n * h


def build_deck(cover, playing_radius=0.108):
    for sx in (-1, 1):
        for sy in (-1, 1):
            cyl("foot", 0.034, FOOT_H, (sx * (DECK_W / 2 - 0.05), sy * (DECK_D / 2 - 0.05), FOOT_H / 2), RUBBER, verts=48, bevel=0.003)
    box("body", (DECK_W - 0.004, DECK_D - 0.004, BODY_H), (0, 0, FOOT_H + BODY_H / 2), BODY, bevel=0.006)
    box("top", (DECK_W, DECK_D, PLATE_H), (0, 0, FOOT_H + BODY_H + PLATE_H / 2), TOP, bevel=0.004)

    # The platter: a spun rim that tapers out toward the bottom, beaded with the strobe's dots.
    px, py = SPINDLE.x, SPINDLE.y
    cyl("platter", PLATTER_R, PLATTER_H, (px, py, TOP_Z + PLATTER_H / 2), ALU_SPUN, r2=PLATTER_R - 0.006, bevel=0.0012)
    dot_mesh = None
    for row in range(4):
        count = 180 + row * 4
        z = TOP_Z + 0.0035 + row * 0.0042
        r = PLATTER_R - 0.006 * (z - TOP_Z) / PLATTER_H + 0.0004
        for i in range(count):
            a = 2 * math.pi * i / count
            if dot_mesh is None:
                bpy.ops.mesh.primitive_cube_add(size=1)
                o = bpy.context.object
                o.scale = (0.0016, 0.0020, 0.0024)
                bpy.ops.object.transform_apply(scale=True)
                o.data.materials.append(CHROME)
                dot_mesh = o.data
            else:
                o = bpy.data.objects.new("dot", dot_mesh)
                scene.collection.objects.link(o)
            o.location = (px + r * math.cos(a), py + r * math.sin(a), z)
            o.rotation_euler = (0, 0, a)
    cyl("mat", 0.149, MAT_H, (px, py, TOP_Z + PLATTER_H + MAT_H / 2), FELT)

    # The record: run-in, five tracks, dead wax, label.
    bands = [(0.1475, 0.1510), (0.1295, 0.1305), (0.1120, 0.1130), (0.0945, 0.0955), (0.0790, 0.0800), (0.0500, 0.0605)]
    lp = cyl("lp", LP_R, LP_H, (px, py, RECORD_Z - LP_H / 2), vinyl_mat(bands), verts=256)
    bpy.ops.mesh.primitive_circle_add(vertices=128, radius=0.050, fill_type="NGON", location=(px, py, RECORD_Z + 0.00015))
    label = bpy.context.object
    label.name = "label"
    uv = label.data.uv_layers.active or label.data.uv_layers.new(name="uv")
    for loop in label.data.loops:
        v = label.data.vertices[loop.vertex_index].co
        uv.data[loop.index].uv = (v.x / 0.100 + 0.5, v.y / 0.100 + 0.5)
    label.data.materials.append(image_mat("Label", cover, rough=0.55, coat=0.0))
    cyl("spindle", 0.0036, 0.016, (px, py, RECORD_Z + 0.006), CHROME, verts=32, bevel=0.001)

    # The arm.
    base = PIVOT.copy()
    cyl("arm_base", 0.043, 0.006, (base.x, base.y, TOP_Z + 0.003), ALU, verts=64, bevel=0.001)
    cyl("arm_tower", 0.021, 0.036, (base.x, base.y, TOP_Z + 0.006 + 0.018), ALU, verts=64, bevel=0.001)
    cyl("arm_gimbal", 0.027, 0.012, (base.x, base.y, PIVOT.z), BLACK_GLOSS, verts=64, bevel=0.0015)
    s = stylus_at(playing_radius)
    w = (s - PIVOT.xy).normalized()
    n = Vector((w.y, -w.x))
    z0 = PIVOT.z
    tip_z = RECORD_Z + 0.019

    def at(along, side, z):
        q = PIVOT.xy + w * along + n * side
        return (q.x, q.y, z)

    tube("arm", [at(-0.050, 0, z0), at(0.0, 0, z0), at(0.090, 0.000, z0 - 0.004), at(0.135, -0.014, z0 - 0.010),
                 at(0.176, 0.006, tip_z + 0.003), at(0.196, 0.004, tip_z + 0.001)], 0.0045, CHROME)
    angle = math.atan2(w.y, w.x)
    cyl("counterweight", 0.0165, 0.036, at(-0.066, 0, z0), BLACK_GLOSS, verts=64, rot=(0, math.pi / 2, angle), bevel=0.002)
    cyl("weight_ring", 0.0170, 0.006, at(-0.045, 0, z0), ALU, verts=64, rot=(0, math.pi / 2, angle))
    head = angle + math.radians(-21)
    hd = Vector((math.cos(head), math.sin(head)))
    hx, hy, _ = at(0.196, 0.004, 0)
    mid = Vector((hx, hy)) + hd * 0.021
    box("headshell", (0.046, 0.018, 0.0022), (mid.x, mid.y, tip_z), BLACK_GLOSS, bevel=0.0008, rot=(0, 0, head))
    box("cartridge", (0.022, 0.014, 0.012), (mid.x + hd.x * 0.008, mid.y + hd.y * 0.008, tip_z - 0.0072), BLACK_GLOSS, bevel=0.001, rot=(0, 0, head))
    lift = Vector((-hd.y, hd.x))
    box("finger_lift", (0.006, 0.020, 0.0016), (mid.x + lift.x * -0.016, mid.y + lift.y * -0.016, tip_z + 0.002), CHROME, bevel=0.0006, rot=(0, 0, head))
    cyl("stylus", 0.0006, 0.006, (mid.x + hd.x * 0.014, mid.y + hd.y * 0.014, RECORD_Z + 0.003), CHROME, verts=12)
    # Rest, cue lever, and the anti-skate dial.
    rest = stylus_at(0.176)
    cyl("rest_post", 0.005, 0.040, (rest.x + 0.02, rest.y - 0.012, TOP_Z + 0.020), ALU, verts=32)
    box("rest_cradle", (0.022, 0.012, 0.010), (rest.x + 0.02, rest.y - 0.012, TOP_Z + 0.044), BLACK_GLOSS, bevel=0.002)
    cyl("anti_skate", 0.011, 0.008, (base.x + 0.040, base.y - 0.046, TOP_Z + 0.004), BLACK_GLOSS, verts=48, bevel=0.001)

    # The controls along the front: start/stop, 33 and 45, the strobe, the pitch fader, the target light.
    fx, fy = -DECK_W / 2 + 0.046, -DECK_D / 2 + 0.036
    box("start", (0.052, 0.036, 0.007), (fx, fy, TOP_Z + 0.0035), ALU, bevel=0.0025)
    box("b33", (0.026, 0.014, 0.005), (fx + 0.052, fy - 0.010, TOP_Z + 0.0025), ALU, bevel=0.0015)
    box("b45", (0.026, 0.014, 0.005), (fx + 0.082, fy - 0.010, TOP_Z + 0.0025), ALU, bevel=0.0015)
    cyl("strobe_tower", 0.014, 0.020, (fx - 0.006, fy + 0.058, TOP_Z + 0.010), ALU, verts=48, bevel=0.002)
    box("strobe_lamp", (0.004, 0.012, 0.010), (fx + 0.008, fy + 0.060, TOP_Z + 0.010), STROBE, rot=(0, 0, math.radians(18)))
    sx, sy = DECK_W / 2 - 0.038, -0.060
    box("pitch_slot", (0.016, 0.118, 0.0012), (sx, sy, TOP_Z + 0.0002), BODY)
    box("pitch_knob", (0.026, 0.012, 0.012), (sx, sy + 0.012, TOP_Z + 0.006), BLACK_GLOSS, bevel=0.002)
    for i in range(-8, 9, 2):
        box("tick", (0.006 if i % 4 == 0 else 0.004, 0.0006, 0.0003), (sx - 0.016, sy + i * 0.0062, TOP_Z + 0.0002), WHITE_INK)
    tx, ty = 0.066, -DECK_D / 2 + 0.030
    cyl("target_post", 0.007, 0.030, (tx, ty, TOP_Z + 0.015), ALU, verts=32, bevel=0.001)
    cyl("target_lens", 0.0045, 0.002, (tx - 0.004, ty + 0.005, TOP_Z + 0.026), TARGET, verts=24, rot=(math.radians(-60), 0, math.radians(35)))
    spot = bpy.data.lights.new("target_light", "SPOT")
    spot.energy = 1.6
    spot.spot_size = math.radians(42)
    spot.spot_blend = 0.6
    spot.color = (1.0, 0.92, 0.78)
    spot.shadow_soft_size = 0.004
    so = bpy.data.objects.new("target_light", spot)
    scene.collection.objects.link(so)
    so.location = (tx - 0.006, ty + 0.008, TOP_Z + 0.030)
    aim(so, Vector((s.x, s.y, RECORD_Z)))
    glow = bpy.data.lights.new("strobe_glow", "POINT")
    glow.energy = 0.10
    glow.color = (1.0, 0.12, 0.05)
    glow.shadow_soft_size = 0.006
    go = bpy.data.objects.new("strobe_glow", glow)
    scene.collection.objects.link(go)
    go.location = (fx + 0.014, fy + 0.062, TOP_Z + 0.010)
    return s


def aim(obj, target):
    direction = target - obj.location
    obj.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()


# ---- sleeves and the crate ---------------------------------------------------------------------------

SLEEVE, SLEEVE_T = 0.315, 0.0034


def sleeve(cover, loc, lean, name="sleeve", face_up=False, yaw=0.0):
    """
    A sleeve standing on its bottom edge at [loc] (the middle of that edge), its cover toward -Y,
    leaning back by [lean] radians (negative: tipped forward, toward the browser).
    """
    bpy.ops.mesh.primitive_cube_add(size=1)
    o = bpy.context.object
    o.name = name
    o.scale = (SLEEVE, SLEEVE_T, SLEEVE)
    bpy.ops.object.transform_apply(scale=True)
    # Pivot on the bottom edge.
    for v in o.data.vertices:
        v.co.z += SLEEVE / 2
    # The cube's own unwrap is a cross of six faces; the cover goes edge to edge on one of them.
    uv = o.data.uv_layers.active or o.data.uv_layers.new(name="uv")
    o.data.materials.append(CARD)
    o.data.materials.append(image_mat("cover_" + os.path.basename(cover), cover))
    for poly in o.data.polygons:
        if poly.normal.y < -0.9:
            poly.material_index = 1
            for li in poly.loop_indices:
                v = o.data.vertices[o.data.loops[li].vertex_index].co
                uv.data[li].uv = (v.x / SLEEVE + 0.5, v.z / SLEEVE)
    if face_up:
        o.rotation_euler = (math.radians(90), 0, yaw)
    else:
        o.rotation_euler = (-lean, 0, yaw)
    o.location = loc
    return o


def build_crate(origin, flipped=5, behind=24, first=0):
    """A pine crate at [origin] (the middle of its floor), open at the top, its records facing -Y."""
    ox, oy, oz = origin
    inner_w, inner_d, height, t = 0.336, 0.420, 0.235, 0.012
    box("crate_floor", (inner_w + 2 * t, inner_d + 2 * t, t), (ox, oy, oz + t / 2), PINE, bevel=0.0015)
    for sx in (-1, 1):
        for k, zc in enumerate((0.055, 0.180)):
            box("crate_side", (t, inner_d + 2 * t, 0.095), (ox + sx * (inner_w / 2 + t / 2), oy, oz + t + zc), PINE, bevel=0.0015)
        for sy in (-1, 1):
            box("crate_post", (0.020, 0.020, height), (ox + sx * (inner_w / 2 - 0.004), oy + sy * (inner_d / 2 - 0.004), oz + t + height / 2), PINE, bevel=0.0015)
    for sy, zcs in ((-1, (0.040,)), (1, (0.055, 0.180))):
        for zc in zcs:
            box("crate_end", (inner_w + 2 * t, t, 0.070 if sy < 0 else 0.095), (ox, oy + sy * (inner_d / 2 + t / 2), oz + t + zc), PINE, bevel=0.0015)
    floor_z = oz + t
    # Flipped toward the browser: their backs to the camera, leaning on the front of the crate.
    y = oy - inner_d / 2 + 0.030
    k = first
    for i in range(flipped):
        sleeve(covers[k % len(covers)], (ox + (i % 3 - 1) * 0.002, y, floor_z), math.radians(-24 + i * 1.0), yaw=math.radians((i % 2) * 0.6 - 0.3))
        y += 0.0105
        k += 1
    # The one being looked at, and the rest leaning back against the far end.
    y_back = oy + inner_d / 2 - 0.020 - behind * 0.0098
    current = covers[k % len(covers)]
    for i in range(behind + 1):
        sleeve(covers[k % len(covers)], (ox + ((i * 7) % 5 - 2) * 0.0012, y_back + i * 0.0098, floor_z), math.radians(13 + (i % 4) * 0.5),
               yaw=math.radians(((i * 3) % 4) * 0.25 - 0.4))
        k += 1
    return current


# ---- the room ------------------------------------------------------------------------------------------

SIDEBOARD_H = 0.52
FLOOR_Z = -SIDEBOARD_H


def build_room():
    box("sideboard_top", (1.30, 0.50, 0.030), (0, 0.02, -0.015), WALNUT, bevel=0.003)
    box("sideboard", (1.26, 0.46, SIDEBOARD_H - 0.030 - 0.10), (0, 0.03, -0.030 - (SIDEBOARD_H - 0.13) / 2), WALNUT, bevel=0.003)
    for sx in (-1, 1):
        cyl("leg", 0.016, 0.10, (sx * 0.55, 0.03, FLOOR_Z + 0.05), BODY, verts=24, r2=0.022)
    box("floor", (4.0, 4.0, 0.02), (0, 0, FLOOR_Z - 0.01), FLOOR)
    box("wall", (4.0, 0.02, 3.0), (0, 0.29, 0.6), WALL)
    # Seen from straight above, the same wood as a table deep enough to lay a record's sleeve out on.
    desk = box("desk", (1.30, 1.50, 0.030), (0, -0.48, -0.015), WALNUT, bevel=0.003)
    back = sleeve(BACK, (0.0, -0.560, 0.0), 0.0, name="sleeve_back", face_up=True)
    back.rotation_euler = (math.radians(-90), 0, math.radians(-2.0))
    back.location = (0.030, -0.560, 0.0070)
    # And the front of it underneath, pushed out to one side as a sleeve and its inner are.
    front = sleeve(covers[13 % len(covers)], (0.0, 0.0, 0.0), 0.0, name="sleeve_front", face_up=True)
    front.rotation_euler = (math.radians(-90), 0, math.radians(5.0))
    front.location = (-0.062, -0.535, 0.0034)
    return [desk, back, front]


def lights(key=46.0, fill=2.2, lamp=9.0):
    world = bpy.data.worlds.new("world")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.010, 0.011, 0.014, 1)
    world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.10
    scene.world = world

    def area(name, energy, size, loc, target, color):
        l = bpy.data.lights.new(name, "AREA")
        l.energy = energy
        l.size = size
        l.color = color
        o = bpy.data.objects.new(name, l)
        scene.collection.objects.link(o)
        o.location = loc
        aim(o, Vector(target))
        return o

    # A shaded lamp up and to the left; a little cool sky from a window off to the right; and the
    # lamp's own pool on the wall.
    area("key", key, 0.34, (-0.70, -0.42, 1.05), (0.0, -0.05, 0.05), (1.0, 0.78, 0.52))
    area("fill", fill, 1.2, (1.3, -0.9, 0.7), (0.0, -0.1, -0.1), (0.55, 0.70, 1.0))
    area("wall_pool", lamp, 0.30, (-0.36, 0.06, 0.50), (-0.22, 0.29, 0.42), (1.0, 0.70, 0.40))


def camera(name, loc, target, lens=50.0, ortho=None, roll=0.0):
    cam = bpy.data.cameras.new(name)
    if ortho:
        cam.type = "ORTHO"
        cam.ortho_scale = ortho
    else:
        cam.lens = lens
    cam.sensor_fit = "VERTICAL"
    cam.sensor_height = 36.0
    cam.dof.use_dof = False
    o = bpy.data.objects.new(name, cam)
    scene.collection.objects.link(o)
    o.location = loc
    aim(o, Vector(target))
    if roll:
        o.rotation_euler.rotate_axis("Z", roll)
    return o


# ---- build and render -------------------------------------------------------------------------------------

flat_only = build_room()
playing = covers[13 % len(covers)]
stylus = build_deck(playing)
crate_at = (0.0, -0.62, FLOOR_Z)
build_crate(crate_at, first=3)
# The sleeve of what is playing, propped against the wall behind the deck.
propped = sleeve(playing, (0.30, 0.262, 0.0), math.radians(9), name="now_playing", yaw=math.radians(-4))
lights()

scene.render.engine = "CYCLES"
scene.cycles.samples = SAMPLES
scene.cycles.use_denoising = True
scene.cycles.max_bounces = 6
prefs = bpy.context.preferences.addons["cycles"].preferences
for kind in ("OPTIX", "CUDA", "HIP", "ONEAPI"):
    try:
        prefs.compute_device_type = kind
        prefs.get_devices()
        if any(d.type == kind for d in prefs.devices):
            for d in prefs.devices:
                d.use = d.type == kind
            scene.cycles.device = "GPU"
            print("rendering on", kind)
            break
    except Exception:
        pass
scene.render.resolution_x = WIDTH
scene.render.resolution_y = HEIGHT
scene.render.image_settings.file_format = "PNG"
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "AgX - Medium High Contrast"
scene.view_settings.exposure = 0.0

ASPECT = HEIGHT / WIDTH
cx, cy = SPINDLE.x, SPINDLE.y


def above(name, centre, width, plane_z=RECORD_Z, height=3.0):
    """Straight down from [height], through a long lens, on a strip [width] metres wide centred at [centre]."""
    lens = 36.0 * (height - plane_z) / (width * ASPECT)
    return camera(name, (centre[0], centre[1], height), (centre[0], centre[1], 0.0), lens=lens)


def toward(target, direction, distance):
    d = Vector(direction).normalized()
    return tuple(Vector(target) + d * distance)


crate_look = (0.0, crate_at[1] + 0.035, FLOOR_Z + 0.185)
SHOTS = {
    # The corner: the crate on the floor, the deck on the sideboard above it, the lamp on the wall.
    "corner": (lambda: camera("corner", (0.0, -2.05, 0.92), (0.0, -0.20, -0.17), lens=62), False),
    # Digging: over the crate, the record in front leaning back to be read.
    "crate": (lambda: camera("crate", toward(crate_look, (0.0, -0.62, 0.78), 1.36), crate_look, lens=58), False),
    # Straight down on the whole deck, the back of the sleeve laid out below it.
    "deck": (lambda: above("deck", (0.0, -0.215), 0.490, plane_z=TOP_Z), True),
    # Straight down and close: the record wider than the phone.
    "thumb": (lambda: above("thumb", (cx + 0.014, cy - 0.022), 0.212), True),
    # The deck as a thing in a room, from where you would stand.
    "deck34": (lambda: camera("deck34", (-0.30, -1.42, 0.86), (0.035, 0.02, 0.20), lens=46), False),
}
os.makedirs(OUT, exist_ok=True)
for view in VIEWS:
    make, flat = SHOTS[view]
    for o in flat_only:
        o.hide_render = not flat
    propped.hide_render = flat
    scene.camera = make()
    scene.render.filepath = os.path.join(OUT, view + ".png")
    bpy.ops.render.render(write_still=True)
    print("wrote", scene.render.filepath)
