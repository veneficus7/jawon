"""
Look at an exported model without a phone.

    blender --background --factory-startup --python tools/models/preview_model.py \
        -- pocket.glb out.png [--angle three-quarter|front|back] [--samples 128]

**Why this exists.** The room cannot be judged without a device, but a *model* can:
it is geometry and materials, and both are wrong in ways a still frame shows
immediately — a hole that filled itself in, a bevel that ate a feature, a face
pointing the wrong way, a material that reads as plastic when it should read as
metal. Catching those here costs seconds; catching them on the phone costs a
build, an install and somebody's attention.

It re-imports the exported `.glb` rather than rendering the scene that made it, so
what is judged is the file the app will actually load — the same reason
`docs/models.md` checks exported bounds rather than Blender's.

This is Cycles, not Filament. It is not a preview of how the room will look: no
Filament tonemapper, no IBL from the room, no post. It answers "is the object
right", not "is the shot right".
"""

import math
import os
import sys

import bpy
from mathutils import Vector


def argv():
    return sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []


def option(args, name, default):
    return args[args.index(name) + 1] if name in args else default


def clear():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)


def report(objects):
    """Bounds and counts per object, in APP axes, which is how docs/models.md reads."""
    print("JAVON-PREVIEW  object                 verts   tris    "
          "app X (mm)        app Y (mm)        app Z (mm)")
    for obj in sorted(objects, key=lambda o: o.name):
        mesh = obj.data
        xs, ys, zs = [], [], []
        for v in mesh.vertices:
            w = obj.matrix_world @ v.co
            # glTF import puts Y-up into Blender Z-up: app X = X, app Y = Z, app Z = -Y.
            xs.append(w.x); ys.append(w.z); zs.append(-w.y)
        tris = sum(len(p.vertices) - 2 for p in mesh.polygons)
        print("JAVON-PREVIEW  %-20s %6d %6d   %6.1f..%6.1f   %6.1f..%6.1f   %6.2f..%6.2f"
              % (obj.name, len(mesh.vertices), tris,
                 min(xs) * 1000, max(xs) * 1000,
                 min(ys) * 1000, max(ys) * 1000,
                 min(zs) * 1000, max(zs) * 1000))


def studio(target, radius, angle):
    """
    A soft key, a cool rim and a big dark floor — enough to read form and finish.

    Area lights, deliberately: this is Cycles, where they cost nothing extra, and a
    polished metal back is mostly a picture of the lights around it. A point light
    would show a point, which says nothing about how the surface will behave in a
    room full of long, moving sources.
    """
    bpy.ops.mesh.primitive_plane_add(size=radius * 40, location=(0, 0, -radius * 1.2))
    floor = bpy.context.active_object
    mat = bpy.data.materials.new("floor"); mat.use_nodes = True
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = (0.02, 0.02, 0.022, 1.0)
    bsdf.inputs["Roughness"].default_value = 0.35
    floor.data.materials.append(mat)

    # **Watts scale with the square of the distance, so energies are written as a
    # coefficient on radius².** The first version used fixed wattages that suited a
    # metre-sized prop; on a 10cm object at 15cm they blew every surface to white and
    # the render said nothing about any material.
    r2 = radius * radius
    for name, loc, size, energy, color in (
        ("key",  (-radius * 2.2, -radius * 2.6, radius * 2.4), radius * 3.0, 420.0 * r2, (1.0, 0.94, 0.86)),
        ("fill", (radius * 2.8, -radius * 1.6, radius * 0.4), radius * 3.4, 120.0 * r2, (0.82, 0.88, 1.0)),
        ("rim",  (radius * 0.6, radius * 3.0, radius * 1.8), radius * 2.0, 240.0 * r2, (0.78, 0.86, 1.0)),
    ):
        bpy.ops.object.light_add(type="AREA", location=loc)
        light = bpy.context.active_object
        light.name = name
        light.data.size = size
        light.data.energy = energy
        light.data.color = color
        direction = (Vector(target) - Vector(loc)).normalized()
        light.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()

    world = bpy.data.worlds.new("w")
    bpy.context.scene.world = world
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (0.012, 0.013, 0.016, 1.0)
    world.node_tree.nodes["Background"].inputs[1].default_value = 1.0


def place_camera(target, radius, angle):
    # 5 radii back with an 85mm lens frames the whole object with a margin; 3.4 cropped
    # a device that is twice as tall as it is wide.
    if angle == "front":
        eye = Vector((0.0, -radius * 5.0, 0.0))
    elif angle == "back":
        eye = Vector((0.0, radius * 5.0, 0.0))
    else:  # three-quarter, from slightly above — how a thing in the hand is seen
        eye = Vector((-radius * 2.8, -radius * 3.8, radius * 2.5))
    bpy.ops.object.camera_add(location=eye)
    cam = bpy.context.active_object
    cam.data.lens = 85.0            # a portrait lens: little perspective distortion
    cam.data.sensor_width = 36.0
    cam.data.dof.use_dof = True
    cam.data.dof.focus_distance = (Vector(target) - eye).length
    cam.data.dof.aperture_fstop = 5.6
    cam.rotation_euler = (Vector(target) - eye).to_track_quat("-Z", "Y").to_euler()
    bpy.context.scene.camera = cam


def main():
    args = argv()
    if len(args) < 2:
        raise SystemExit("usage: -- <model.glb> <out.png> [--angle a] [--samples n]")
    model, out = args[0], args[1]
    angle = option(args, "--angle", "three-quarter")
    samples = int(option(args, "--samples", "128"))
    # Clay: one matte grey on everything. **The first thing to reach for when a render
    # looks wrong**, because it separates the two questions — a polished metal under a
    # big soft light can hide a chamfer, invent one, or read as white, and clay cannot.
    clay = "--clay" in args

    path = model if os.path.isabs(model) else os.path.abspath(
        os.path.join("app", "src", "main", "assets", "models", model))

    clear()
    bpy.ops.import_scene.gltf(filepath=path)
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    if not meshes:
        raise SystemExit("no meshes imported from " + path)
    report(meshes)

    if clay:
        mat = bpy.data.materials.new("clay")
        mat.use_nodes = True
        bsdf = mat.node_tree.nodes["Principled BSDF"]
        bsdf.inputs["Base Color"].default_value = (0.32, 0.32, 0.33, 1.0)
        bsdf.inputs["Roughness"].default_value = 0.62
        bsdf.inputs["Metallic"].default_value = 0.0
        for obj in meshes:
            obj.data.materials.clear()
            obj.data.materials.append(mat)

    lo = Vector((1e9, 1e9, 1e9))
    hi = Vector((-1e9, -1e9, -1e9))
    for obj in meshes:
        for corner in obj.bound_box:
            w = obj.matrix_world @ Vector(corner)
            lo = Vector((min(lo.x, w.x), min(lo.y, w.y), min(lo.z, w.z)))
            hi = Vector((max(hi.x, w.x), max(hi.y, w.y), max(hi.z, w.z)))
    target = (lo + hi) / 2.0
    radius = max((hi - lo).length / 2.0, 1e-4)

    studio(target, radius, angle)
    place_camera(target, radius, angle)

    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = samples
    scene.cycles.use_denoising = True
    scene.render.resolution_x = 1100
    scene.render.resolution_y = 1500
    scene.render.film_transparent = False
    scene.view_settings.view_transform = "AgX"
    scene.render.filepath = out if os.path.isabs(out) else os.path.abspath(out)
    bpy.ops.render.render(write_still=True)
    print("JAVON-PREVIEW wrote " + scene.render.filepath)


if __name__ == "__main__":
    main()
