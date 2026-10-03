# Realism — what the renderer can actually do, and whether to keep it

Written 2026-09-22, for the new format rooms and the owner's bar: *"dont hold back at
all … use whatever tool we can use to achieve hyper realism and beauty … the highest
quality of 3d graphics and animation"*, with the renderer itself open to change.

**Everything in §1 was read out of the artifacts this app actually ships**, not from
upstream documentation, because upstream documents HEAD and this app pins Filament
**1.72.1** (through SceneView 4.32.0). `docs/room-design.md` §1 found the hard way that
some option fields are silently dropped by the JNI binding; §1.1 below is that audit
finished.

---

## 1. Filament 1.72.1, measured

Method: unpack `filament-android-1.72.1.aar`, `gltfio-android-1.72.1.aar` and
`sceneview-4.32.0.aar`, `javap -p -c` the classes, read the bytecode of each setter to
see which fields it passes to native, grep the native `.so` for extension names, and
compile test materials with the matching `matc`.

### 1.1 Post-processing: what the JNI forwards, field by field

A field that is not forwarded is **silently ignored** — it compiles, runs, and does
nothing.

| Option | Forwarded | Dropped |
|---|---|---|
| `TemporalAntiAliasingOptions` | `feedback`, `filterWidth`, `enabled` — **three of sixteen** | `sharpness`, `upscaling`, `jitterPattern`, `boxType`, `boxClipping`, `varianceGamma`, `preventFlickering`, `historyReprojection`, `filterHistory`, `filterInput`, `useYCoCg`, `lodBias`, `hdr` |
| `AmbientOcclusionOptions` | `radius`, `bias`, `power`, `resolution`, `intensity`, `bilateralThreshold`, `quality`, `lowPassFilter`, `upsampling`, `enabled`, `bentNormals`, `minHorizonAngleRad` | **`aoType`** (so GTAO cannot be selected from Java), every `gtao*` field, every `ssct*` field (cone-traced shadows unreachable) |
| `BloomOptions` | everything else, including `lensFlare`, `starburst`, `ghost*`, `halo*`, `dirt` | `quality` (confirms `room-design.md` §1) |
| `DepthOfFieldOptions` | `cocScale`, `maxApertureDiameter`, `enabled`, `filter`, `nativeResolution`, ring counts, max COCs | `cocAspectRatio` |
| `FogOptions` | all, including height falloff, `inScattering*`, `fogColorFromIbl`, `skyColor` | — |
| `ScreenSpaceReflectionsOptions` | all five (`thickness`, `bias`, `maxDistance`, `stride`, `enabled`) | — |
| `VignetteOptions`, `MultiSampleAntiAliasingOptions`, `DynamicResolutionOptions`, `SoftShadowOptions`, `VsmShadowOptions` | all | — |

So **TAA is available but only in its crudest form**, and **GTAO and cone-traced
shadows are not reachable at all** from this Filament's Java API. Both are arguments for
upgrading Filament (§4) rather than for changing engine.

### 1.2 Lighting, camera, shadows

- Light types: `SUN`, `DIRECTIONAL`, `POINT`, `FOCUSED_SPOT`, `SPOT`. **No area
  lights** — a physically large, soft source has to be faked, and emissive geometry lights
  nothing.
- `ShadowOptions` carries `screenSpaceContactShadows` + `stepCount` + `maxShadowDistance`,
  `shadowBulbRadius`, `blurWidth`, cascades, `elvsm`. **Contact shadows are available** and
  are what seats a small object on a surface.
- `IndirectLight.setIntensity` and **`setRotation(float[])`** — the environment can be
  turned per frame, which is one cheap way to make reflections move.
- Camera: `setExposure(aperture, shutter, ISO)`, `setFocusDistance`, `setLensProjection`.
  A real 35mm-equivalent lens with a real aperture, and DOF focused where the lens is.
- One IBL per scene, no reflection probes, **no real-time GI**, and **no motion blur of any
  kind** (there is no such option class).

### 1.3 Materials

`matc` 1.72.1 compiled both of these on this machine (`--api opengl --api vulkan
--platform mobile -Os`):

- **Rain on glass:** `refractionMode : screenspace`, `refractionType : thin`,
  transmission, absorption, thickness, microThickness, IOR, clear coat, an animated
  normal map, `specularAntiAliasing`. 616 KB.
- **Brushed metal under passing light:** normal map, `clearCoatNormal`, `bentNormal`,
  `anisotropy` + `anisotropyDirection`, sheen, and — the important one — a
  **`samplerExternal`**, i.e. a material can sample live video. 738 KB.

And **imported glTF already gets full PBR**: gltfio 1.72.1's shaders expose `normalMap`,
`normalScale`, `clearCoatNormalMap`, `occlusionMap`, `metallicRoughnessMap`,
`emissiveMap`, `sheenColorMap`. The extensions its binary names are
`KHR_materials_anisotropy`, `clearcoat`, `sheen`, `specular`, `ior`, `transmission`,
`volume`, `iridescence`, `dispersion`, `diffuse_transmission`, `emissive_strength`,
`unlit`, `variants`, `pbrSpecularGlossiness`, plus `KHR_texture_basisu` (KTX2),
`KHR_texture_transform`, `EXT_texture_webp`, `KHR_draco_mesh_compression`,
`EXT_meshopt_compression`, `EXT_mesh_gpu_instancing`, `KHR_lights_punctual`.

> **This corrects a line in `ENGINEERING.md`'s custom-materials row.** "The stock set cannot do
> a normal map" is true of *SceneView's* stock materials, which the CD room's wall and
> floor planes use. It is not true of a model imported as glTF: those carry their own
> materials and normal-map fine. A hero object does not need a hand-written `.mat` to be
> normal-mapped.

### 1.4 The device's screen, and video

`ViewNode` (already used for the shelf label plates) renders an Android view — so any
Compose content — into a `SurfaceTexture` → Filament `Stream` → `Texture`: the same path
hardware-decoded video takes. Its `materialInstance` is settable and its `Stream` and
`Texture` are exposed, so the pocket player's screen can be **live Compose sampled by a
custom LCD material** — pixel grid, backlight, glass Fresnel, reflections — rather than a
flat image. It also has a touch forwarder, which this design does not want: you could not
touch an iPod's screen.

`Texture.setExternalStream` and `Texture.Sampler.SAMPLER_EXTERNAL` exist, so the same
mechanism carries **real video into the scene** (§3).

### 1.5 The asset pipeline, tested here

Blender 5.1 headless on this machine baked a 1024×1024 Cycles AO map at 32 samples in
**2.7 seconds** on CPU. Its glTF exporter offers tangents, Draco, WebP and image quality
controls. So the existing "committed script generates the models" approach extends to
baked normal/AO/curvature maps without new tooling.

---

## 2. What Filament 1.72.1 cannot do

**First, the distinction that matters: most of §1.1's gaps are the *binding*, not the
engine.** `libfilament-jni.so` contains both `gtao` and `ssct`, and the Java options class
carries every `gtao*` and `ssct*` field — they exist, and the JNI setter simply does not
pass them. Filament is **Apache License 2.0**, its Java and JNI layers are in its own
repository, and the missing lines are a handful per option. So "Filament cannot" is, for
GTAO, cone-traced shadows and the thirteen dropped TAA fields, really "this prebuilt AAR
will not" — fixable by upgrading, by rebuilding the AAR from source, or by a small JNI
shim of our own against the native `View` pointer. The list below is what is missing from
the *engine*.

1. **No *real-time* global illumination and no reflection probes.** One IBL, everywhere —
   but indirect light can be baked: a material with `requires : [ uv0, uv1 ]` sampling a
   Cycles-baked lightmap on the second UV set **compiles** (tested, 612 KB), so a static
   interior can carry path-traced bounce, and with it the soft shape of light that
   real-time GI would have computed.
2. **No area lights** *at runtime*. Bake them (they are ordinary lights to Cycles) and let
   shadow options do the rest for anything that moves.
3. **No motion blur.**
4. **TAA in name only** (§1.1), and **no GTAO or SSCT** from Java — see the note above:
   engine yes, binding no.
5. **SSR reflects only what is on screen** — a reflection of something off-frame, or of
   the viewer, is not available. It also measured **7× cost** with a case open in the CD
   room, which is a CD-room number and not a verdict for a one-object room.
6. **No normal maps on SceneView's own stock materials** — that ceiling is real, and the
   answer is to make such surfaces glTF models or hand-written materials.

**None of these is fatal for the pocket player's room**, because the room is one hero
object, a window and a dark interior: its indirect light can be baked, its reflections are
mostly of lights that *are* on screen, and its softness comes from small sources close to
the object.

---

## 3. The train window, technique by technique

Prioritised for this scene. Costs are estimates until measured per traps 28–30.

**The room's light is the lights going past.** Two or three moving `POINT`/`SPOT` lights
travelling along the carriage, rhythmically spaced like catenary poles, are what make a
polished object read as real: the highlight slides across it, the anodised front lights up
edge-first, the screen's glass catches a streak. This is cheap, it is animation rather
than geometry, and it is the single biggest win. Light channels (trap 7) keep them off
anything they should not touch, and remember trap 6: `falloff` is a hard cutoff, and trap
24: hiding a node does not switch its light off.

**The exterior.** Emissive quads or instanced points moving past behind the glass, with
the camera focused on the device, so Filament's DOF turns them into discs. Alternative,
and probably better: **a video plate** — a Cycles-rendered loop or licensed footage of a
night train window — carried in as an external stream texture (§1.4). A video plate is
hardware-decoded, costs almost nothing per pixel, and is photographic by construction.

**The device reflects what is outside.** With a video plate, the same external texture can
be sampled by the device's own material (verified: `samplerExternal` compiles in a lit
material), so the polish shows the lights that are actually passing. Without it, a
rotating/animated `IndirectLight` plus the moving lights does most of the same work.

**Rain and grime on the glass**, as a hand-written material: animated droplet normals with
`refractionMode : screenspace` (compiled, §1.3). Grime matters as much as droplets — clean
glass is one of the things that reads as CG.

**The interior is baked.** Cycles bakes its AO and its bounce into the seats, table and
window frame, so the room has indirect light that Filament will never compute at runtime.

**Contact shadows** under the device, so it sits on the table rather than hovering.

**Physically based camera** — a real aperture, focus on the device, everything beyond it
falling away. This is what makes a phone screen look like a photograph of an object rather
than a render of one.

**Animation:** train sway and vibration as several low-amplitude springs at different
frequencies, never a sine; hand-held micro-motion; the wheel's detents and the click
(`docs/formats.md` §4). Avoid perfect periodicity — it is the tell.

---

## 4. Should the renderer change?

Evidence, then the answer.

| Option | What it would buy here | What it costs |
|---|---|---|
| **Filament 1.72.1 (today)** | Everything in §1: PBR with anisotropy and clear coat, SSR, contact shadows, soft shadows, DOF, fog, physically based camera, custom materials, video textures, live Compose screens | Nothing new. §2's gaps. |
| **Upgrade Filament (1.76/1.77 are current)** | **Multiple directional lights** (1.76), **a second specular lobe for hazy materials** (`secondRoughness`, 1.76), iridescence in lit models (1.77), plus whatever JNI forwarding improved | SceneView 4.32 pins 1.72.1, so this is a dependency override to test; several releases flag **"Recompile Materials"**, so `matc` must be re-matched (`docs/materials.md`). Cheapest lever by far. |
| **Godot 4 as an Android library** | Officially embeddable as a *view* (AAR on Maven Central), MIT | **Its Mobile renderer has no SSR, no SSAO/SSIL and no volumetric fog** — Forward+ is documented as desktop. It would be a step *down* from §1. |
| **Unity as a Library** | A mature editor and lightmapper | **Full-screen rendering only, one runtime instance per process**, plugins need adapting — and this app destroys and rebuilds its room on every navigation, which is exactly what UaaL is worst at. Plus licence terms and tens of MB. |
| **Unreal Engine 5** | The strongest offline-quality features | Not embeddable in an existing app in any supported way (it owns the Activity); Lumen/Nanite are not mobile; APK size and a royalty. |
| **Custom Vulkan with ray tracing** | True reflections on the polished back | Months of work, and it would exclude every device without hardware RT — the app runs down to a Note 8. |

**Recommendation: keep Filament for the pocket player's room, and spend the effort on the
things that actually decide realism** — baked lighting, real materials with imperfection
maps, moving lights, a video plate, a physically based camera, and animation that is not
periodic. Then, if something specific is still missing, **upgrade Filament** before
considering an engine change.

### 4.1 Keeping Filament is not accepting its defaults

Three levers raise the ceiling a long way without an engine change, in the order they are
worth doing:

1. **Bake the light.** Cycles is a path tracer: unlimited bounces, area lights, caustics,
   and it already runs headless here in seconds (§1.5). Baked into a lightmap on UV1
   (verified to compile) plus AO, curvature and normal maps, the static half of a room gets
   *offline* lighting quality that no mobile real-time renderer computes.
2. **Photograph the parts that never move.** The window's exterior can be a path-traced
   Cycles loop or real footage, carried as an external stream texture and *also sampled by
   the device's own material* (verified, §1.3), so the polish reflects the actual passing
   lights. This is the one place where offline rendering can be put on screen directly.
3. **Unbind the binding.** Upgrade to 1.76/1.77 (multiple directional lights, a second
   specular lobe for hazy metal), or rebuild the Apache-2.0 bindings to forward GTAO, SSCT
   and the full TAA set (§2). Each is hours-to-days, not a rewrite.

### 4.2 The one genuinely different approach, if that is ever not enough

**Pre-render the room and composite the live parts into it.** With the camera fixed — which
a device held at life size arguably wants — the whole scene can be path-traced offline at
film quality and played back as a loop, with only the screen, the wheel's response and the
transport rendered live on top. That is strictly better-looking than any real-time renderer
on any phone, because it *is* offline rendering.

What it costs: the camera stops being free (or moves only on rails), every visual variation
has to be rendered in advance, and the assets get large. It also breaks the thing the CD
room is proud of — that the room is real geometry you move through. Worth raising for the
pocket player specifically, where the camera has a reason to be still; wrong for vinyl,
where crate-digging is movement.

**What would reopen this:** vinyl's crate, which draws hundreds of sleeves rather than one
device; or a decision that the rooms need real global illumination that cannot be baked
because the geometry moves.

---

## 5. Where this leaves the plan

`docs/formats.md` §7 is unchanged in shape. What this adds:

- Step 2's spike is smaller than feared: the screen is a `SurfaceTexture` stream, the same
  path video uses, so the question is only how much a Compose redraw costs per frame.
- Add a step before the room: **the bake pipeline** — Cycles AO/normal/curvature into the
  committed model script, since it is proven to run headless here in seconds.
- The rain-glass and LCD materials are the second and third hand-written `.mat` files in
  the project; budget `matc` time for each (`docs/materials.md`).
