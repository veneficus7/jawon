"""
Javon's jewel case and compact disc, generated with Blender.

    blender --background --factory-startup --python tools/models/build_models.py

Writes `app/src/main/assets/models/jewel_case.glb` and `disc.glb`.

---

WHY A SCRIPT AND NOT A .blend

ENGINEERING.md locked geometry to "procedural, no downloaded models", and M11 moved it
to imported glTF. This is both, and deliberately so. The models are *authored by
code* — parametric, diffable, reviewable in a pull request, and free of any
licence — but *built by Blender*, which brings the two things hand-written vertex
buffers were never going to give us cheaply: real bevels with correct shading
normals, and a glTF exporter that speaks `KHR_materials_clearcoat` and
`KHR_materials_anisotropy`.

The bevels are the point. Every edge in the old geometry was a perfect 90°
corner, and a perfect corner catches no specular highlight — which is the single
most reliable tell that something is computer graphics rather than an object.
BEVEL_W below is 0.35mm, roughly what a moulded polystyrene edge actually is.

If you open the .blend equivalent by hand and tweak it, your change is lost the
next time this runs. Change the numbers here instead.

---

COORDINATES

Modelled Z-up, the way Blender shows it, so the case stands upright in the
viewport and Numpad-1 front view looks at the cover:

    Blender X  = cover width   (125mm)  -> glTF X, app X
    Blender Z  = case height   (142mm)  -> glTF Y, app Y
    Blender Y  = -case thickness        -> glTF Z, app Z   (front face at -Y)

The exporter's Y-up conversion maps (x, y, z) -> (x, z, -y), which lands exactly
on the app's axes. `ActiveCase.kt` models the case cover-forward in the same
frame, so nothing needs rotating on the Kotlin side.

THE LID'S ORIGIN IS THE HINGE AXIS, not the lid's centre. `ActiveCase.kt` hangs
the lid off a bare `Node` at the hinge and swings that node 123°; giving the mesh
the same origin means the app parents it at local zero and the pivot is correct
by construction. Get this wrong and the lid swings around its middle, which is
the kind of bug that looks like an animation problem for a day.
"""

import math
import os
import sys

import bmesh
import bpy
from mathutils import Matrix, Vector

# ---------------------------------------------------------------------------
# Dimensions. These MUST agree with WallDimens in ui/wallscene/WallLayout.kt —
# the app positions art planes and the hinge against them, and a mismatch shows
# up as artwork floating off the case rather than as anything obviously wrong.
# ---------------------------------------------------------------------------

CASE_W = 0.010        # total thickness
CASE_H = 0.142        # height
CASE_D = 0.125        # cover width
TRAY_T = 0.006        # the tray half of the 10mm; the lid closes flush into the rest

DISC_R = 0.060        # a real CD: 120mm across
DISC_HOLE_R = 0.0075  # 15mm centre hole
DISC_T = 0.0012       # and 1.2mm thick, which the old flat annulus was not

# Edge break. The whole reason for using Blender.
BEVEL_W = 0.00035
BEVEL_SEGMENTS = 2

WALL_T = 0.0013       # moulded wall thickness of the tray shell
SMOOTH_ANGLE = math.radians(35.0)

OUT_DIR = os.path.join("app", "src", "main", "assets", "models")


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------

def clear_scene():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for block in (bpy.data.meshes, bpy.data.materials, bpy.data.objects):
        for item in list(block):
            try:
                block.remove(item)
            except RuntimeError:
                pass


def new_mesh_object(name):
    mesh = bpy.data.meshes.new(name)
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)
    return obj


def az(app_z):
    """
    App/glTF Z -> Blender Y.

    **Read this before touching any thickness value in this file.** The exporter's
    Y-up conversion maps Blender (x, y, z) -> glTF (x, z, -y), so the case's
    thickness axis is *negated* on the way out: the front of the case, at app
    +Z, lives at Blender -Y.

    The first version of build_tray() ignored that and computed its front and
    back faces as though Blender Y were app Z directly. Everything still
    exported, still loaded, still rendered — as a tray mirrored through the
    case's mid-plane, sitting in the lid's half with its well facing away from
    the camera. On screen it read as a featureless slab, which looks like a
    lighting or material problem and is neither.

    So: think in app Z, and put every thickness coordinate through here.
    """
    return -app_z


def box(bm, cx, cy, cz, sx, sy, sz):
    """An axis-aligned box, by centre and full size. `cy` is Blender Y — see [az]."""
    bmesh.ops.create_cube(bm, size=1.0, matrix=(
        Matrix.Translation((cx, cy, cz)) @ Matrix.Diagonal((sx, sy, sz, 1.0))
    ))


def cylinder(bm, cx, cy, cz, radius, depth, segments=32, axis="Y"):
    """A cylinder centred at (cx,cy,cz). `axis` is the axis it runs along."""
    if axis == "Y":
        rot = Matrix.Rotation(math.radians(90.0), 4, "X")
    elif axis == "Z":
        rot = Matrix.Identity(4)
    else:
        rot = Matrix.Rotation(math.radians(90.0), 4, "Y")
    bmesh.ops.create_cone(
        bm,
        cap_ends=True,
        cap_tris=False,
        segments=segments,
        radius1=radius,
        radius2=radius,
        depth=depth,
        matrix=(Matrix.Translation((cx, cy, cz)) @ rot),
    )


def finish(obj, bevel=True, bevel_width=None):
    """Bevel, smooth-by-angle, and hand back a ready object."""
    if bevel:
        mod = obj.modifiers.new(name="Bevel", type="BEVEL")
        mod.width = bevel_width if bevel_width is not None else BEVEL_W
        mod.segments = BEVEL_SEGMENTS
        mod.limit_method = "ANGLE"
        mod.angle_limit = math.radians(30.0)
        # Clamp stops the bevel eating thin features whole — the tray walls are
        # 1.3mm and a 0.35mm bevel on both sides of them is already close.
        mod.use_clamp_overlap = True
        mod.harden_normals = False

    # After the bevel, never before. The exporter derives tangents from the mesh
    # and silently gives up on n-gons — "Could not calculate tangents" — which
    # would leave the disc's anisotropy with no frame to orient against and no
    # visible error beyond the highlight simply being wrong.
    obj.modifiers.new(name="Triangulate", type="TRIANGULATE")

    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)
    bpy.ops.object.shade_auto_smooth(angle=SMOOTH_ANGLE)
    obj.select_set(False)


# ---------------------------------------------------------------------------
# Materials
# ---------------------------------------------------------------------------

def principled(name, base_color, metallic, roughness,
               coat=0.0, coat_roughness=0.05, anisotropic=0.0,
               alpha=1.0, blend=False):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes["Principled BSDF"]

    def setv(key, value):
        if key in bsdf.inputs:
            bsdf.inputs[key].default_value = value

    setv("Base Color", (*base_color, 1.0))
    setv("Metallic", metallic)
    setv("Roughness", roughness)
    setv("Alpha", alpha)
    # -> KHR_materials_clearcoat. A separate specular lobe over the base, which
    # is physically what a moulded plastic's polished skin is, and the thing the
    # stock SceneView material could not express at all.
    setv("Coat Weight", coat)
    setv("Coat Roughness", coat_roughness)
    # -> KHR_materials_anisotropy. Colourless directional stretch of the
    # highlight. NOT iridescence — that was built in M8 and removed because the
    # owner did not want a rainbow, and this is a different thing entirely.
    setv("Anisotropic", anisotropic)

    if blend:
        # Blender 4.2+ renamed/reshuffled these; guard so the script survives it.
        for attr, value in (("blend_method", "BLEND"), ("surface_render_method", "BLENDED")):
            if hasattr(mat, attr):
                try:
                    setattr(mat, attr, value)
                except TypeError:
                    pass
    return mat


def attach_placeholder_basecolor(mat, name):
    """
    Hang a tiny white image off Base Color so the exported material carries a
    baseColorTexture slot.

    **This is load-bearing, not decoration.** gltfio compiles a material variant
    per glTF material, keyed on which texture slots that material declares, and
    signals "no texture here" with `baseColorIndex = -1`. A material exported
    without a baseColorTexture therefore produces a shader that never samples one
    — and the app's `discTexture` would have nowhere to bind, silently, with the
    disc rendering as a flat grey circle and nothing in the log to say why.

    With the slot declared, `WallSceneScreen` swaps the real album art in at
    runtime via `setParameter("baseColorMap", ...)`. 8x8 keeps the embedded PNG
    down to a couple of hundred bytes.
    """
    img = bpy.data.images.new(name, width=8, height=8, alpha=False)
    img.generated_color = (1.0, 1.0, 1.0, 1.0)
    tex = mat.node_tree.nodes.new("ShaderNodeTexImage")
    tex.image = img
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    mat.node_tree.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    return mat


# ---------------------------------------------------------------------------
# The tray — the back half of the case, 6mm of the 10mm.
# ---------------------------------------------------------------------------

def build_tray():
    obj = new_mesh_object("tray")
    bm = bmesh.new()

    # In APP Z throughout — see [az]. The tray owns the back 6mm of the case's 10.
    z_back = -CASE_W / 2.0            # -0.005, the case's outer back face
    z_front = z_back + TRAY_T         # +0.001, where the lid closes onto it

    # Outer shell as a floor plus four walls, rather than a solid block with a
    # boolean well cut out of it. Booleans on millimetre features produce
    # degenerate slivers that the bevel then explodes; walls are exact.
    floor_t = 0.0011
    box(bm, 0.0, az(z_back + floor_t / 2.0), 0.0, CASE_D, floor_t, CASE_H)

    # Walls stand off the floor toward the front, leaving the well the disc sits in.
    wall_h = TRAY_T - floor_t
    wall_y = az(z_back + floor_t + wall_h / 2.0)
    # left / right
    box(bm, -(CASE_D - WALL_T) / 2.0, wall_y, 0.0, WALL_T, wall_h, CASE_H)
    box(bm, +(CASE_D - WALL_T) / 2.0, wall_y, 0.0, WALL_T, wall_h, CASE_H)
    # top / bottom
    box(bm, 0.0, wall_y, +(CASE_H - WALL_T) / 2.0, CASE_D - 2 * WALL_T, wall_h, WALL_T)
    box(bm, 0.0, wall_y, -(CASE_H - WALL_T) / 2.0, CASE_D - 2 * WALL_T, wall_h, WALL_T)

    # The spindle rosette: a central button ringed by the flexible fingers that
    # actually grip the disc, standing up off the well floor toward the viewer.
    # Uncovered the moment the disc lifts away, at close to full frame height —
    # worth the triangles it costs.
    hub_z = z_back + floor_t          # the well floor
    cylinder(bm, 0.0, az(hub_z + 0.00125), 0.0, 0.0042, 0.0025, segments=24, axis="Y")
    fingers = 8
    for i in range(fingers):
        a = 2.0 * math.pi * i / fingers
        fx, fz = math.cos(a) * 0.0064, math.sin(a) * 0.0064
        m = (Matrix.Translation((fx, az(hub_z + 0.0011), fz))
             @ Matrix.Rotation(-a, 4, "Y")
             @ Matrix.Diagonal((0.0026, 0.0022, 0.0016, 1.0)))
        bmesh.ops.create_cube(bm, size=1.0, matrix=m)

    # Hinge knuckles on the spine edge, where the lid's tabs engage. Small, but
    # they sit right at the pivot and the eye goes there while the lid swings.
    for sz in (+1.0, -1.0):
        cz = sz * (CASE_H / 2.0 - 0.010)
        cylinder(bm, -CASE_D / 2.0 + 0.0016, az(z_front - 0.0009), cz,
                 0.0013, 0.006, segments=12, axis="Z")

    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-6)
    bm.to_mesh(obj.data)
    bm.free()

    finish(obj)
    obj.data.materials.append(
        principled(
            "JewelCasePlastic",
            base_color=(0.055, 0.050, 0.044),
            metallic=0.0,
            # Was 0.45 on the stock material, which read as a chalky solid.
            # Moulded polystyrene is glossy; the coat above carries the shine.
            roughness=0.22,
            coat=0.85,
            coat_roughness=0.06,
        )
    )
    return obj


# ---------------------------------------------------------------------------
# The lid — clear front half, ORIGIN ON THE HINGE AXIS. See module docstring.
# ---------------------------------------------------------------------------

def build_lid():
    obj = new_mesh_object("lid")
    bm = bmesh.new()

    # App Z, relative to the hinge — which sits at absolute app z = TRAY_FRONT_Z.
    # The lid therefore runs from 0 (against the tray) out to +4mm, landing its
    # outer face on the case's own front at app z = +CASE_W/2.
    lid_t = CASE_W - TRAY_T           # 4mm
    z_inner = 0.0                     # meets the tray
    z_outer = lid_t                   # the case's outer front face
    face_t = 0.0009                   # the clear sheet the inlay shows through

    cx = CASE_D / 2.0                 # lid centre, hinge-relative

    # Clear front sheet.
    box(bm, cx, az(z_outer - face_t / 2.0), 0.0, CASE_D, face_t, CASE_H)

    # Rim standing back off it toward the tray, which is what gives the closed
    # case its seam and the open lid a visible thickness.
    rim_h = lid_t - face_t
    rim_y = az(z_outer - face_t - rim_h / 2.0)
    box(bm, WALL_T / 2.0, rim_y, 0.0, WALL_T, rim_h, CASE_H)
    box(bm, CASE_D - WALL_T / 2.0, rim_y, 0.0, WALL_T, rim_h, CASE_H)
    box(bm, cx, rim_y, +(CASE_H - WALL_T) / 2.0, CASE_D - 2 * WALL_T, rim_h, WALL_T)
    box(bm, cx, rim_y, -(CASE_H - WALL_T) / 2.0, CASE_D - 2 * WALL_T, rim_h, WALL_T)

    # Hinge tabs, reaching back past the rim to meet the tray's knuckles — so
    # *behind* the hinge plane, at negative app Z.
    for sz in (+1.0, -1.0):
        cz = sz * (CASE_H / 2.0 - 0.010)
        box(bm, 0.0011, az(z_inner - 0.0011), cz, 0.0022, 0.0022, 0.0075)

    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-6)
    bm.to_mesh(obj.data)
    bm.free()

    finish(obj)
    obj.data.materials.append(
        principled(
            "JewelCaseClear",
            base_color=(0.86, 0.90, 0.93),
            metallic=0.0,
            roughness=0.035,
            coat=1.0,
            coat_roughness=0.02,
            alpha=0.22,
            blend=True,
        )
    )
    return obj


# ---------------------------------------------------------------------------
# The disc.
# ---------------------------------------------------------------------------

def build_disc():
    """
    A real CD, with thickness and a broken edge — the old geometry was a flat
    annulus with every normal +Z, which `docs/materials.md` already noted made
    any Fresnel term collapse to nothing across the whole face.

    UVs are a planar projection from the front, normalised over the disc's
    bounding square. That is exactly the radial convention the old
    `DiscGeometry` emitted and that `DiscArt.bakeFace` writes against, so the
    app's existing disc texture maps unchanged.
    """
    obj = new_mesh_object("disc")
    bm = bmesh.new()

    segments = 192  # up from 96; the disc fills much of the frame when it is up
    rot = Matrix.Rotation(math.radians(90.0), 4, "X")

    # Bridge two concentric edge loops into a flat annulus, rather than cutting a
    # hole out of a solid cylinder. A boolean would work here, but it produces
    # topology nobody chose — and the first attempt at this built the inner
    # cylinder *as well as* subtracting it, which left the mesh self-intersecting
    # and got "Mesh disc is not valid" out of the exporter. Bridging two loops
    # cannot go wrong that way and gives clean quads for the bevel to follow.
    outer = bmesh.ops.create_circle(
        bm, cap_ends=False, segments=segments, radius=DISC_R, matrix=rot)
    inner = bmesh.ops.create_circle(
        bm, cap_ends=False, segments=segments, radius=DISC_HOLE_R, matrix=rot)
    loop_edges = [e for e in bm.edges]
    bmesh.ops.bridge_loops(bm, edges=loop_edges)

    bm.to_mesh(obj.data)
    bm.free()

    # Real thickness. The old geometry was an infinitely thin annulus with every
    # normal +Z — docs/materials.md notes that made `dot(N,V)` span 1.0 to 0.994
    # across the whole face, so any Fresnel term collapsed to nothing. A disc
    # with an actual edge has somewhere for a highlight to live.
    solidify = obj.modifiers.new(name="Thickness", type="SOLIDIFY")
    solidify.thickness = DISC_T
    solidify.offset = 0.0
    solidify.use_even_offset = True

    # A much finer bevel than the case: 1.2mm of edge cannot take 0.35mm.
    finish(obj, bevel_width=0.00012)

    # Planar UVs from the front, matching DiscGeometry's radial convention.
    mesh = obj.data
    if not mesh.uv_layers:
        mesh.uv_layers.new(name="UVMap")
    uv = mesh.uv_layers.active.data
    for poly in mesh.polygons:
        for li in poly.loop_indices:
            v = mesh.vertices[mesh.loops[li].vertex_index].co
            uv[li].uv = (v.x / (2.0 * DISC_R) + 0.5, v.z / (2.0 * DISC_R) + 0.5)

    mesh.materials.append(
        attach_placeholder_basecolor(principled(
            "DiscFace",
            base_color=(0.80, 0.80, 0.82),
            # THE PRINTED SIDE, not the data side. The owner's point: this face
            # is the label, and the mirror side is turned away from the camera
            # for the whole sequence. So it is modelled as what it physically is
            # — ink screen-printed onto a white base coat, under a clear lacquer.
            #
            # The old material had metallic 0.72 across the whole face, which is
            # the reflectance of bare aluminium and treats the artwork as if it
            # were etched into the mirror. Backed off to leave some of the
            # silvered substrate showing through, the way a real disc's print
            # does, without the label reading as chrome.
            metallic=0.25,
            roughness=0.28,
            # The lacquer, and the main reason this now reads as a disc: a
            # printed surface under a glossy clear coat has two separate
            # speculars, a soft one from the ink and a tight one from the
            # varnish. One roughness value can only ever express one of them.
            coat=0.7,
            coat_roughness=0.045,
            # NO ANISOTROPY — deliberately, after building it and taking it out.
            #
            # The circular sweep everyone pictures on a CD comes from diffraction
            # off the data spiral, and it lives on the *unprinted* side. Since
            # that side never faces the camera here, anisotropy would only stretch
            # the highlight along the UV axes — a fixed linear smear, which reads
            # as brushed steel rather than as a disc. Doing it properly would need
            # an anisotropyTexture encoding a tangential direction per texel,
            # which is real work for an effect on a surface we never show.
            anisotropic=0.0,
        ), "disc_face_placeholder")
    )
    return obj


# ---------------------------------------------------------------------------
# Export
# ---------------------------------------------------------------------------

def export(objects, filename):
    bpy.ops.object.select_all(action="DESELECT")
    for obj in objects:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = objects[0]

    path = os.path.abspath(os.path.join(OUT_DIR, filename))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=path,
        export_format="GLB",
        use_selection=True,
        export_apply=True,      # bake the bevel modifier
        export_yup=True,        # Blender Z-up -> glTF Y-up; see module docstring
        export_normals=True,
        export_tangents=True,   # anisotropy needs a tangent frame
        export_texcoords=True,
        export_materials="EXPORT",
        export_extras=False,
    )
    return path


def main():
    """
    One file per part, rather than one jewel_case.glb holding both.

    `ActiveCase.kt` already has the hierarchy these belong in — the tray under
    the case root, the lid under the bare `Node` that acts as the hinge — and one
    `FilamentInstance` per file drops straight into it. A combined file would
    mean loading it, hunting its children by name, and reparenting the lid out
    from under its own asset root, which is more code to express the same tree
    and one more thing to get wrong.
    """
    paths = []

    clear_scene()
    paths.append(export([build_tray()], "case_tray.glb"))

    clear_scene()
    paths.append(export([build_lid()], "case_lid.glb"))

    clear_scene()
    paths.append(export([build_disc()], "disc.glb"))

    for path in paths:
        size = os.path.getsize(path)
        print(f"JAVON-BUILD wrote {path} ({size / 1024.0:.1f} KB)")


if __name__ == "__main__":
    main()
