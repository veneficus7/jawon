package com.jawon.app.ui.wallscene

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Texture
import io.github.sceneview.environment.Environment
import io.github.sceneview.math.Position
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * The Midnight Shop's light rig — DESIGN.md §1: "A record shop after closing. One warm lamp
 * over the racks, deep shadow everywhere else."
 *
 * Kept in one file, free of Compose and of Filament lifecycle beyond building the
 * [IndirectLight], for the same reason [OpenSequence] and [Scratch] are: the room's look
 * lives in these numbers and should be reviewable without a device attached.
 *
 * Positions are the prototype's (`javon-shelf-prototype.html`, the lights block) converted
 * ×0.1 — the prototype works in decimetres, where a 142mm case is 1.42 and here it is 0.142.
 *
 * **Intensities are NOT the prototype's.** three.js takes arbitrary multipliers; Filament is
 * physically based and wants candela against a scene roughly 0.4m across, so its 2.2 and 0.7
 * mean nothing here. These are physical estimates, found on the device — the same lesson
 * M8's `emissive` taught, where the prototype's 1.0 turned out to be enormous.
 */
object RoomLighting {

    // DESIGN.md §3's scene palette, as linear-ish RGB in 0..1.
    /** Key light (lamp) — `#FFD2A0`. */
    val KEY_COLOR = floatArrayOf(1.0f, 0.824f, 0.627f)

    /** Rim / bounce — `#F0A93C`. */
    val RIM_COLOR = floatArrayOf(0.941f, 0.663f, 0.235f)

    /** Ambient fill — `#40312A`. */
    val AMBIENT_COLOR = floatArrayOf(0.251f, 0.192f, 0.165f)

    /** Fog / background — `#120E0B`. What the room falls off to. */
    val VOID_COLOR = floatArrayOf(0.071f, 0.055f, 0.043f)

    /** The lamp, above and to the right, aimed back at the wall. */
    val KEY_POSITION = Position(0.16f, 0.42f, 0.34f)

    /** The coloured bounce, low and to the left. */
    val RIM_POSITION = Position(-0.26f, -0.12f, 0.24f)

    /**
     * The direction the key light points — normalised `-KEY_POSITION`, i.e. from the lamp
     * back down at the wall's centre.
     *
     * As a plain `FloatArray` because its consumer is [RoomAtmosphere]'s screen-space cone
     * tracing, which wants three floats rather than SceneView's `Direction`. The light node
     * itself derives the same vector through SceneView's `normalize`; if either moves, both
     * must.
     */
    val KEY_DIRECTION: FloatArray = floatArrayOf(
        -KEY_POSITION.x, -KEY_POSITION.y, -KEY_POSITION.z
    ).let { d ->
        val len = length(d)
        floatArrayOf(d[0] / len, d[1] / len, d[2] / len)
    }

    /**
     * Candela. A domestic lamp is a few hundred; these are close to their subject, so they
     * do not need to be large. Main tuning knobs — expect to move them on device.
     */
    const val KEY_CANDELA = 500f
    const val RIM_CANDELA = 30f

    /**
     * Cone half-angles in radians.
     *
     * Much wider than the prototype's PI/5. The prototype framed a handful of cases in a
     * landscape browser; our wall grows with the library, and at PI/5 exactly one case was
     * lit and the rest of the shelf fell into pure black — unbrowsable, whatever it did for
     * the mood. DESIGN.md wants deep shadow, not an unreadable wall.
     */
    const val KEY_CONE_INNER = 0.55f
    const val KEY_CONE_OUTER = 1.15f

    /** Metres at which each light has fallen off to nothing. */
    const val KEY_FALLOFF = 6.0f
    const val RIM_FALLOFF = 1.8f

    /**
     * The key light's shadow map, in texels per side.
     *
     * Filament's default is 1024, which is what `CaseSlots` was working around when it
     * turned on screen-space contact shadows and noted the map was too coarse to resolve a
     * contact line. The map has to cover the whole lit wall, so a 10mm spine occupies very
     * few texels of it.
     *
     * 2048 rather than 4096: this is a depth texture, so each step doubles memory as well
     * as texels, and 4096² is ~67MB of it for one light. 2048 quadruples the default's
     * texel count for ~17MB, which is the honest trade on a phone. If contact lines still
     * read as coarse after this pass, 4096 is the knob — but measure it, and expect it to
     * become an M10 tier.
     */
    const val KEY_SHADOW_MAP_PX = 2048

    /**
     * Emitter radius in metres, driving PCSS's contact hardening. A shop spot is a small
     * source, so shadows stay tight where objects touch and soften only gradually — which
     * is the point of the effect. Raise it for a softer, more diffuse room.
     */
    const val KEY_BULB_RADIUS = 0.025f

    /**
     * Depth range hints, in metres from the lamp. The lamp sits ~0.56m from the wall's
     * centre and the whole room fits inside a 1.5m sphere around it; bounding the range
     * this tightly is most of what buys usable depth precision at this scale.
     */
    const val KEY_SHADOW_NEAR = 0.05f
    const val KEY_SHADOW_FAR = 1.6f

    /**
     * Shadow acne bias. Filament's defaults are sized for metre-scale geometry and at this
     * scale they push a shadow clean off the object casting it — the lid's shadow detaches
     * from the lid. Scaled down to match millimetre features.
     */
    const val KEY_SHADOW_CONSTANT_BIAS = 0.001f
    const val KEY_SHADOW_NORMAL_BIAS = 0.01f

    /**
     * Lux for the image-based ambient.
     *
     * This has to carry most of the room: the wall is `#1A1410` and the case bodies
     * `#2A2622`, deliberately near-black, so they need real light before they read at all.
     */
    const val AMBIENT_INTENSITY = 40_000f

    /**
     * Builds the room's image-based lighting — **with no asset files at all.**
     *
     * SceneView can load an IBL from a `.hdr` or `.ktx`, but that would mean shipping a
     * baked environment map, and ENGINEERING.md's geometry rule ("procedural, no downloaded
     * models") is the same instinct. `createEnvironment` takes an [IndirectLight] we
     * construct ourselves, so instead:
     *
     * - **Reflections** come from a cubemap generated in code by [buildRoomCubemap] — warm
     *   glow above, near-black below — prefiltered by Filament into a roughness mip chain.
     *   This is what finally gives the case's clear lid and the disc something to reflect;
     *   until now the lid rendered as a flat white strip because there was nothing there.
     * - **Ambient** comes from spherical harmonics, authored rather than baked. Only the
     *   constant band and a vertical term are used, which is all a single dim fill needs.
     *
     * No skybox: the room is a lit wall standing in blackness, and a skybox would paint
     * over that blackness.
     */
    fun createEnvironment(engine: Engine, prefilter: (Texture) -> Texture): Environment {
        val cubemap = buildRoomCubemap(engine)
        val reflections = prefilter(cubemap)
        val indirectLight = IndirectLight.Builder()
            .reflections(reflections)
            .irradiance(SH_BANDS, ambientSphericalHarmonics())
            .intensity(AMBIENT_INTENSITY)
            .build(engine)
        return Environment(indirectLight = indirectLight, skybox = null)
    }

    private const val SH_BANDS = 3

    /**
     * Cubemap face size.
     *
     * Was 64, which is where the room's reflections fell down: the specular prefilter turns
     * this into a roughness mip chain, and 64px leaves so few levels that anything glossy —
     * the case's clear lid at roughness 0.05, the disc at 0.22 — reflected a smeared blob
     * instead of a room. Raising it four-fold per axis gives the chain something to work
     * with.
     *
     * The cost is a one-off 6 × 256² = 393k texels of CPU fill at scene creation, sixteen
     * times what 64 asked for, which is why [buildRoomCubemap]'s inner loop no longer
     * allocates per texel. It stays on the calling thread deliberately: every Filament call
     * around it — the texture build, `setImage`, and the prefilter's GPU pass — belongs to
     * the engine thread, so moving the arithmetic away would buy a few milliseconds and
     * hand back a threading bug.
     *
     * Sharp reflections now come from [RoomAtmosphere]'s screen-space reflections; this is
     * the fallback for rough surfaces and for whatever SSR's rays miss. If the lid still
     * reads flat, this is the knob — 512 is affordable — but check SSR first.
     */
    private const val CUBEMAP_PX = 256

    /**
     * How bright the reflected lamp and the reflected void are, in the cubemap's own HDR
     * range. The lamp sits above 1.0 deliberately — that headroom is what makes it read as
     * a light source in a reflection rather than as a pale grey patch.
     */
    private const val LAMP_LEVEL = 1.2f
    private const val VOID_LEVEL = 0.15f

    /**
     * Irradiance as spherical harmonics: 3 bands, 9 coefficients, RGB each.
     *
     * Hand-authored rather than convolved from an environment — a single dim warm fill has
     * no detail worth baking. Index 0 is the constant term (the ambient colour itself) and
     * index 1 is the vertical one, biased positive so the fill arrives from above like the
     * lamp does. The rest stay zero.
     *
     * The sign convention on the vertical term is worth checking on device rather than
     * trusting; if the room looks lit from the floor, flip it.
     */
    private fun ambientSphericalHarmonics(): FloatArray {
        val sh = FloatArray(SH_BANDS * SH_BANDS * 3)
        for (c in 0..2) {
            sh[c] = AMBIENT_COLOR[c]
            sh[3 + c] = AMBIENT_COLOR[c] * 0.35f
        }
        return sh
    }

    /**
     * A 64² cubemap of the room: warm light pooling above, falling to near-black below.
     *
     * Face order is Filament's: +X, -X, +Y, -Y, +Z, -Z. Each texel's direction is
     * reconstructed from its face and its position on that face, and the colour is a
     * function of how far up that direction points — which is the whole of the shop's
     * lighting story, and enough for a reflection to read as a room rather than a colour.
     */
    private fun buildRoomCubemap(engine: Engine): Texture {
        val size = CUBEMAP_PX
        // RGBA float. IBL wants HDR — Filament's specular prefilter rejects an LDR
        // single-level cubemap outright, and the lamp needs headroom above 1.0 anyway if it
        // is going to read as a light source rather than as a pale patch.
        val floatsPerTexel = 4
        val faceBytes = size * size * floatsPerTexel * Float.SIZE_BYTES
        val buffer = ByteBuffer.allocateDirect(faceBytes * 6).order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()

        // Hoisted: these are six multiplications, not 393k of them.
        val void = FloatArray(3) { VOID_COLOR[it] * VOID_LEVEL }
        val lamp = FloatArray(3) { KEY_COLOR[it] * LAMP_LEVEL }
        // Reused across every texel. `faceDirection` used to return a fresh array per
        // texel, which was survivable at 64px and is 393k throwaway allocations at 256.
        val dir = FloatArray(3)

        for (face in 0 until 6) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    // Face-local coordinates in -1..1, then the direction they point.
                    val u = (x + 0.5f) / size * 2f - 1f
                    val v = (y + 0.5f) / size * 2f - 1f
                    faceDirection(face, u, v, dir)
                    val up = dir[1] / length(dir)

                    // Everything above the horizon warms toward the lamp; everything below
                    // falls into the void. Raised to a power so the lamp stays a pool of
                    // light rather than washing the whole upper hemisphere.
                    val t = ((up + 1f) * 0.5f).coerceIn(0f, 1f).pow(2.2f)
                    floats.put(void[0] + (lamp[0] - void[0]) * t)
                    floats.put(void[1] + (lamp[1] - void[1]) * t)
                    floats.put(void[2] + (lamp[2] - void[2]) * t)
                    floats.put(1f)
                }
            }
        }

        val levels = 32 - Integer.numberOfLeadingZeros(size)
        val texture = Texture.Builder()
            .width(size)
            .height(size)
            .levels(levels)
            .format(Texture.InternalFormat.RGBA16F)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            // The prefilter renders through this, and needs the mip chain it walks.
            .usage(
                Texture.Usage.DEFAULT or
                    Texture.Usage.GEN_MIPMAPPABLE or
                    Texture.Usage.COLOR_ATTACHMENT
            )
            .build(engine)

        val faceOffsets = IntArray(6) { it * faceBytes }
        texture.setImage(
            engine,
            0,
            Texture.PixelBufferDescriptor(
                buffer,
                Texture.Format.RGBA,
                Texture.Type.FLOAT
            ),
            faceOffsets
        )
        texture.generateMipmaps(engine)
        return texture
    }

    /**
     * The unit-ish direction a texel on [face] at face-local ([u], [v]) points, written
     * into [out] rather than returned — see the allocation note in [CUBEMAP_PX].
     */
    private fun faceDirection(face: Int, u: Float, v: Float, out: FloatArray) {
        when (face) {
            0 -> { out[0] = 1f; out[1] = -v; out[2] = -u }    // +X
            1 -> { out[0] = -1f; out[1] = -v; out[2] = u }    // -X
            2 -> { out[0] = u; out[1] = 1f; out[2] = v }      // +Y
            3 -> { out[0] = u; out[1] = -1f; out[2] = -v }    // -Y
            4 -> { out[0] = u; out[1] = -v; out[2] = 1f }     // +Z
            else -> { out[0] = -u; out[1] = -v; out[2] = -1f } // -Z
        }
    }

    private fun length(v: FloatArray): Float =
        kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
}
