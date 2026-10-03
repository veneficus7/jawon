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
import random
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


def finish(obj, bevel=True, bevel_width=None, harden=False):
    """
    Bevel, smooth-by-angle, and hand back a ready object.

    [harden] keeps the flat faces flat. **Reach for it on any large flat panel with a
    small bevel.** Without it the bevel's 45° faces are averaged into the vertex
    normals all along the panel's boundary, and because a filled face can be one
    enormous triangle fan, that tilt is then interpolated across the *entire* panel:
    the pocket player's 1.1mm front plate rendered as a deep sloping tray, which
    reads as a modelling mistake and is a shading one.
    """
    if bevel:
        mod = obj.modifiers.new(name="Bevel", type="BEVEL")
        mod.width = bevel_width if bevel_width is not None else BEVEL_W
        mod.segments = BEVEL_SEGMENTS
        mod.limit_method = "ANGLE"
        mod.angle_limit = math.radians(30.0)
        # Clamp stops the bevel eating thin features whole — the tray walls are
        # 1.3mm and a 0.35mm bevel on both sides of them is already close.
        mod.use_clamp_overlap = True
        mod.harden_normals = harden

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
               alpha=1.0, blend=False, transmission=0.0, ior=1.5):
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
    # **Only when there is a coat.** Setting the roughness alone makes the exporter emit
    # KHR_materials_clearcoat with no factor — a coat of weight zero — and gltfio keys its
    # shader on the extension's *presence*: every such material compiles the clearcoat
    # variant for nothing, and on a transmissive one the ubershader then drops the
    # transmission outright (see PocketGlass). Models already committed were exported with
    # the empty extension and keep it until they are next regenerated.
    if coat > 0.0:
        setv("Coat Roughness", coat_roughness)
    # -> KHR_materials_anisotropy. Colourless directional stretch of the
    # highlight. NOT iridescence — that was built in M8 and removed because the
    # owner did not want a rainbow, and this is a different thing entirely.
    setv("Anisotropic", anisotropic)
    # -> KHR_materials_transmission + KHR_materials_ior. This is what separates *clear
    # plastic* from *a pale surface at low alpha*: alpha blending only lets the background
    # through, whereas transmission bends it, so edges and corners pick up the thick, bright
    # caustic-ish look a moulded crystal case actually has. Polystyrene is ior 1.59.
    setv("Transmission Weight", transmission)
    setv("IOR", ior)

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
    # Write the buffer explicitly rather than relying on `generated_color`, which is only a
    # hint to the generator and leaves the pixel buffer lazily evaluated. Exporting it as-is
    # encoded whatever happened to be in memory, so disc.glb came out with a different hash
    # on every run — 188KB of binary churn in git each time anyone regenerated the models,
    # while the three case models stayed byte-identical.
    img.pixels[:] = [1.0] * (8 * 8 * 4)
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
# The closed case — what stands on the shelf, spine out.
# ---------------------------------------------------------------------------

def build_closed_case():
    """
    A shut jewel case, modelled **in the shelved frame** rather than the held one.

    Two things make this a separate model rather than the tray and lid posed shut:

    1. **Nothing inside it is visible.** The well, the spindle rosette and the
       hinge knuckles are the bulk of the tray's 2176 triangles and every one of
       them is enclosed. The wall draws up to ~470 of these at once (see
       MAX_CASE_SLOTS in CaseSlots.kt), so carrying that interior would cost over
       a million triangles to render nothing.
    2. **Its axes differ.** A shelved case is thickness-on: X is the 10mm edge,
       Z the 125mm depth, and the spine faces the viewer at +Z. The held case is
       cover-forward. Modelling this one in the shelved frame directly means
       `CaseSlots` needs no rotation and the existing art-plate positions, which
       are tuned against those axes, keep working untouched.

    What it does carry is what you can actually see edge-on: broken edges, the
    seam where the lid meets the tray, and the two hinge barrels — which on a
    real jewel case sit on the same edge as the spine, and are therefore pointed
    straight at you on a shelf.
    """
    obj = new_mesh_object("case_closed")
    bm = bmesh.new()

    # App axes here: X = thickness, Y = height, Z = depth (spine at +Z).
    # `az()` still converts the axis that points at the viewer, which is Z.
    tray_cx = -CASE_W / 2.0 + TRAY_T / 2.0
    lid_t = CASE_W - TRAY_T
    lid_cx = -CASE_W / 2.0 + TRAY_T + lid_t / 2.0

    # A hair of air between the halves so their bevels read as a seam instead of
    # fusing into one slab. This is the line that says "this thing opens".
    seam = 0.00014

    # TWO MATERIAL SLOTS, and this is the whole point of the rebuild. The case used to be one
    # opaque dark slab, which read as a lump of plastic rather than a jewel case. Now the back
    # half is opaque moulded tray and the front half is the clear shell you actually see the
    # artwork through.
    #
    # Faces are tagged by which pass built them: everything standing when the tray is done is
    # slot 0, everything added afterwards is slot 1. Tagged before `remove_doubles`, which can
    # merge geometry and scramble any index-based scheme.
    box(bm, tray_cx, az(0.0), 0.0, TRAY_T - seam, CASE_D, CASE_H)
    tray_faces = set(bm.faces)

    box(bm, lid_cx, az(0.0), 0.0, lid_t - seam, CASE_D, CASE_H)
    for f in bm.faces:
        f.material_index = 0 if f in tray_faces else 1

    # Hinge barrels. The pin is vertical on a real case, so these run along the
    # height axis and show as two short cylinders at the top and bottom of the
    # spine — the only moving part you can see from a shelf.
    for sz in (+1.0, -1.0):
        cz = sz * (CASE_H / 2.0 - 0.011)
        cylinder(bm, 0.0, az(CASE_D / 2.0 - 0.0017), cz,
                 0.0017, 0.0075, segments=10, axis="Z")
    # Knuckles belong to the moulded tray, not the clear shell.
    for f in bm.faces:
        if f not in tray_faces and f.calc_center_median().x < tray_cx + TRAY_T:
            f.material_index = 0

    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-6)
    bm.to_mesh(obj.data)
    bm.free()

    finish(obj)

    # Slot 0 — the moulded tray. Still dark, because a real jewel case's tray is, but this is
    # now only the back half of the object rather than all of it.
    # Lifted well off near-black. At 0.048 the tray swallowed every highlight the clear shell
    # threw, so the whole case read as one dull dark lump however shiny the front was — a
    # transparent thing over a black thing still looks black.
    obj.data.materials.append(
        principled(
            "JewelCaseTray",
            base_color=(0.16, 0.155, 0.15),
            metallic=0.0,
            roughness=0.30,
            coat=0.7,
            coat_roughness=0.07,
        )
    )
    # Slot 1 — the clear shell.
    #
    # Affordable now, and it was not before. When the wall was a packed rack of ~470 cases a
    # transparent surface per case meant hundreds of blended, depth-sorted draws, which is why
    # the previous pass deliberately removed the one that existed. A gallery of 150mm bays puts
    # two or three cases on screen, so the cost is nothing and the look is the whole point.
    #
    # Very low roughness with a full clearcoat over it: two speculars, a broad one off the
    # polystyrene and a tight one off the surface, which is what makes plastic read as *shiny*
    # rather than merely pale. Alpha is high enough to catch the light and low enough to read
    # the printed inlay straight through it.
    # Clear, and you can SEE THROUGH IT — which sounds obvious and was not.
    #
    # This was briefly KHR_materials_transmission at 0.92, on the reasoning that refraction
    # is what separates crystal from a pale film. It refracts beautifully and it hid the
    # album art completely: screen-space refraction samples the opaque colour buffer *behind*
    # the refracting surface, so it cannot reveal geometry enclosed *inside* the refractor,
    # which is exactly where a cover inlay sits. Physically the right model, practically a
    # case with no artwork on it.
    #
    # So: alpha blending, which does composite what is behind it, at a low enough alpha that
    # the cover reads cleanly through the shell.
    #
    # What makes it look like glass instead of cling film is no longer this material at all —
    # it is that the IBL now contains a small fierce lamp for the shell to reflect (see
    # RoomLighting.LAMP_HOT_LEVEL). Roughness 0.02 under a full clearcoat against a hot
    # source gives a hard, moving highlight; the same numbers against the old smooth gradient
    # gave nothing. The environment was the missing half, not the surface.
    #
    # **The fresnel was hiding the album art, which is the one thing the wall exists to show.**
    #
    # Reported as "cover art is not visible from sharp angles, and worse zoomed out", and it
    # was neither culling nor depth: on a blended surface the specular is not attenuated by
    # alpha, so as fresnel climbs toward 1 at grazing incidence the shell's own reflection
    # swamps whatever is behind it. Reflecting a dark room, that reads as a dark blue slab
    # where a cover should be — which is exactly what a case at the edge of the shelf looked
    # like. Zooming out makes it worse for the plainest possible reason: more of the wall is
    # off-axis, so more cases are being seen at the angles where this bites.
    #
    # Every number below is a retreat from physical accuracy, on purpose. Polystyrene really is
    # 1.59 with a hard clearcoat, and a real jewel case really does go to a mirror when you
    # tilt it — but a real jewel case is one you can pick up and tilt back. Here the wall turns
    # the cases for you, so half the presentation would be spent showing you a reflection of an
    # unlit room instead of the record. The shelf has to read first.
    #
    # - ior 1.59 -> 1.24 lowers F0 and, more usefully, flattens how fast fresnel climbs.
    # - coat 1.0 -> 0.28. The clearcoat is a second specular lobe with its own fresnel, fixed
    #   at 1.5, and it was doubling the effect the first one was already causing.
    # - roughness 0.02 -> 0.09 spreads the grazing reflection instead of mirroring the room
    #   into a single hard sheet.
    #
    # The highlight survives all three: RoomLighting's hot lamp is what makes this read as
    # glass (see the note above), and it still does — it is now a sheen across the cover rather
    # than a shutter over it.
    #
    # ---- Half of that retreat has now been given back, and the reason it was made is why ----
    #
    # Read the paragraph above again: the retreat was not about physics, it was about *what
    # there was to reflect*. "Half the presentation would be spent showing you a reflection of
    # an unlit room instead of the record" — a case tilted to a grazing angle mirrored a dark
    # room, and a dark mirror over album art is a dark blue slab where a cover should be.
    #
    # That premise expired. `RoomLighting.STRIP_BAND_LEVEL` puts the shelf strips into the
    # environment the cases reflect, so the grazing reflection is now a **warm line running
    # across the sleeve** — the exact thing that makes a rack of jewel cases in a lit shop read
    # as jewel cases, and the owner's word for what was missing was "cheap plastic". Reflecting
    # something is what separates polystyrene from painted card, and until the bands existed
    # there was nothing to reflect.
    #
    # So: back toward the real numbers, not all the way to them.
    #
    # - ior 1.24 -> 1.42. Polystyrene is 1.59; this keeps fresnel climbing more gently than
    #   life, because the wall turns the cases for you and a real case is one you can tilt back.
    # - coat 0.28 -> 0.55. The second lobe is what reads as a hard surface over a soft one.
    # - roughness 0.09 -> 0.055, so the reflected strip arrives as a line rather than a smear.
    #   A blurred highlight is the single most reliable tell of cheap CG plastic.
    #
    # Alpha is untouched at 0.16. It is the one number in here that was never about reflections
    # — it is how much of the printed inlay reads through the shell, and the cover art is what
    # the wall exists to show.
    obj.data.materials.append(
        principled(
            "JewelCaseClear",
            base_color=(0.90, 0.94, 1.0),
            metallic=0.0,
            roughness=0.055,
            coat=0.55,
            coat_roughness=0.03,
            alpha=0.16,
            blend=True,
            ior=1.42,
        )
    )
    return obj


# ---------------------------------------------------------------------------
# The underlight — the beam each album levitates in.
# ---------------------------------------------------------------------------

def build_beam():
    """
    A cone of light standing on the shelf, plus the pool it throws on the board.

    **Geometry, not a light.** Filament will not give every album a real spot: shadow-casting
    lights are the scarcest resource in the renderer, and even non-shadowing ones are capped
    well below the number of bays a wide library has. A cone of emissive, alpha-blended
    geometry costs one draw, looks the same from the front, and can be handed to as many
    slots as there are albums.

    Modelled standing at the origin so `CaseSlots` can drop it at a bay's base with no
    transform. Height runs along **Blender Z**, which the Y-up export turns into the app's
    +Y — building it along Blender Y instead produces a beam that hangs downwards through
    the shelf, which is exactly what the first attempt did.

    Vertex colours carry the fade: opaque-ish at the emitter, transparent at the top, so the
    beam dissolves into the room instead of ending in a hard rim. gltfio multiplies COLOR_0
    into base colour, so this needs no shader and no `.mat`.
    """
    obj = new_mesh_object("beam")
    bm = bmesh.new()

    r_base = 0.013
    segments = 28

    # **How wide the pool is, as a multiple of the emitter's own radius.**
    #
    # This has been both ways and the owner has now chosen. It was 2.6 — a 68mm disc — then
    # 0.85, on the reasoning written below that the big one was "a hard-edged, clipped, flat
    # ellipse, a glowing hockey puck under every record" whose bloom veiled the lower half of
    # every sleeve. That reasoning was sound at the time and is not sound now: back then this
    # sat under a real per-album LIGHT that was already washing the artwork, and the veil
    # landed on top of that. There is no such light any more — nothing per-album illuminates
    # anything — so the veil is the whole of what this contributes, and the owner sent a
    # screenshot of the 2.6 version with the words "everything is obvious".
    #
    # One number. If it turns out to be too much again, it is this one.
    POOL_SCALE = 2.6

    # NO SHAFT.
    #
    # There was a truncated cone here standing between the board and the album, and it never
    # worked. glTF has no additive blend mode, so a shaft can only ever be alpha-composited
    # over the scene — which means it *occludes* rather than adds, and reads as a solid
    # low-opacity object standing on the shelf rather than as light. Faint enough not to
    # occlude and it disappears; bright enough to see and it is a plastic cone.
    #
    # A real volumetric shaft wants additive blending, which wants a hand-written `.mat` and
    # the 735MB toolchain that entails (docs/materials.md). Until then the pool alone does the
    # job: it blooms, so it reads as a genuine source, and the eye supplies the rest.
    # The emitter, not the pool — and the change is the owner's complaint made geometric.
    #
    # This was r_base * 2.6: a 68mm disc of emission driven past bloom's threshold, standing
    # in for the pool of light an underlight throws. Two things went wrong with that once the
    # underlight became a real Filament light. It is a hard-edged, clipped, flat ellipse — a
    # glowing hockey puck under every record — and its bloom spread upward over the lower half
    # of every sleeve as a milky veil, which is most of why the covers "are not vivid enough".
    #
    # So it is the size of the LED a display puck actually has. Small enough that its bloom is
    # a glint rather than a haze, bright enough to read as the source the record is lit from.
    # The real light does the lighting; this only has to be seen.
    pool = []
    for i in range(segments):
        a = 2.0 * math.pi * i / segments
        pool.append(bm.verts.new((math.cos(a) * r_base * POOL_SCALE,
                                  math.sin(a) * r_base * POOL_SCALE, 0.0006)))
    bm.faces.new(tuple(pool))
    bm.normal_update()
    bm.to_mesh(obj.data)
    bm.free()

    # No bevel and no triangulate pass: this is a soft volume, and beveling it would only
    # round edges that are meant to be invisible.
    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)
    bpy.ops.object.shade_smooth()
    obj.select_set(False)

    # The pool where the beam meets the board, and the reason any of this glows.
    #
    # `RoomAtmosphere` runs bloom with `threshold = true`, which means **only values above
    # 1.0 bloom at all**. The first beam emitted at 0.3 and therefore could not glow no
    # matter how it was tuned — it was always going to sit there as a low-opacity cone.
    #
    # Splitting the beam in two is what lets both things be true at once: the shaft stays
    # faint so it does not occlude the album behind it (glTF has no additive blend, so a
    # bright surface hides what is behind it), while the pool is driven well past 1.0 and
    # blooms into an actual pool of light. Emission is not damped by alpha or vertex colour,
    # so it had to be a separate material rather than a gradient.
    pool_mat = principled(
        "UnderlightPool",
        base_color=(1.0, 0.824, 0.627),
        metallic=0.0,
        roughness=1.0,
        alpha=0.85,
        blend=True,
    )
    pb = pool_mat.node_tree.nodes["Principled BSDF"]
    pb.inputs["Emission Color"].default_value = (1.0, 0.86, 0.70, 1.0)
    # Exported as KHR_materials_emissive_strength. Above the bloom threshold on purpose.
    pb.inputs["Emission Strength"].default_value = 4.5
    obj.data.materials.append(pool_mat)
    return obj


# ---------------------------------------------------------------------------
# The props: the things a shop has that are not records.
# ---------------------------------------------------------------------------
#
# The last of the four kinds of decor the owner picked. Everything else in this
# room is a record, a fixture, or a surface; these are the objects that make it
# read as a place somebody works rather than a rack in a void.
#
# **These are SMALL objects, and the first attempt at them was not.**
#
# They were built at furniture size first — a 420mm deck, a 580mm plant — on the
# argument that a turntable being nearly three CD cases wide is what makes the
# rack read as furniture rather than as a texture. That argument is true about
# the objects and false about the room, and the owner's screenshot settled it in
# one look: this camera frames about half a metre of wall, so a real plant
# standing 300mm in front of the records fills the entire frame and the rack
# disappears behind it. His own words for what he picked were "small objects",
# which is what these are now — a portable deck, a desk plant, a small clock.
#
# The lesson generalises past the props: **the yardstick in this room is not the
# real world, it is the frame.** Half a metre of it, at CD scale.


def build_turntable():
    """
    A portable deck: plinth, platter, slipmat, arm. **One object per material.**

    It was a single mesh wearing one dark plastic, so the platter, the arm and the feet were
    all the same non-colour and the thing read as a moulded toy. A turntable is the opposite
    of one material — a plastic box, a spun aluminium platter, a felt mat and a chromed arm,
    which is four different ways of catching the same light and is most of why a real one looks
    like machinery.

    Split rather than face-assigned because the exporter and `finish()` both work per object,
    and because `build_disc_shards` already establishes the pattern in this file: hand `export`
    a list and it writes them into one glb.
    """
    plinth = new_mesh_object("deck_plinth")
    bm = bmesh.new()

    plinth_w, plinth_h, plinth_d = 0.235, 0.042, 0.185
    foot_h = 0.010
    for sx in (-1, 1):
        for sz in (-1, 1):
            cylinder(
                bm,
                sx * (plinth_w / 2 - 0.020), az(sz * (plinth_d / 2 - 0.020)),
                foot_h / 2, 0.009, foot_h, segments=12, axis="Z",
            )
    box(bm, 0.0, az(0.0), foot_h + plinth_h / 2, plinth_w, plinth_d, plinth_h)
    bm.normal_update()
    bm.to_mesh(plinth.data)
    bm.free()
    # Moulded plastic with a polished skin: the coat is the separate specular lobe that skin
    # actually is, and it is what stops a black box reading as a black hole.
    plinth.data.materials.append(principled(
        "DeckPlinth", base_color=(0.032, 0.030, 0.029), metallic=0.0,
        roughness=0.40, coat=0.45, coat_roughness=0.08,
    ))
    finish(plinth, bevel=True, bevel_width=0.0012)

    platter_y = foot_h + plinth_h

    # The platter: spun aluminium, and anisotropic because a spun disc's highlight stretches
    # around it rather than sitting still. The disc model already uses this for the same
    # reason, and it is the one thing that says "turned on a lathe" at this size.
    platter = new_mesh_object("deck_platter")
    bm = bmesh.new()
    cylinder(bm, -0.030, az(0.011), platter_y + 0.007, 0.086, 0.014, segments=48, axis="Z")
    cylinder(bm, -0.030, az(0.011), platter_y + 0.019, 0.0022, 0.022, segments=10, axis="Z")
    bm.normal_update()
    bm.to_mesh(platter.data)
    bm.free()
    platter.data.materials.append(principled(
        "DeckPlatter", base_color=(0.58, 0.585, 0.60), metallic=1.0,
        roughness=0.30, anisotropic=0.5,
    ))
    finish(platter, bevel=True, bevel_width=0.0008)

    # The slipmat. Felt: the one genuinely matt thing on the whole prop, which is what gives
    # the platter's metal something to be shiny against.
    mat = new_mesh_object("deck_mat")
    bm = bmesh.new()
    cylinder(bm, -0.030, az(0.011), platter_y + 0.0155, 0.080, 0.0016, segments=48, axis="Z")
    bm.normal_update()
    bm.to_mesh(mat.data)
    bm.free()
    mat.data.materials.append(principled(
        "DeckMat", base_color=(0.022, 0.021, 0.020), metallic=0.0, roughness=0.96,
    ))
    finish(mat, bevel=False)

    # The arm: base, tube, counterweight, in chrome.
    arm = new_mesh_object("deck_arm")
    bm = bmesh.new()
    arm_x, arm_z = 0.086, -0.062
    cylinder(bm, arm_x, az(arm_z), platter_y + 0.010, 0.015, 0.020, segments=16, axis="Z")
    tube = bmesh.new()
    bmesh.ops.create_cone(
        tube, cap_ends=True, cap_tris=False, segments=10,
        radius1=0.0035, radius2=0.0035, depth=0.112,
        matrix=Matrix.Translation((0.0, 0.0, 0.0)),
    )
    bmesh.ops.rotate(
        tube, verts=tube.verts, cent=(0, 0, 0),
        matrix=Matrix.Rotation(math.radians(90.0), 3, "X"),
    )
    bmesh.ops.rotate(
        tube, verts=tube.verts, cent=(0, 0, 0),
        matrix=Matrix.Rotation(math.radians(34.0), 3, "Z"),
    )
    bmesh.ops.translate(
        tube, verts=tube.verts,
        vec=(arm_x - 0.042, az(arm_z + 0.031), platter_y + 0.026),
    )
    mesh_add(bm, tube)
    cylinder(
        bm, arm_x + 0.029, az(arm_z - 0.021), platter_y + 0.026,
        0.010, 0.017, segments=14, axis="Z",
    )
    bm.normal_update()
    bm.to_mesh(arm.data)
    bm.free()
    arm.data.materials.append(principled(
        "DeckArm", base_color=(0.72, 0.73, 0.75), metallic=1.0, roughness=0.16,
    ))
    finish(arm, bevel=True, bevel_width=0.0006)

    return [plinth, platter, mat, arm]


def build_clock():
    """
    A shop clock for the brick, modelled facing the room (+Z), case and face separate.

    Hands at ten past ten, which is where every clock in every photograph of a clock is: it
    frames the face, it is unmistakably A CLOCK at a glance, and it cannot be read as an
    accidental time. Nothing in this app tells the prop what time it is, and one quietly
    showing the wrong time forever would be worse than one showing a time nobody expects to
    be right.
    """
    case = new_mesh_object("clock_case")
    bm = bmesh.new()
    radius = 0.088
    cylinder(bm, 0.0, az(0.0), 0.0, radius, 0.024, segments=48, axis="Y")
    bm.normal_update()
    bm.to_mesh(case.data)
    bm.free()
    case.data.materials.append(principled(
        "ClockCase", base_color=(0.045, 0.042, 0.039), metallic=0.0,
        roughness=0.45, coat=0.30,
    ))
    finish(case, bevel=True, bevel_width=0.0010)

    # The face, and the hands on it. Off-white enamel: the one pale thing in the room's upper
    # half, which is what lets a small prop read from across the rack.
    face = new_mesh_object("clock_face")
    bm = bmesh.new()
    cylinder(bm, 0.0, az(0.013), 0.0, radius - 0.008, 0.004, segments=48, axis="Y")
    for length, width, degrees in ((0.054, 0.006, -60.0), (0.068, 0.004, 60.0)):
        hand = bmesh.new()
        bmesh.ops.create_cube(hand, size=1.0, matrix=(
            Matrix.Diagonal((width, 0.003, length, 1.0))
        ))
        bmesh.ops.translate(hand, verts=hand.verts, vec=(0.0, 0.0, length / 2 - 0.005))
        bmesh.ops.rotate(
            hand, verts=hand.verts, cent=(0, 0, 0),
            matrix=Matrix.Rotation(math.radians(degrees), 3, "Y"),
        )
        bmesh.ops.translate(hand, verts=hand.verts, vec=(0.0, az(0.016), 0.0))
        mesh_add(bm, hand)
    cylinder(bm, 0.0, az(0.018), 0.0, 0.006, 0.005, segments=16, axis="Y")
    bm.normal_update()
    bm.to_mesh(face.data)
    bm.free()
    face.data.materials.append(principled(
        "ClockFace", base_color=(0.78, 0.74, 0.66), metallic=0.0, roughness=0.55,
    ))
    finish(face, bevel=False)

    return [case, face]


def build_plant():
    """
    A snake plant in a terracotta pot. **Two objects, because a plant is not one colour.**

    It was one mesh with one material, so the blades came out the same brown as the pot — a
    brown plant, which is a dead one. Sansevieria on purpose: it is the one houseplant whose
    real shape is a handful of flat tapered upright blades, a shape primitives can make. A
    fern modelled this way is a green blob, and a green blob in the corner of a photographic
    room is worse than an empty corner.
    """
    pot = new_mesh_object("plant_pot")
    bm = bmesh.new()
    bmesh.ops.create_cone(
        bm, cap_ends=True, cap_tris=False, segments=28,
        radius1=0.040, radius2=0.053, depth=0.105,
        matrix=Matrix.Translation((0.0, 0.0, 0.0525)) @ Matrix.Identity(4),
    )
    cylinder(bm, 0.0, az(0.0), 0.104, 0.055, 0.008, segments=28, axis="Z")
    bm.normal_update()
    bm.to_mesh(pot.data)
    bm.free()
    # Unglazed terracotta: warm, matt, and slightly porous. The only genuinely red thing in
    # the room, which is why it reads as an object rather than as more furniture.
    pot.data.materials.append(principled(
        "PlantPot", base_color=(0.30, 0.125, 0.072), metallic=0.0, roughness=0.88,
    ))
    finish(pot, bevel=True, bevel_width=0.0012)

    leaves = new_mesh_object("plant_leaves")
    bm = bmesh.new()
    blades = (
        (0.20, 0.0, 7.0), (0.17, 62.0, -9.0), (0.23, 128.0, 5.0),
        (0.14, 196.0, -12.0), (0.19, 250.0, 10.0), (0.115, 305.0, -6.0),
        (0.16, 340.0, 8.0),
    )
    for length, around, lean in blades:
        blade = bmesh.new()
        bmesh.ops.create_cone(
            blade, cap_ends=True, cap_tris=False, segments=4,
            radius1=0.016, radius2=0.0025, depth=length,
            matrix=Matrix.Translation((0.0, 0.0, length / 2)),
        )
        bmesh.ops.scale(blade, verts=blade.verts, vec=(0.22, 1.0, 1.0))
        bmesh.ops.rotate(
            blade, verts=blade.verts, cent=(0, 0, 0),
            matrix=Matrix.Rotation(math.radians(lean), 3, "Y"),
        )
        bmesh.ops.rotate(
            blade, verts=blade.verts, cent=(0, 0, 0),
            matrix=Matrix.Rotation(math.radians(around), 3, "Z"),
        )
        bmesh.ops.translate(blade, verts=blade.verts, vec=(0.0, 0.0, 0.098))
        mesh_add(bm, blade)
    bm.normal_update()
    bm.to_mesh(leaves.data)
    bm.free()
    # A leaf is not a painted surface: it has a waxy skin over a dull body, which is what the
    # coat is for. Dark enough to sit in a dim room without glowing, green enough to be a plant.
    leaves.data.materials.append(principled(
        "PlantLeaf", base_color=(0.085, 0.175, 0.075), metallic=0.0,
        roughness=0.62, coat=0.25, coat_roughness=0.22,
    ))
    finish(leaves, bevel=False)

    return [pot, leaves]


# ---------------------------------------------------------------------------
# The pocket player — the iPod-mode device.
# ---------------------------------------------------------------------------

# Its own design, deliberately. The interaction is the iPod's, faithfully (see
# docs/formats.md §4); the object is not. Different proportions, no wordmark, no
# Apple marks, and a face of its own — because copying the product's appearance is
# a legal question, and copying its *feel* is not.
#
# Real dimensions, because this is shown at LIFE SIZE filling a phone screen — at
# that distance every tolerance is visible, and a millimetre of error reads as a toy.
# **The classic's envelope, on the owner's call** ("closer to the original
# proportions"). It was 56 x 96 x 8.6 — its own size — and that read as a smaller,
# thinner object than the thing being simulated. Dimensions are not what makes a
# product's appearance its own; the marks and the face are, and this still carries
# none of them.
POCKET_W = 0.0618       # across
POCKET_H = 0.1035       # tall
POCKET_T = 0.0105       # thick
POCKET_R = 0.0065       # corner radius
POCKET_FRONT_T = 0.0011  # the anodised front plate
# **The bead**: the back shell's polished steel brought round the edge to meet the face, as a
# quarter-round this wide. It is what draws the device's outline. A flat side and a flat face
# each reflect one direction of the room, so against a dark carriage the silhouette dissolved —
# the phone's screenshots lost the bottom corners into the background entirely. A rounded edge
# faces every way between the two, so some part of it always finds a light: the ceiling along
# the top, the reading lamp down the right, the window down the left.
POCKET_RIM = 0.0008
POCKET_RIM_SEGMENTS = 8
# The hairline where the plate meets the bead: two parts, not one surface.
POCKET_RIM_SEAM = 0.00005

POCKET_SCREEN_W = 0.0455
POCKET_SCREEN_H = 0.0341
POCKET_SCREEN_Y = 0.0255     # centre of the window, above the device's middle
POCKET_SCREEN_R = 0.0016
# How far the LCD panel sits behind the front surface. **It must stay above the back
# shell's top face**, which is solid and rises to the underside of the front plate
# (front_z - POCKET_FRONT_T): at 1.6mm the panel was *inside* the shell, and what showed
# through the window was the shell's mirror-polished top — a streak of reflected lamp that
# read, in both Cycles and Filament, as a dark screen under glass. 1.0mm puts it 0.1mm
# above that face, with the live screen and then the glass above it.
POCKET_SCREEN_SINK = 0.0010

POCKET_WHEEL_Y = -0.0225
POCKET_WHEEL_R = 0.0195      # 39mm across, which is a thumb's reach
POCKET_WHEEL_GAP = 0.0004    # the hairline the wheel turns in
POCKET_WHEEL_SINK = 0.0006   # recessed, so a thumb finds it without looking
POCKET_BUTTON_R = 0.00825
POCKET_BUTTON_GAP = 0.0003


def rounded_rect(bm, w, h, r, y, cz=0.0, arc=8):
    """
    A closed rounded-rectangle edge loop in the XZ plane at Blender `y`.

    Returns its verts in order. Used instead of a box wherever an edge is one a
    thumb would feel: a 6mm corner radius is the difference between a moulded
    object and a cuboid, and the bevel modifier cannot make one that large
    without eating the faces either side of it.
    """
    hw, hh = w / 2.0 - r, h / 2.0 - r
    pts = []
    for cx, cy, a0 in (
        (hw, hh, 0.0), (-hw, hh, 90.0), (-hw, -hh, 180.0), (hw, -hh, 270.0),
    ):
        for i in range(arc + 1):
            a = math.radians(a0 + 90.0 * i / arc)
            pts.append((cx + r * math.cos(a), cz + cy + r * math.sin(a)))
    # The arcs share their end points with the next arc's start; drop the repeats.
    verts = []
    for x, z in pts:
        if verts:
            px, pz = verts[-1].co.x, verts[-1].co.z
            if abs(px - x) < 1e-9 and abs(pz - z) < 1e-9:
                continue
        verts.append(bm.verts.new((x, y, z)))
    edges = [bm.edges.new((verts[i], verts[(i + 1) % len(verts)]))
             for i in range(len(verts))]
    return verts, edges


def circle_loop(bm, radius, y, cz, segments=96):
    """A closed circular edge loop in the XZ plane — the wheel's hole and its rim."""
    verts = [bm.verts.new((radius * math.cos(2.0 * math.pi * i / segments), y,
                           cz + radius * math.sin(2.0 * math.pi * i / segments)))
             for i in range(segments)]
    edges = [bm.edges.new((verts[i], verts[(i + 1) % segments]))
             for i in range(segments)]
    return verts, edges


# ---------------------------------------------------------------------------
# The pocket player's wear: roughness maps, generated.
#
# A surface that is perfectly even is the surest sign a thing was rendered. A real
# player that has been carried has fingerprints on its screen, a ring polished into
# its wheel where the thumb goes round, and anodising that is not quite the same
# everywhere — and none of it shows until light moves across it, which is what the
# room's passing lamps do. So each is a roughness map, not a colour: invisible when
# still, there the moment a glint crosses it.
#
# Written as glTF wants a metallic-roughness texture — roughness in green, metallic
# in blue — and wired through a Separate Color node, which the exporter recognises.
# Generated with numpy from fixed seeds, so the build stays reproducible.
# ---------------------------------------------------------------------------

def _np():
    import numpy
    return numpy


def _smooth_noise(h, w, cells_x, cells_y, rng, wrap_x=False):
    """Value noise: a random grid, smoothly interpolated up to h x w. [wrap_x] makes it periodic across."""
    np = _np()
    grid = rng.random((cells_y + 1, cells_x + 1))
    if wrap_x:
        grid[:, -1] = grid[:, 0]
    ys = np.linspace(0.0, cells_y, h, endpoint=False)
    xs = np.linspace(0.0, cells_x, w, endpoint=False)
    y0 = ys.astype(int)
    x0 = xs.astype(int)
    ty = ys - y0
    tx = xs - x0
    ty = ty * ty * (3.0 - 2.0 * ty)
    tx = tx * tx * (3.0 - 2.0 * tx)
    a = grid[y0][:, x0]
    b = grid[y0][:, x0 + 1]
    c = grid[y0 + 1][:, x0]
    d = grid[y0 + 1][:, x0 + 1]
    top = a + (b - a) * tx[None, :]
    bottom = c + (d - c) * tx[None, :]
    return top + (bottom - top) * ty[:, None]


def _fbm(h, w, base_cells, octaves, rng, aspect=1.0):
    """Octaves of value noise, 0..1."""
    np = _np()
    out = np.zeros((h, w))
    amp = 1.0
    total = 0.0
    cells = base_cells
    for _ in range(octaves):
        out += amp * _smooth_noise(h, w, max(1, int(cells * aspect)), max(1, cells), rng)
        total += amp
        amp *= 0.5
        cells *= 2
    return out / total


def _scratches(field, count, length_px, strength, rng, angle=None, width_px=0.8):
    """Thin straight scratches, anti-aliased, added into [field]."""
    np = _np()
    h, w = field.shape
    for _ in range(count):
        cx, cy = rng.random() * w, rng.random() * h
        a = angle if angle is not None else rng.random() * math.pi
        a += (rng.random() - 0.5) * 0.3
        half = length_px * (0.4 + 0.6 * rng.random()) / 2.0
        dx, dy = math.cos(a), math.sin(a)
        x0, x1 = int(max(0, cx - abs(dx) * half - 3)), int(min(w, cx + abs(dx) * half + 4))
        y0, y1 = int(max(0, cy - abs(dy) * half - 3)), int(min(h, cy + abs(dy) * half + 4))
        if x1 <= x0 or y1 <= y0:
            continue
        yy, xx = np.mgrid[y0:y1, x0:x1]
        px, py = xx - cx, yy - cy
        along = np.clip(px * dx + py * dy, -half, half)
        dist = np.hypot(px - along * dx, py - along * dy)
        line = np.clip(1.0 - dist / width_px, 0.0, 1.0)
        field[y0:y1, x0:x1] += line * strength * (0.5 + 0.5 * rng.random())


def _metal_rough_image(name, roughness, metallic):
    """A packed image: roughness in G, metallic in B, as glTF's metallicRoughness wants."""
    np = _np()
    h, w = roughness.shape
    rgba = np.zeros((h, w, 4), dtype=np.float32)
    rgba[..., 0] = 1.0
    rgba[..., 1] = np.clip(roughness, 0.0, 1.0)
    rgba[..., 2] = metallic
    rgba[..., 3] = 1.0
    img = bpy.data.images.new(name, width=w, height=h, alpha=False)
    img.colorspace_settings.name = "Non-Color"
    # Blender's pixel buffer runs bottom row first, the same way the arrays here are built.
    img.pixels.foreach_set(rgba.ravel())
    img.pack()
    return img


def _use_metal_rough(mat, image):
    """Wire [image] to Roughness (G) and Metallic (B) through a Separate Color node."""
    nt = mat.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = image
    sep = nt.nodes.new("ShaderNodeSeparateColor")
    nt.links.new(tex.outputs["Color"], sep.inputs["Color"])
    nt.links.new(sep.outputs["Green"], bsdf.inputs["Roughness"])
    nt.links.new(sep.outputs["Blue"], bsdf.inputs["Metallic"])


def _planar_uvs(obj, x0, x1, z0, z1):
    """UVs straight off the device's face (Blender XZ), normalised over a rectangle."""
    mesh = obj.data
    if not mesh.uv_layers:
        mesh.uv_layers.new(name="UVMap")
    uv = mesh.uv_layers.active.data
    for poly in mesh.polygons:
        for li in poly.loop_indices:
            v = mesh.vertices[mesh.loops[li].vertex_index].co
            uv[li].uv = ((v.x - x0) / (x1 - x0), (v.z - z0) / (z1 - z0))


def pocket_glass_wear(w=512, h=384, seed=11, width_m=None):
    """
    The screen's cover as it is after a week in a pocket: a faint haze, wiped
    unevenly; two fingerprints, one partial; and a few hairline scratches. Base
    roughness 0.03 — what the glass is when clean — rising to ~0.2 on a ridge.

    [width_m] is the glass's width, so a fingerprint is a finger's size on any cover:
    the device's by default, the face's (`build_pocket_face`) when it says.
    """
    np = _np()
    rng = np.random.default_rng(seed)
    mm_per_px = (width_m or POCKET_SCREEN_W) * 1000.0 / w
    rough = np.full((h, w), 0.03)
    haze = _fbm(h, w, 3, 4, rng, aspect=w / h)
    rough += np.clip(haze - 0.48, 0.0, 1.0) * 0.10
    yy, xx = np.mgrid[0:h, 0:w]
    # Fingerprints. What a print on glass mostly is, is *oil*: a hazy oval where a
    # pad pressed, rougher than clean glass. The ridges are a faint texture inside
    # that haze, ~0.45mm apart, and they wander — the first version drew them as
    # perfect concentric rings and the map read as a target. Warping the ridge field
    # hard, with noise at about the pad's own scale, is what bends rings into a whorl.
    for (fx, fy, rx_mm, ry_mm, turn, cover) in (
        (0.70, 0.36, 7.0, 9.0, 0.5, 0.75),
        (0.28, 0.66, 6.0, 7.5, -0.3, 0.45),
    ):
        cx, cy = fx * w, fy * h
        rx, ry = rx_mm / mm_per_px, ry_mm / mm_per_px
        c, s = math.cos(turn), math.sin(turn)
        u = ((xx - cx) * c + (yy - cy) * s) / rx
        v = (-(xx - cx) * s + (yy - cy) * c) / ry
        pad = np.clip((1.0 - np.hypot(u, v)) / 0.4, 0.0, 1.0)
        # Where the finger did not quite press: a print is nearly always partial.
        broken = np.clip((_fbm(h, w, 6, 3, rng, aspect=w / h) - (1.0 - cover)) * 3.0, 0.0, 1.0)
        # The warp: about a quarter of the pad's radius, at a little under its scale —
        # several ridges' worth, slowly. Twice as fine and twice as strong turned the
        # print into a maze.
        wu = (_fbm(h, w, 5, 2, rng, aspect=w / h) - 0.5) * 0.5
        wv = (_fbm(h, w, 5, 2, rng, aspect=w / h) - 0.5) * 0.5
        # A loop rather than a bullseye: squash the field on one side of the core.
        uu = u + wu + 0.25 * np.clip(v, 0.0, None) ** 2
        vv = v + wv
        r_mm = np.hypot(uu * rx_mm, vv * ry_mm)
        ridges = 0.5 + 0.5 * np.sin(2.0 * math.pi * r_mm / 0.45)
        rough += pad * broken * (0.045 + 0.06 * ridges)
    _scratches(rough, 7, 14.0 / mm_per_px, 0.10, rng)
    return rough


def pocket_front_wear(w=512, h=860, seed=12):
    """
    Anodised aluminium is never one roughness: a slow mottle from the bath, a fine
    grain from the blasting, and the edges a touch smoother where hands have been.
    """
    np = _np()
    rng = np.random.default_rng(seed)
    rough = 0.34 + (_fbm(h, w, 4, 3, rng, aspect=w / h) - 0.5) * 0.05
    rough += (rng.random((h, w)) - 0.5) * 0.018
    yy, xx = np.mgrid[0:h, 0:w]
    edge = np.minimum(np.minimum(xx, w - 1 - xx) / w, np.minimum(yy, h - 1 - yy) / h * (h / w))
    rough -= np.clip(1.0 - edge / 0.05, 0.0, 1.0) * 0.05
    _scratches(rough, 5, 60.0, -0.06, rng, width_px=1.1)
    return rough


def pocket_wheel_wear(size=512, seed=13, radius_m=None):
    """
    The ring polished by a thumb going round it: a band a few millimetres wide at
    the thumb's radius, smoother by a good margin, with the fine circular marks a
    thumb leaves — the one piece of wear that says how the thing is used.

    [radius_m] is the wheel's radius: the device's by default, the face's bigger one
    (`build_pocket_face`) when it says — and the thumb's band moves out with it.
    """
    np = _np()
    rng = np.random.default_rng(seed)
    wheel_r = radius_m or POCKET_WHEEL_R
    mm_per_px = wheel_r * 2000.0 / size
    yy, xx = np.mgrid[0:size, 0:size]
    cx = cy = (size - 1) / 2.0
    r_mm = np.hypot(xx - cx, yy - cy) * mm_per_px
    theta = np.arctan2(yy - cy, xx - cx)
    band = np.exp(-((r_mm - 14.2 * wheel_r / POCKET_WHEEL_R) / 3.2) ** 2)
    rough = np.full((size, size), 0.54)
    rough -= band * 0.15
    # Circular marks: noise stretched round the ring, not across it — and periodic round it,
    # or the marks would stop dead in a seam at nine o'clock.
    streaks = _smooth_noise(size, size, 3, 120, rng, wrap_x=True)
    ang = ((theta + math.pi) / (2.0 * math.pi) * (size - 1)).astype(int)
    rad = np.clip((r_mm / (wheel_r * 1000.0)) * (size - 1), 0, size - 1).astype(int)
    rough += (streaks[rad, ang] - 0.5) * 0.08 * band
    rough += (_fbm(size, size, 6, 3, rng) - 0.5) * 0.03
    return rough


def build_pocket():
    """
    Eight objects, one per material: front plate, back shell, screen panel, its
    glass, the wheel, its centre button, the print on the wheel, and the dark
    details on the edges.

    **The front plate is a face with two holes, filled and then extruded** — not a
    slab with holes cut out of it. `docs/models.md` records what booleans do to
    millimetre features here, and the plate is 1.1mm thick. Filling a planar
    outline that already has its holes in it cannot leave a sliver behind.

    Everything a thumb touches is recessed or proud by a real amount: the wheel
    sits 0.6mm down with a 0.4mm hairline around it, the button 0.3mm inside the
    wheel, the glass flush with the plate. Those numbers are what the eye reads as
    "assembled" rather than "modelled".
    """
    front_z = POCKET_T / 2.0
    objects = []

    # ---- the front plate ---------------------------------------------------
    front = new_mesh_object("pocket_front")
    bm = bmesh.new()
    # Inside the bead, with the seam's hairline between them.
    plate_inset = POCKET_RIM + POCKET_RIM_SEAM
    _, outer_e = rounded_rect(bm, POCKET_W - 2.0 * plate_inset, POCKET_H - 2.0 * plate_inset,
                              POCKET_R - plate_inset, az(front_z))
    _, screen_e = rounded_rect(bm, POCKET_SCREEN_W, POCKET_SCREEN_H,
                               POCKET_SCREEN_R, az(front_z), cz=POCKET_SCREEN_Y)
    _, wheel_e = circle_loop(bm, POCKET_WHEEL_R + POCKET_WHEEL_GAP,
                             az(front_z), POCKET_WHEEL_Y)
    bmesh.ops.triangle_fill(bm, use_beauty=True, use_dissolve=False,
                            edges=outer_e + screen_e + wheel_e)
    faces = [f for f in bm.faces]
    # Extrude the whole plate backwards in one go: the hole rims come with it and
    # become the window's and the wheel recess's walls, which is where a real
    # plate's thickness shows.
    ex = bmesh.ops.extrude_face_region(bm, geom=faces)
    moved = [v for v in ex["geom"] if isinstance(v, bmesh.types.BMVert)]
    bmesh.ops.translate(bm, verts=moved, vec=(0.0, -az(POCKET_FRONT_T), 0.0))
    bm.to_mesh(front.data)
    bm.free()
    front_mat = principled(
        # Bead-blasted anodised aluminium: a metal wearing an oxide skin, which is
        # why it has a coat as well as a middling roughness. Not paint.
        "PocketFront", base_color=(0.205, 0.200, 0.196), metallic=1.0,
        roughness=0.34, coat=0.12, coat_roughness=0.26, anisotropic=0.15,
    )
    _planar_uvs(front, -POCKET_W / 2.0, POCKET_W / 2.0, -POCKET_H / 2.0, POCKET_H / 2.0)
    _use_metal_rough(front_mat, _metal_rough_image("pocket_front_mr", pocket_front_wear(), 1.0))
    front.data.materials.append(front_mat)
    finish(front, bevel_width=0.00018, harden=True)
    objects.append(front)

    # ---- the back shell, and the bead it brings round the edge ---------------
    back = new_mesh_object("pocket_back")
    bm = bmesh.new()
    back_inner_z = front_z - POCKET_FRONT_T
    # One profile, lofted: from the floor under the plate up the seam's wall, over the bead
    # and down the side to the back — (inset from the outline, app Z) per ring. Every ring is
    # a rounded rect with the same vertex count, so each bridges square to the next.
    rim = POCKET_RIM
    profile = [(rim, back_inner_z), (rim, front_z)]
    for i in range(1, POCKET_RIM_SEGMENTS + 1):
        t = (math.pi / 2.0) * i / POCKET_RIM_SEGMENTS
        profile.append((rim * (1.0 - math.sin(t)), front_z - rim * (1.0 - math.cos(t))))
    profile.append((0.0, -POCKET_T / 2.0))
    rings = []
    for inset, z in profile:
        _, e = rounded_rect(bm, POCKET_W - 2.0 * inset, POCKET_H - 2.0 * inset,
                            POCKET_R - inset, az(z))
        rings.append(e)
    for lower, upper in zip(rings, rings[1:]):
        bmesh.ops.bridge_loops(bm, edges=lower + upper)
    bmesh.ops.contextual_create(bm, geom=rings[0])
    bmesh.ops.contextual_create(bm, geom=rings[-1])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(back.data)
    bm.free()
    back.data.materials.append(principled(
        # Mirror-polished stainless. The most demanding surface in the room: it is
        # almost entirely a reflection of whatever is around it, which is why the
        # room's moving lights matter more here than any map.
        "PocketBack", base_color=(0.560, 0.570, 0.580), metallic=1.0,
        roughness=0.055, anisotropic=0.08,
    ))
    # A small bevel for the seam's lip and the back edge; the bead is round already, and its
    # 11° steps sit well inside the smoothing angle.
    finish(back, bevel_width=0.0001)
    objects.append(back)

    # ---- the screen: panel, then glass over it -----------------------------
    panel = new_mesh_object("pocket_screen")
    bm = bmesh.new()
    # The LCD's black surround, filling the window to within a hair of its walls. The live
    # picture is the app's own plane (LcdPanel), 0.6mm inside this all round — an LCD's active
    # area inside its mask. It used to be the picture's own size, which left a 0.6mm gap round
    # it down to the shell's polished floor: a pale band inside the window that Cover Flow's
    # black showed plainly and no real screen has.
    clear = 0.00002
    _, e = rounded_rect(bm, POCKET_SCREEN_W - 2.0 * clear, POCKET_SCREEN_H - 2.0 * clear,
                        POCKET_SCREEN_R - clear, az(front_z - POCKET_SCREEN_SINK),
                        cz=POCKET_SCREEN_Y)
    bmesh.ops.contextual_create(bm, geom=e)
    bm.to_mesh(panel.data)
    bm.free()
    # Near-black and matt: a mask, not a picture. (It carried a white placeholder texture from
    # when the app bound the screen to this material; nothing has since, and white is what the
    # margin would have shown.)
    panel.data.materials.append(principled(
        "PocketScreen", base_color=(0.012, 0.012, 0.013), metallic=0.0,
        roughness=0.55,
    ))
    finish(panel, bevel=False)
    objects.append(panel)

    glass = new_mesh_object("pocket_glass")
    bm = bmesh.new()
    _, e = rounded_rect(bm, POCKET_SCREEN_W, POCKET_SCREEN_H, POCKET_SCREEN_R,
                        az(front_z - 0.00005), cz=POCKET_SCREEN_Y)
    bmesh.ops.contextual_create(bm, geom=e)
    ex = bmesh.ops.extrude_face_region(bm, geom=[f for f in bm.faces])
    moved = [v for v in ex["geom"] if isinstance(v, bmesh.types.BMVert)]
    bmesh.ops.translate(bm, verts=moved, vec=(0.0, -az(0.0004), 0.0))
    bm.to_mesh(glass.data)
    bm.free()
    glass.data.materials.append(principled(
        # Flush with the plate, 0.4mm thick, and the only transmissive thing on the
        # device. It is what puts the room on top of the picture.
        #
        # **Blended, not transmissive — and both earlier versions were wrong on a device.**
        #
        # With a clear coat, gltfio's ubershader dropped the transmission outright ("Volume,
        # transmission, sheen and IOR are not supported in ubershader mode for clearcoat
        # materials"). Without it, transmission worked exactly as Filament implements it:
        # screen-space refraction of the *opaque* pass — and the live screen is a transparent
        # ViewNode, so the glass faithfully refracted the dark panel underneath and painted
        # that over the picture. Hiding the glass is what showed it.
        #
        # A flat 0.4mm cover bends almost nothing, so refraction was never buying anything a
        # person could see. What a screen cover *does* do is reflect, and a blended surface at
        # a few percent alpha keeps its full specular (Filament's `transparent` blending) while
        # sorting after the screen, because it is nearer the lens.
        "PocketGlass", base_color=(0.015, 0.016, 0.018), metallic=0.0,
        roughness=0.03, alpha=0.06, blend=True,
    ))
    _planar_uvs(glass, -POCKET_SCREEN_W / 2.0, POCKET_SCREEN_W / 2.0,
                POCKET_SCREEN_Y - POCKET_SCREEN_H / 2.0, POCKET_SCREEN_Y + POCKET_SCREEN_H / 2.0)
    _use_metal_rough(glass.data.materials[0], _metal_rough_image("pocket_glass_mr", pocket_glass_wear(), 0.0))
    finish(glass, bevel_width=0.00008, harden=True)
    objects.append(glass)

    # ---- the wheel and its button ------------------------------------------
    wheel = new_mesh_object("pocket_wheel")
    bm = bmesh.new()
    wheel_z = front_z - POCKET_WHEEL_SINK
    outer = bmesh.ops.create_circle(
        bm, cap_ends=False, segments=96, radius=POCKET_WHEEL_R,
        matrix=(Matrix.Translation((0.0, az(wheel_z), POCKET_WHEEL_Y))
                @ Matrix.Rotation(math.radians(90.0), 4, "X")))
    inner = bmesh.ops.create_circle(
        bm, cap_ends=False, segments=96, radius=POCKET_BUTTON_R + POCKET_BUTTON_GAP,
        matrix=(Matrix.Translation((0.0, az(wheel_z), POCKET_WHEEL_Y))
                @ Matrix.Rotation(math.radians(90.0), 4, "X")))
    bmesh.ops.bridge_loops(bm, edges=[e for e in bm.edges])
    bm.to_mesh(wheel.data)
    bm.free()
    solid = wheel.modifiers.new(name="Thickness", type="SOLIDIFY")
    solid.thickness = 0.0013
    # **Backwards, into the recess.** offset = +1 thickened it toward the camera and
    # stood the wheel 0.7mm *proud* of a plate it is supposed to sit 0.6mm inside —
    # which the exported bounds caught (app Z 3.70..5.00 against the plate's 4.30)
    # long before a render would have.
    solid.offset = -1.0
    wheel.data.materials.append(principled(
        # Matt polycarbonate with a polished skin — the one surface here that is
        # not metal, so it is what the metal is shiny *against*.
        #
        # **Dark, close to the body** (owner, 2026-09-22). At 0.145 it read as a
        # white wheel on a graphite device; a moulding a shade under the anodising
        # is what a black one actually is, and it lets the hairline gap and the
        # button's step do the work instead of a colour change.
        "PocketWheel", base_color=(0.072, 0.071, 0.070), metallic=0.0,
        roughness=0.54, coat=0.28, coat_roughness=0.30,
    ))
    _planar_uvs(wheel, -POCKET_WHEEL_R, POCKET_WHEEL_R,
                POCKET_WHEEL_Y - POCKET_WHEEL_R, POCKET_WHEEL_Y + POCKET_WHEEL_R)
    _use_metal_rough(wheel.data.materials[0], _metal_rough_image("pocket_wheel_mr", pocket_wheel_wear(), 0.0))
    finish(wheel, bevel_width=0.00010, harden=True)
    objects.append(wheel)

    button = new_mesh_object("pocket_button")
    bm = bmesh.new()
    # 0.05mm proud of the wheel it sits in: enough for a thumb to find the edge of
    # it without looking, which is the whole point of the control.
    cylinder(bm, 0.0, az(wheel_z - 0.0007), POCKET_WHEEL_Y,
             POCKET_BUTTON_R, 0.0015, segments=96, axis="Y")
    bm.to_mesh(button.data)
    bm.free()
    button.data.materials.append(principled(
        # A shade under the wheel and a touch glossier: a separate moulding, which
        # is what the eye reads even before it finds the 0.3mm gap.
        "PocketButton", base_color=(0.062, 0.061, 0.060), metallic=0.0,
        roughness=0.47, coat=0.32, coat_roughness=0.26,
    ))
    finish(button, bevel_width=0.00012, harden=True)
    objects.append(button)

    objects.append(build_pocket_print(wheel_z))

    # ---- the edges: hold switch, headphone jack, connector -----------------
    details = new_mesh_object("pocket_details")
    bm = bmesh.new()
    top_z = POCKET_H / 2.0
    # Hold switch, recessed into the top edge — inset, not sitting on it.
    box(bm, -0.0125, az(0.0), top_z - 0.0013, 0.0085, 0.0026, 0.0022)
    # Headphone jack, sunk into the same edge.
    cylinder(bm, 0.0155, az(0.0), top_z - 0.0018, 0.0032, 0.0030, segments=32, axis="Z")
    # The connector, on the bottom edge where a dock puts it — sunk into the edge,
    # not hanging below it. The exported bounds are the check: app Y must not go
    # past the body's own -POCKET_H/2.
    box(bm, 0.0, az(0.0), -POCKET_H / 2.0 + 0.0011, 0.0262, 0.0026, 0.0018)
    bm.to_mesh(details.data)
    bm.free()
    details.data.materials.append(principled(
        "PocketDetail", base_color=(0.035, 0.035, 0.037), metallic=0.0,
        roughness=0.44,
    ))
    finish(details, bevel_width=0.00010)
    objects.append(details)

    return objects


# Where the wheel's print sits: halfway across the ring, a shade outward of the
# arithmetic middle ((8.55 + 19.5) / 2 = 14.0mm), which is where a thumb's pad
# covers it least while pressing.
POCKET_PRINT_R = 0.0143
# How far above the wheel's face the print is laid — 20µm, nothing a finger or an
# eye could find, and a long way clear of any depth-buffer fight at arm's length.
POCKET_PRINT_LIFT = 0.00002
# The caption's cap height and the transport glyphs' height. The classic's were
# small and quiet; the controls are found by feel and by habit, not by reading.
POCKET_PRINT_CAP_H = 0.0018
POCKET_PRINT_GLYPH_H = 0.0019
# Inter's capitals stand 0.727 of an em, which is how a cap height becomes a size.
INTER_CAP_HEIGHT_EM = 0.727


def build_pocket_print(wheel_z):
    """
    The wheel's four labels — MENU at the top, previous and next at the sides,
    play/pause at the bottom — as **geometry**, not a texture.

    Printed ink is flat and sharp-edged, and at life size the device is looked
    at from a hand's length: a label baked into a texture is only as crisp as
    its pixels, where a mesh is crisp at any distance and costs a few hundred
    triangles. The caption is set in Inter, the app's own face, loaded from
    `res/font` and converted to a mesh here, so nothing Apple drew is borrowed;
    the three transport glyphs are plain triangles and bars.

    One object, one material, 20µm proud of the wheel's face.
    """
    z = az(wheel_z + POCKET_PRINT_LIFT)
    cy = POCKET_WHEEL_Y
    bm = bmesh.new()

    def poly(points):
        """A flat face from (x, up) points, facing the viewer (Blender -Y)."""
        verts = [bm.verts.new((x, z, cy + u)) for (x, u) in points]
        bm.faces.new(verts)

    def triangle(x0, u0, w, h, right=True):
        """An isosceles triangle, base at x0, pointing right (or left)."""
        tip = x0 + w if right else x0 - w
        pts = [(x0, u0 - h / 2.0), (tip, u0), (x0, u0 + h / 2.0)]
        # Counter-clockwise seen from the front, which is what faces -Y.
        poly(pts if right else list(reversed(pts)))

    def bar(x0, u0, w, h):
        poly([(x0, u0 - h / 2.0), (x0 + w, u0 - h / 2.0),
              (x0 + w, u0 + h / 2.0), (x0, u0 + h / 2.0)])

    h = POCKET_PRINT_GLYPH_H
    tri_w = h * 0.80
    bar_w = h * 0.19
    gap = h * 0.16
    r = POCKET_PRINT_R

    # Next, on the right: two triangles nose to tail, then the bar they run into.
    width = tri_w * 2.0 + bar_w
    x = r - width / 2.0
    triangle(x, 0.0, tri_w, h)
    triangle(x + tri_w, 0.0, tri_w, h)
    bar(x + tri_w * 2.0, 0.0, bar_w, h)

    # Previous, on the left: the mirror of it.
    x = -r + width / 2.0
    triangle(x, 0.0, tri_w, h, right=False)
    triangle(x - tri_w, 0.0, tri_w, h, right=False)
    bar(x - tri_w * 2.0 - bar_w, 0.0, bar_w, h)

    # Play/pause, at the bottom: a triangle, then two bars.
    width = tri_w + gap * 1.6 + bar_w * 2.0 + gap
    x = -width / 2.0
    u = -r
    triangle(x, u, tri_w, h)
    x += tri_w + gap * 1.6
    bar(x, u, bar_w, h)
    bar(x + bar_w + gap, u, bar_w, h)

    # MENU, at the top, in the app's own face.
    # Relative to the repository root, like OUT_DIR — the script is run from there.
    font_path = os.path.abspath(os.path.join("app", "src", "main", "res", "font", "inter_variable.ttf"))
    curve = bpy.data.curves.new("pocket_menu_caption", type="FONT")
    curve.font = bpy.data.fonts.load(font_path)
    curve.body = "MENU"
    curve.size = POCKET_PRINT_CAP_H / INTER_CAP_HEIGHT_EM
    curve.align_x = "CENTER"
    curve.align_y = "CENTER"
    # Tracked out a little, as small capitals on a control always are, and thickened
    # by a hair so the strokes hold up in grey on a dark moulding.
    curve.space_character = 1.16
    curve.offset = 0.000025
    curve.resolution_u = 8
    caption = bpy.data.objects.new("pocket_menu_caption", curve)
    bpy.context.collection.objects.link(caption)
    depsgraph = bpy.context.evaluated_depsgraph_get()
    text_mesh = bpy.data.meshes.new_from_object(caption.evaluated_get(depsgraph))
    # A text object lies in its own XY plane facing +Z. Standing it up in the XZ
    # plane faces it down -Y — towards the viewer, per [az] — with its tops up.
    text_mesh.transform(
        Matrix.Translation((0.0, z, cy + r)) @ Matrix.Rotation(math.radians(90.0), 4, "X"))
    bm.from_mesh(text_mesh)
    bpy.data.meshes.remove(text_mesh)
    bpy.data.objects.remove(caption)

    obj = new_mesh_object("pocket_print")
    bm.normal_update()
    bm.to_mesh(obj.data)
    bm.free()
    obj.data.materials.append(principled(
        # Grey ink under the same polished skin as the wheel it is printed on, so the
        # gloss runs over the print without a seam — pad printing is under the coat.
        "PocketPrint", base_color=(0.62, 0.62, 0.63), metallic=0.0,
        roughness=0.50, coat=0.28, coat_roughness=0.30,
    ))
    finish(obj, bevel=False)
    return obj


# ---------------------------------------------------------------------------
# The pocket player's FACE — the phone *is* the player (docs/formats.md §7, step 5c).
# ---------------------------------------------------------------------------

# Chosen by the owner on 2026-10-03 from four prototypes: the device as an object in a room put its
# screen at 44mm and its wheel at 39mm on a 73mm-wide phone, and every iPod-style app that shipped
# (Rewound, ClassiPod, RetroPod, Elvin Hu's) fills the phone instead — the one that showed a small
# device drew reviews asking for the full width. So the face is the phone's whole display: a plate
# running past every edge of it, the window across the top and the wheel where a thumb rests.
#
# **Laid out for the owner's S25 Ultra's display** (1440 x 3120 at 498ppi) and drawn at life size
# on it. The app scales the whole face uniformly to any other display — by its width, or by its
# height on one wider than this — and centres it, so a phone of another shape gets the same face a
# little larger or smaller (`PocketFace.kt` agrees with every number here).
#
# In the face's own frame, app axes: X right, Y up, **Z = 0 at the front surface** (the device's
# was at +T/2), origin at the reference display's centre. Everything a thumb touches keeps the
# device's real tolerances — the 0.4mm hairline, the 0.6mm recess, the button's step — and only
# the parts that scale with a hand grow: the window, the wheel, the button and its print.
FACE_DISPLAY_W = 1440.0 / 498.0 * 0.0254     # 73.45mm
FACE_DISPLAY_H = 3120.0 / 498.0 * 0.0254     # 159.13mm
# The plate, far past the display on every side: wide enough for a square display scaled to fit
# its height, tall enough for a 21:9 one scaled to fit its width.
FACE_PLATE_W = 0.180
FACE_PLATE_H = 0.240
FACE_FRONT_T = POCKET_FRONT_T
# The window: 6.5mm of plate either side, its top 15mm under the display's top (clear of the
# status bar), and the picture inside it 0.8mm all round — the device's mask, grown with it.
FACE_SCREEN_MARGIN = 0.0065
FACE_SCREEN_W = FACE_DISPLAY_W - 2.0 * FACE_SCREEN_MARGIN
FACE_LCD_INSET = 0.0008
FACE_LCD_W = FACE_SCREEN_W - 2.0 * FACE_LCD_INSET
FACE_LCD_H = FACE_LCD_W * 475.0 / 640.0
FACE_SCREEN_H = FACE_LCD_H + 2.0 * FACE_LCD_INSET
FACE_SCREEN_TOP = 0.015
FACE_SCREEN_Y = FACE_DISPLAY_H / 2.0 - FACE_SCREEN_TOP - FACE_SCREEN_H / 2.0
FACE_SCREEN_R = 0.0022
# The wheel: 51mm across, its centre 54mm above the display's bottom — where a thumb rests with
# the phone in one hand, and well clear of the gesture bar under it.
FACE_WHEEL_R = 0.0255
FACE_WHEEL_Y = -FACE_DISPLAY_H / 2.0 + 0.054
FACE_SCALE = FACE_WHEEL_R / POCKET_WHEEL_R          # the device's wheel to the face's: 1.31
FACE_BUTTON_R = POCKET_BUTTON_R * FACE_SCALE
# The hairlines round the wheel and the button, a shade finer than the device's: seen straight on
# and life size across a whole phone, 0.4mm read as a ring drawn round the wheel, not a gap.
FACE_WHEEL_GAP = 0.00025
FACE_BUTTON_GAP = 0.0002
# The wear maps' density: the device's own, about 8 pixels a millimetre.
FACE_WEAR_PX_PER_MM = 8.0


def build_pocket_face():
    """
    `pocket_face.glb`: the device's parts under the device's own names — `pocket_front`,
    `pocket_screen`, `pocket_glass`, `pocket_wheel`, `pocket_button`, `pocket_print` — so the app
    binds the finish and rocks the wheel exactly as it did the device's, and `pocket_floor` under
    them for the hairlines to look down to. No back, no bead, no edges: they are past the
    display, and nothing draws what no one can see.

    **Needs `pocket.glb` built first**: the print is the device's own, scaled to the bigger wheel.
    Blender 5.0 drops triangles from the M of MENU (`docs/models.md`), so the caption is taken
    from the committed mesh rather than set again — the same letters, in any Blender.
    """
    np = _np()
    front_z = 0.0
    objects = []

    def tex_extend(mat):
        # The wear maps cover the display, not the plate: past it they hold their edge.
        for node in mat.node_tree.nodes:
            if node.type == "TEX_IMAGE":
                node.extension = "EXTEND"

    # ---- the plate: one face with two holes, filled and extruded, as the device's is ---------
    front = new_mesh_object("pocket_front")
    bm = bmesh.new()
    _, outer_e = rounded_rect(bm, FACE_PLATE_W, FACE_PLATE_H, 0.01, az(front_z))
    _, screen_e = rounded_rect(bm, FACE_SCREEN_W, FACE_SCREEN_H, FACE_SCREEN_R, az(front_z),
                               cz=FACE_SCREEN_Y)
    _, wheel_e = circle_loop(bm, FACE_WHEEL_R + FACE_WHEEL_GAP, az(front_z), FACE_WHEEL_Y,
                             segments=128)
    bmesh.ops.triangle_fill(bm, use_beauty=True, use_dissolve=False,
                            edges=outer_e + screen_e + wheel_e)
    ex = bmesh.ops.extrude_face_region(bm, geom=[f for f in bm.faces])
    moved = [v for v in ex["geom"] if isinstance(v, bmesh.types.BMVert)]
    bmesh.ops.translate(bm, verts=moved, vec=(0.0, -az(FACE_FRONT_T), 0.0))
    bm.to_mesh(front.data)
    bm.free()
    # The device's anodising, the same numbers: the app's finish (`PocketFinish`) puts its own
    # colour, roughness and metalness on these factors, over the same wear.
    front_mat = principled(
        "PocketFront", base_color=(0.205, 0.200, 0.196), metallic=1.0,
        roughness=0.34, coat=0.12, coat_roughness=0.26, anisotropic=0.15,
    )
    _planar_uvs(front, -FACE_DISPLAY_W / 2.0, FACE_DISPLAY_W / 2.0,
                -FACE_DISPLAY_H / 2.0, FACE_DISPLAY_H / 2.0)
    wear_w = int(round(FACE_DISPLAY_W * 1000.0 * FACE_WEAR_PX_PER_MM))
    wear_h = int(round(FACE_DISPLAY_H * 1000.0 * FACE_WEAR_PX_PER_MM))
    _use_metal_rough(front_mat, _metal_rough_image("pocket_face_front_mr",
                                                   pocket_front_wear(wear_w, wear_h, seed=22), 1.0))
    tex_extend(front_mat)
    front.data.materials.append(front_mat)
    finish(front, bevel_width=0.00018, harden=True)
    objects.append(front)

    # ---- the screen: the black surround, then the glass over it ------------------------------
    panel = new_mesh_object("pocket_screen")
    bm = bmesh.new()
    clear = 0.00002
    _, e = rounded_rect(bm, FACE_SCREEN_W - 2.0 * clear, FACE_SCREEN_H - 2.0 * clear,
                        FACE_SCREEN_R - clear, az(front_z - POCKET_SCREEN_SINK), cz=FACE_SCREEN_Y)
    bmesh.ops.contextual_create(bm, geom=e)
    bm.to_mesh(panel.data)
    bm.free()
    panel.data.materials.append(principled(
        "PocketScreen", base_color=(0.012, 0.012, 0.013), metallic=0.0, roughness=0.55,
    ))
    finish(panel, bevel=False)
    objects.append(panel)

    glass = new_mesh_object("pocket_glass")
    bm = bmesh.new()
    _, e = rounded_rect(bm, FACE_SCREEN_W, FACE_SCREEN_H, FACE_SCREEN_R,
                        az(front_z - 0.00005), cz=FACE_SCREEN_Y)
    bmesh.ops.contextual_create(bm, geom=e)
    ex = bmesh.ops.extrude_face_region(bm, geom=[f for f in bm.faces])
    moved = [v for v in ex["geom"] if isinstance(v, bmesh.types.BMVert)]
    bmesh.ops.translate(bm, verts=moved, vec=(0.0, -az(0.0004), 0.0))
    bm.to_mesh(glass.data)
    bm.free()
    # Blended at a few percent, not transmissive — `build_pocket()`'s glass says why.
    glass.data.materials.append(principled(
        "PocketGlass", base_color=(0.015, 0.016, 0.018), metallic=0.0,
        roughness=0.03, alpha=0.06, blend=True,
    ))
    _planar_uvs(glass, -FACE_SCREEN_W / 2.0, FACE_SCREEN_W / 2.0,
                FACE_SCREEN_Y - FACE_SCREEN_H / 2.0, FACE_SCREEN_Y + FACE_SCREEN_H / 2.0)
    glass_w = int(round(FACE_SCREEN_W * 1000.0 * FACE_WEAR_PX_PER_MM * 1.4))
    glass_h = int(round(glass_w * FACE_SCREEN_H / FACE_SCREEN_W))
    _use_metal_rough(glass.data.materials[0], _metal_rough_image(
        "pocket_face_glass_mr", pocket_glass_wear(glass_w, glass_h, seed=21, width_m=FACE_SCREEN_W), 0.0))
    finish(glass, bevel_width=0.00008, harden=True)
    objects.append(glass)

    # ---- the wheel and its button --------------------------------------------------------------
    wheel = new_mesh_object("pocket_wheel")
    bm = bmesh.new()
    wheel_z = front_z - POCKET_WHEEL_SINK
    for radius in (FACE_WHEEL_R, FACE_BUTTON_R + FACE_BUTTON_GAP):
        bmesh.ops.create_circle(
            bm, cap_ends=False, segments=128, radius=radius,
            matrix=(Matrix.Translation((0.0, az(wheel_z), FACE_WHEEL_Y))
                    @ Matrix.Rotation(math.radians(90.0), 4, "X")))
    bmesh.ops.bridge_loops(bm, edges=[e for e in bm.edges])
    # **Facing out of the plate, whatever the bridge chose.** The solidify below thickens against
    # the faces' normals, and which way a bridged ring faces depends on its loops' order — with
    # this button's radius it came out facing in, and the wheel stood 0.7mm proud of the plate
    # with the print buried under it. The exported bounds caught it, as they did the device's.
    for f in bm.faces:
        f.normal_update()
        if f.normal.y > 0.0:            # Blender +Y is app -Z: into the phone
            f.normal_flip()
    bm.to_mesh(wheel.data)
    bm.free()
    solid = wheel.modifiers.new(name="Thickness", type="SOLIDIFY")
    solid.thickness = 0.0013
    solid.offset = -1.0          # backwards, into the recess — see build_pocket()
    wheel.data.materials.append(principled(
        "PocketWheel", base_color=(0.072, 0.071, 0.070), metallic=0.0,
        roughness=0.54, coat=0.28, coat_roughness=0.30,
    ))
    _planar_uvs(wheel, -FACE_WHEEL_R, FACE_WHEEL_R,
                FACE_WHEEL_Y - FACE_WHEEL_R, FACE_WHEEL_Y + FACE_WHEEL_R)
    _use_metal_rough(wheel.data.materials[0], _metal_rough_image(
        "pocket_face_wheel_mr", pocket_wheel_wear(640, seed=23, radius_m=FACE_WHEEL_R), 0.0))
    finish(wheel, bevel_width=0.00010, harden=True)
    objects.append(wheel)

    button = new_mesh_object("pocket_button")
    bm = bmesh.new()
    cylinder(bm, 0.0, az(wheel_z - 0.0007), FACE_WHEEL_Y,
             FACE_BUTTON_R, 0.0015, segments=128, axis="Y")
    bm.to_mesh(button.data)
    bm.free()
    button.data.materials.append(principled(
        "PocketButton", base_color=(0.062, 0.061, 0.060), metallic=0.0,
        roughness=0.47, coat=0.32, coat_roughness=0.26,
    ))
    finish(button, bevel_width=0.00012, harden=True)
    objects.append(button)

    # ---- the floor under it all -------------------------------------------------------------
    # What the hairlines round the wheel and the button look down to. With nothing there they
    # showed the void, and a 0.4mm gap read as a heavy black ring drawn round the wheel; a real
    # one shows the dark moulding inside, lit a little through the gap itself.
    floor = new_mesh_object("pocket_floor")
    bm = bmesh.new()
    _, e = rounded_rect(bm, FACE_PLATE_W, FACE_PLATE_H, 0.01, az(front_z - 0.0024))
    bmesh.ops.contextual_create(bm, geom=e)
    bm.to_mesh(floor.data)
    bm.free()
    floor.data.materials.append(principled(
        "PocketFloor", base_color=(0.12, 0.12, 0.125), metallic=0.0, roughness=0.6,
    ))
    finish(floor, bevel=False)
    objects.append(floor)

    # ---- the print: the device's, scaled about the wheel's centre onto the face's ------------
    before = set(bpy.context.scene.objects)
    bpy.ops.import_scene.gltf(filepath=os.path.abspath(os.path.join(OUT_DIR, "pocket.glb")))
    imported = set(bpy.context.scene.objects) - before
    print_obj = None
    for obj in imported:
        if obj.name.startswith("pocket_print") and obj.type == "MESH":
            print_obj = obj
    for obj in imported:
        if obj is not print_obj:
            bpy.data.objects.remove(obj, do_unlink=True)
    if print_obj is None:
        raise RuntimeError("pocket.glb has no pocket_print to scale: build pocket.glb first")
    print_obj.parent = None
    device_print_z = POCKET_T / 2.0 - POCKET_WHEEL_SINK + POCKET_PRINT_LIFT
    k = FACE_SCALE
    # Its vertices sit at the device's wheel (centre y -22.5mm, face z +4.67mm): scaled by k about
    # the origin, then moved so that centre lands on the face's wheel, 20µm over its face.
    bpy.context.view_layer.update()
    mesh = print_obj.data
    world = print_obj.matrix_world.copy()
    for v in mesh.vertices:
        co = world @ v.co                      # Blender: (x, -app_z, app_y)
        app_x, app_y, app_z = co.x, co.z, -co.y
        nx = app_x * k
        ny = (app_y - POCKET_WHEEL_Y) * k + FACE_WHEEL_Y
        nz = wheel_z + POCKET_PRINT_LIFT + (app_z - device_print_z)
        v.co = Vector((nx, az(nz), ny))
    print_obj.matrix_world = Matrix.Identity(4)
    print_obj.name = "pocket_print"
    print_obj.data.name = "pocket_print"
    objects.append(print_obj)

    # Pixels and bounds, for the log and docs/models.md.
    print("JAVON-BUILD face: screen %.2f x %.2fmm at y %.2f, picture %.2f x %.2fmm, wheel %.1fmm at y %.2f"
          % (FACE_SCREEN_W * 1000, FACE_SCREEN_H * 1000, FACE_SCREEN_Y * 1000,
             FACE_LCD_W * 1000, FACE_LCD_H * 1000, FACE_WHEEL_R * 2000, FACE_WHEEL_Y * 1000))
    return objects


def rounded_rect_xy(bm, w, d, r, z, cx=0.0, cy=0.0, arc=8):
    """A closed rounded-rectangle loop in Blender's XY plane at height [z] — a tabletop's outline."""
    hw, hd = w / 2.0 - r, d / 2.0 - r
    verts = []
    for ccx, ccy, a0 in ((hw, hd, 0.0), (-hw, hd, 90.0), (-hw, -hd, 180.0), (hw, -hd, 270.0)):
        for i in range(arc + 1):
            a = math.radians(a0 + 90.0 * i / arc)
            verts.append(bm.verts.new((cx + ccx + r * math.cos(a), cy + ccy + r * math.sin(a), z)))
    edges = [bm.edges.new((verts[i], verts[(i + 1) % len(verts)])) for i in range(len(verts))]
    return verts, edges


# ---------------------------------------------------------------------------
# The showroom — the gallery between formats (docs/formats.md §7, step 5b).
# ---------------------------------------------------------------------------

# A row of plinths along +X, one per format, on a black floor, in the showroom's own coordinates
# (app Y up). `ShowroomScreen` puts each format's object on a plinth's cap and a spot over it;
# these numbers are its numbers.
SHOWROOM_SPACING = 0.42
SHOWROOM_PLINTHS = 4
SHOWROOM_PLINTH_W = 0.15
SHOWROOM_PLINTH_R = 0.012
SHOWROOM_PLINTH_H = 0.95
SHOWROOM_CAP_W = 0.17
SHOWROOM_CAP_T = 0.018
SHOWROOM_FOOT_W = 0.17
SHOWROOM_FOOT_T = 0.03
SHOWROOM_FLOOR_HALF = 6.0
# The gallery's back wall, behind the row: what the wash behind each plinth lands on.
SHOWROOM_WALL_Z = -2.2
SHOWROOM_WALL_H = 3.5


def _rounded_block(bm, w, r, y0, y1, cx):
    """A square block with rounded upright edges, from app Y [y0] to [y1], centred on X [cx]."""
    _, e = rounded_rect_xy(bm, w, w, r, y0, cx=cx)
    faces = bmesh.ops.contextual_create(bm, geom=e)["faces"]
    ex = bmesh.ops.extrude_face_region(bm, geom=faces)
    moved = [v for v in ex["geom"] if isinstance(v, bmesh.types.BMVert)]
    bmesh.ops.translate(bm, verts=moved, vec=(0.0, 0.0, y1 - y0))


def build_showroom():
    """
    The gallery: a polished concrete floor, a plaster wall behind, and a row of charcoal plinths,
    each with a moulded cap and foot. One object per material. The objects on the plinths and the
    lights over them are the app's.
    """
    objects = []
    floor = new_mesh_object("showroom_floor")
    bm = bmesh.new()
    mid = SHOWROOM_SPACING * (SHOWROOM_PLINTHS - 1) / 2.0
    box(bm, mid, az(0.0), -0.01, 2.0 * SHOWROOM_FLOOR_HALF, 2.0 * SHOWROOM_FLOOR_HALF, 0.02)
    bm.to_mesh(floor.data)
    bm.free()
    floor.data.materials.append(principled(
        # Polished dark concrete: dark enough that the gallery stays dark, light enough that each
        # spot leaves a pool on it and the plinth's shadow runs back through the pool. Black stone
        # was tried first and returned nothing at all — at this room's exposure a surface is seen
        # by what it reflects, and a black floor under a spot reflects a black gallery.
        "ShowroomFloor", base_color=(0.055, 0.053, 0.050), metallic=0.0, roughness=0.38,
    ))
    finish(floor, bevel=False)
    objects.append(floor)

    wall = new_mesh_object("showroom_wall")
    bm = bmesh.new()
    box(bm, mid, az(SHOWROOM_WALL_Z - 0.05), SHOWROOM_WALL_H / 2.0,
        2.0 * SHOWROOM_FLOOR_HALF, 0.1, SHOWROOM_WALL_H)
    bm.to_mesh(wall.data)
    bm.free()
    wall.data.materials.append(principled(
        # Dark warm plaster, matte: seen only where the wash behind each plinth lands, a soft glow
        # the exhibit stands out against rather than a black it disappears into.
        "ShowroomPlaster", base_color=(0.085, 0.078, 0.070), metallic=0.0, roughness=0.92,
    ))
    finish(wall, bevel=False)
    objects.append(wall)

    plinths = new_mesh_object("showroom_plinths")
    bm = bmesh.new()
    for i in range(SHOWROOM_PLINTHS):
        cx = i * SHOWROOM_SPACING
        _rounded_block(bm, SHOWROOM_FOOT_W, SHOWROOM_PLINTH_R, 0.0, SHOWROOM_FOOT_T, cx)
        _rounded_block(bm, SHOWROOM_PLINTH_W, SHOWROOM_PLINTH_R, SHOWROOM_FOOT_T,
                       SHOWROOM_PLINTH_H, cx)
        _rounded_block(bm, SHOWROOM_CAP_W, SHOWROOM_PLINTH_R * 1.2, SHOWROOM_PLINTH_H,
                       SHOWROOM_PLINTH_H + SHOWROOM_CAP_T, cx)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(plinths.data)
    bm.free()
    plinths.data.materials.append(principled(
        # Charcoal satin under a thin coat: a spot falls off down the front of it into the dark,
        # which is how a plinth is seen in a gallery. Black lacquer was tried first, and read as
        # nothing — its only light was the spot's reflection, which goes away from the camera.
        "ShowroomLacquer", base_color=(0.045, 0.045, 0.048), metallic=0.0,
        roughness=0.32, coat=0.45, coat_roughness=0.12,
    ))
    finish(plinths, bevel_width=0.002, harden=True)
    objects.append(plinths)
    return objects


def mesh_add(target, source):
    """Fold `source` into `target` and free it. bmesh has no union operator."""
    tmp = bpy.data.meshes.new("tmp")
    source.to_mesh(tmp)
    source.free()
    target.from_mesh(tmp)
    bpy.data.meshes.remove(tmp)


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

# Radial cuts around the disc, and the ring boundaries between them. Their product is
# the piece count: 9 x 3 = 27 fragments of genuinely different sizes.
SHARD_SPOKES = 9
SHARD_RINGS = (0.0, 0.34, 0.68, 1.0)


def build_disc_shards():
    """
    The disc, fractured, for the deletion animation.

    The first version of this was eight even wedges and it read as a pie chart
    coming apart, which is the single most common tell in a fake shatter: real
    fracture produces pieces of *visibly different sizes*, and animation guidance
    is blunt about identical timing across identical masses destroying the
    illusion. So this cuts on two axes — nine jittered spokes crossed with three
    jittered rings — for 27 fragments spanning roughly an order of magnitude in
    area.

    **Each shard's origin is its own centroid, not the disc's centre.** That is
    what lets the app spin a piece about its own centre of mass instead of
    swinging it around the hub like a fairground ride, and it means the app can
    read every piece's rest position straight off its glTF node rather than
    duplicating this layout in Kotlin.

    **The mass is encoded in the node name** — `shard_<index>_<per-mille area>`.
    The app needs it because small fragments must fly fast and large ones slow
    (impact studies put the largest, slowest pieces opposite the impact), and
    computing area from geometry at runtime is work the exporter can do once.

    UVs use `build_disc`'s formula, computed *before* the origin moves, so an
    album's disc texture lands on the pieces with the artwork continuous across
    every crack.
    """
    rng = random.Random(20260827)
    pieces = []

    spokes = sorted(
        (i + rng.uniform(-0.34, 0.34)) / SHARD_SPOKES for i in range(SHARD_SPOKES)
    )

    def crack(angle, radius_t):
        """A cut line that wanders, so two neighbours share a jagged edge."""
        return angle + math.sin(radius_t * 6.2 + angle * 4.1) * 0.022

    for si in range(SHARD_SPOKES):
        a0 = spokes[si] * math.tau
        a1 = (spokes[(si + 1) % SHARD_SPOKES]
              + (1.0 if si == SHARD_SPOKES - 1 else 0.0)) * math.tau

        # Ring boundaries jittered per spoke, so pieces do not line up in tidy
        # concentric bands across the whole disc.
        rings = [
            r + (0.0 if r in (0.0, 1.0) else rng.uniform(-0.07, 0.07))
            for r in SHARD_RINGS
        ]

        for ri in range(len(rings) - 1):
            r_in = DISC_HOLE_R + (DISC_R - DISC_HOLE_R) * rings[ri]
            r_out = DISC_HOLE_R + (DISC_R - DISC_HOLE_R) * rings[ri + 1]

            steps = max(3, int(round((a1 - a0) / math.tau * 72)))
            bm = bmesh.new()

            ring_verts = []
            for s in range(steps + 1):
                t = s / steps
                angle = a0 + (a1 - a0) * t
                inner_a = angle
                outer_a = angle
                if s == 0:
                    inner_a = crack(a0, rings[ri])
                    outer_a = crack(a0, rings[ri + 1])
                elif s == steps:
                    inner_a = crack(a1, rings[ri])
                    outer_a = crack(a1, rings[ri + 1])
                ring_verts.append((
                    bm.verts.new((r_in * math.cos(inner_a), 0.0, r_in * math.sin(inner_a))),
                    bm.verts.new((r_out * math.cos(outer_a), 0.0, r_out * math.sin(outer_a))),
                ))

            for s in range(steps):
                i0, o0 = ring_verts[s]
                i1, o1 = ring_verts[s + 1]
                bm.faces.new((i0, o0, o1, i1))

            bm.normal_update()

            # Planar area, before thickness — this is what decides how fast the
            # piece flies, so it is measured rather than guessed.
            area = sum(f.calc_area() for f in bm.faces)

            obj = new_mesh_object("shard_tmp")
            bm.to_mesh(obj.data)
            bm.free()
            pieces.append((obj, area))

    total = max(p[1] for p in pieces)
    objects = []

    for index, (obj, area) in enumerate(pieces):
        mesh = obj.data
        if not mesh.uv_layers:
            mesh.uv_layers.new(name="UVMap")
        uv = mesh.uv_layers.active.data
        # Before origin_set, while local coordinates still equal disc coordinates.
        for poly in mesh.polygons:
            for li in poly.loop_indices:
                v = mesh.vertices[mesh.loops[li].vertex_index].co
                uv[li].uv = (v.x / (2.0 * DISC_R) + 0.5, v.z / (2.0 * DISC_R) + 0.5)

        solidify = obj.modifiers.new(name="Thickness", type="SOLIDIFY")
        solidify.thickness = DISC_T
        solidify.offset = 0.0
        solidify.use_even_offset = True

        finish(obj, bevel_width=0.00012)

        mesh.materials.append(
            attach_placeholder_basecolor(
                principled(
                    f"ShardFace{index}",
                    base_color=(0.80, 0.80, 0.82),
                    metallic=0.25,
                    roughness=0.28,
                    coat=0.6,
                    coat_roughness=0.10,
                ),
                f"shard_placeholder_{index}",
            )
        )

        # Origin to the piece's own centre. Everything above is in disc space; from
        # here the mesh is local and the object's transform carries where it sits.
        bpy.context.view_layer.objects.active = obj
        obj.select_set(True)
        bpy.ops.object.origin_set(type="ORIGIN_GEOMETRY", center="MEDIAN")
        obj.select_set(False)

        # Name carries everything the app needs to place and move this piece:
        #
        #     shard_<index>_<per-mille area>_<centre x>_<centre y>
        #
        # **The centre is in the name because the app cannot read it off the node.**
        # SceneView's `Node.position` is its own transform, which starts at identity — not
        # the glTF node's translation, which is what actually holds each shard's centroid.
        # Reading `position` gives zero for every piece, and writing it then *overwrites*
        # the real placement, collapsing all 27 fragments onto the disc's centre. That is
        # exactly what happened.
        #
        # Encoded as tenths of a millimetre, offset by 1000 so negatives survive a name:
        # `app_metres = (value - 1000) / 10000`. Blender X and Z are the app's X and Y —
        # see the module docstring on the axis mapping.
        per_mille = max(1, min(999, int(round(area / total * 999))))
        ex = int(round(obj.location.x * 10000.0)) + 1000
        ey = int(round(obj.location.z * 10000.0)) + 1000
        obj.name = f"shard_{index:02d}_{per_mille:03d}_{ex:04d}_{ey:04d}"
        obj.data.name = obj.name
        objects.append(obj)

    return objects


def export(objects, filename, image_format="AUTO", jpeg_quality=75):
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
        # JPEG for large maps (the pocket face's wear): lossy is fine for albedo and roughness, and a
        # 2048-wide grain is megabytes as PNG. Everything else keeps its own format.
        export_image_format=image_format,
        export_jpeg_quality=jpeg_quality,
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
    # Optional filter: `blender --background --factory-startup --python
    # tools/models/build_models.py -- pocket` builds only the models whose file
    # name contains "pocket". Without it every model is rebuilt, and disc.glb is
    # not byte-reproducible (see docs/models.md), so a one-model change would
    # otherwise dirty 188KB of unrelated binary on every run.
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    wanted = [a.lower() for a in argv]

    def skip(filename):
        if not wanted:
            return False
        return not any(w in filename.lower() for w in wanted)

    paths = []

    if not skip("case_tray.glb"):
        clear_scene()
        paths.append(export([build_tray()], "case_tray.glb"))

    if not skip("case_lid.glb"):
        clear_scene()
        paths.append(export([build_lid()], "case_lid.glb"))

    if not skip("case_closed.glb"):
        clear_scene()
        paths.append(export([build_closed_case()], "case_closed.glb"))

    if not skip("beam.glb"):
        clear_scene()
        paths.append(export([build_beam()], "beam.glb"))

    if not skip("disc.glb"):
        clear_scene()
        paths.append(export([build_disc()], "disc.glb"))

    # The props. One file each: they are placed independently and a shop that
    # gains a fourth object should not mean re-exporting the other three.
    if not skip("turntable.glb"):
        clear_scene()
        paths.append(export(build_turntable(), "turntable.glb"))
    if not skip("clock.glb"):
        clear_scene()
        paths.append(export(build_clock(), "clock.glb"))
    if not skip("plant.glb"):
        clear_scene()
        paths.append(export(build_plant(), "plant.glb"))

    # The pocket player — the iPod mode's device. Eight objects in one file,
    # because they are one object in the hand and the app addresses its parts by
    # the names they carry here.
    if not skip("pocket.glb"):
        clear_scene()
        paths.append(export(build_pocket(), "pocket.glb"))

    # The pocket player's face: the phone as the player, its print the device's (so after it).
    # JPEG for its wear maps — roughness noise is megabytes as PNG and nobody can see the loss.
    if not skip("pocket_face.glb"):
        clear_scene()
        paths.append(export(build_pocket_face(), "pocket_face.glb", image_format="JPEG", jpeg_quality=90))




    # The showroom between formats.
    if not skip("showroom.glb"):
        clear_scene()
        paths.append(export(build_showroom(), "showroom.glb"))

    # Exported together, unlike the case parts: these eight are one object that
    # has come apart, the app wants them all or none, and it addresses them by
    # the names they carry here.
    if not skip("disc_shards.glb"):
        clear_scene()
        paths.append(export(build_disc_shards(), "disc_shards.glb"))

    for path in paths:
        size = os.path.getsize(path)
        print(f"JAVON-BUILD wrote {path} ({size / 1024.0:.1f} KB)")


if __name__ == "__main__":
    main()
