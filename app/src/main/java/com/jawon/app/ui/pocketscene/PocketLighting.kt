package com.jawon.app.ui.pocketscene

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Texture
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.rotation
import io.github.sceneview.environment.Environment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The pocket player's room: **a walnut desk at night, under a desk lamp** — the device lying on
 * it, a pair of headphones plugged into its top.
 *
 * The room is in three pieces, and they have to agree:
 *
 * - **The desk**, `models/pocket_desk.glb`: the wood with the lamp's light, the room's light and
 *   the device's own shadow already *baked into it* by Cycles (`build_pocket_desk()`), drawn
 *   unlit. A path-traced contact shadow and a soft pool of lamplight are the two things that
 *   make an object look like it is lying on something, and neither is affordable live here.
 * - **The lamp**, a spot light the room places beside the device ([LAMP_POSITION] …): the
 *   device's own light, live, so the wheel's coat and the anodising answer it.
 * - **The room**, this environment: what every metal and glass surface on the device reflects
 *   — the lamp's shade, the ceiling its open top lights, the desk, a window across the room.
 *
 * **`tools/models/pocket_room.py` is the other copy of all three**, read by the bake and by
 * `tools/pocket-preview/`; every constant here that it also has must be the same number there,
 * or the device is lit by one room and lies on a desk lit by another. [radiance] is the same
 * function, term for term — `PocketLightingTest` pins values the Python produces.
 *
 * **Directions are in the device's frame**: +X right, +Y up the device, +Z out of its face,
 * which with the device lying on the desk is *up off the desk*. The cubemap is built already
 * turned by the device's pose ([devicePose]), so the room the device reflects is the room its
 * desk was baked in; the phone's tilt is applied on top, as a rotation of the light.
 *
 * **Why the device is in the lamp's falloff and not its pool** is in `pocket_room.py`: the wheel
 * is black plastic at 7% albedo and walnut is 6%, so any light that makes the wood a readable
 * brown makes the wheel the same grey — the "white wheel" the owner turned down on 2026-09-22.
 */
object PocketLighting {

    /** Cubemap face size. The glass at roughness 0.03 reads the sharpest level. */
    const val CUBEMAP_PX = 256

    /** How hard the room lights the device — the world's strength the desk was baked at. */
    const val AMBIENT_INTENSITY = 2_600f

    // ---- the lamp ---------------------------------------------------------------------------

    /** Up and to the left of the device, a hand-span above the desk. Metres, device frame. */
    val LAMP_POSITION = floatArrayOf(-0.15f, 0.20f, 0.34f)

    /** Where its axis meets the desk: up-left of the device, so the device is in the falloff. */
    val LAMP_AIM = floatArrayOf(-0.11f, 0.14f, -0.005f)

    /** At SceneView's sunny-16 exposure, as the desk was baked. */
    const val LAMP_CANDELA = 15_000f

    val LAMP_COLOUR = floatArrayOf(1.0f, 0.82f, 0.60f)

    /**
     * The cone, as half-angles. The bake's lamp is Cycles' 64° spot with its whole cone blended,
     * which falls off as a smoothstep in the cosine; Filament's falls off as the square of a
     * linear ramp. These two angles are the Filament cone that best matches it across the
     * device — within 4% of the lamp's light from the plug to the wheel, a few per cent
     * brighter at the foot, where the room's light is most of it anyway.
     */
    const val LAMP_CONE_INNER_RAD = 0.2269f   // 13°
    const val LAMP_CONE_OUTER_RAD = 0.6109f   // 35°

    /** Past the device by a long way: Filament's falloff is a hard cutoff (trap 6). */
    const val LAMP_FALLOFF_M = 3f

    // ---- the room as light ------------------------------------------------------------------

    private val VOID = floatArrayOf(0.0055f, 0.0048f, 0.0042f)

    /** The desk, as the polished sides see it at a grazing angle: warm, brighter toward the lamp. */
    private val DESK_SEEN = floatArrayOf(0.62f, 0.37f, 0.22f)

    /** The lamp itself: its shade's mouth, and the glow round it. */
    private const val LAMP_DISC = 60f
    private const val LAMP_DISC_RAD = 0.045f
    private const val LAMP_GLOW = 1.2f
    private const val LAMP_GLOW_RAD = 0.30f

    /**
     * **The ceiling, lit from the lamp's open top.** The face reflects a patch 11–23° from
     * straight up, off to the right (`tools/pocket-preview/frame.py --probe`), so this glow —
     * brightest over the lamp, falling away to the right — is what fills the face where the lamp
     * has fallen away, and keeps it a gradient rather than a cliff.
     */
    private val CEILING = floatArrayOf(2.5f, 2.0f, 1.5f)
    private val CEILING_DIR = floatArrayOf(-0.19f, 0.24f, 0.95f)
    private const val CEILING_RAD = 0.55f

    /** A window across the room: a cool band low on the far wall, for the polished edges. */
    private val WINDOW = floatArrayOf(0.10f, 0.14f, 0.22f)
    private const val WINDOW_AZ = -2.3f
    private const val WINDOW_HALF_AZ = 0.5f
    private const val WINDOW_Z = 0.10f
    private const val WINDOW_HALF_Z = 0.18f

    private val LAMP_DIR = unit(LAMP_POSITION)
    private val LAMP_TOWARD = unit(floatArrayOf(LAMP_DIR[0], LAMP_DIR[1]))
    private val CEILING_UNIT = unit(CEILING_DIR)

    /**
     * The device's pose as SceneView applies it: `Rotation(x = pitch, y = yaw)` through the very
     * call `Node.rotation` makes (`Quaternion.fromEuler`), as a column-major 3×3 taking the
     * device's frame into the world's. Not written out by hand: the order of the two turns is
     * kotlin-math's, `Ry · Rx`, and the preview had them the other way round for a day.
     */
    fun devicePose(pitchDeg: Float, yawDeg: Float): FloatArray {
        val m = rotation(Quaternion.fromEuler(Float3(pitchDeg, yawDeg, 0f)))
        return floatArrayOf(m.x.x, m.x.y, m.x.z, m.y.x, m.y.y, m.y.z, m.z.x, m.z.y, m.z.z)
    }

    /**
     * The room as an image-based light, turned by [pose] (column-major, device to world) so its
     * directions land where the device's own frame says they are.
     */
    fun createEnvironment(
        engine: Engine,
        pose: FloatArray,
        size: Int = CUBEMAP_PX,
        prefilter: (Texture) -> Texture
    ): Environment {
        val cubemap = buildCubemap(engine, pose, size)
        val indirectLight = IndirectLight.Builder()
            .reflections(prefilter(cubemap))
            .intensity(AMBIENT_INTENSITY)
            .build(engine)
        return Environment(indirectLight = indirectLight, skybox = null)
    }

    /**
     * The room's radiance toward one direction, in the device's frame — `pocket_room.radiance`,
     * term for term. Pure, so the placement can be tested against the Python.
     */
    fun radiance(dx: Float, dy: Float, dz: Float, out: FloatArray) {
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        val x = dx / len
        val y = dy / len
        val z = dz / len
        for (c in 0..2) out[c] = VOID[c]

        // The desk, below: warm, and brighter on the lamp's side.
        val below = (-z * 4f).coerceIn(0f, 1f)
        val toward = (0.5f + 0.5f * (x * LAMP_TOWARD[0] + y * LAMP_TOWARD[1])).coerceIn(0f, 1f)
        val desk = below * (0.35f + 0.65f * toward)
        for (c in 0..2) out[c] += DESK_SEEN[c] * desk

        // The lamp.
        val a = acos((x * LAMP_DIR[0] + y * LAMP_DIR[1] + z * LAMP_DIR[2]).coerceIn(-1f, 1f))
        val lamp = LAMP_DISC * gauss(a, LAMP_DISC_RAD) + LAMP_GLOW * gauss(a, LAMP_GLOW_RAD)
        for (c in 0..2) out[c] += LAMP_COLOUR[c] * lamp

        // The ceiling it lights.
        val cAngle = acos((x * CEILING_UNIT[0] + y * CEILING_UNIT[1] + z * CEILING_UNIT[2]).coerceIn(-1f, 1f))
        val ceiling = gauss(cAngle, CEILING_RAD) * (z * 3f).coerceIn(0f, 1f)
        for (c in 0..2) out[c] += CEILING[c] * ceiling

        // The window.
        val band = (1f - abs(z - WINDOW_Z) / WINDOW_HALF_Z).coerceIn(0f, 1f)
        val across = (1f - abs(atan2(y, x) - WINDOW_AZ) / WINDOW_HALF_AZ).coerceIn(0f, 1f)
        for (c in 0..2) out[c] += WINDOW[c] * band * across
    }

    private fun buildCubemap(engine: Engine, pose: FloatArray, size: Int): Texture {
        // RGBA float: the prefilter rejects an LDR single-level cubemap, and the lamp needs
        // headroom far above 1.0 or it is a pale patch rather than a light.
        val faceBytes = size * size * 4 * Float.SIZE_BYTES
        val buffer = ByteBuffer.allocateDirect(faceBytes * 6).order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        val dir = FloatArray(3)
        val rgb = FloatArray(3)

        for (face in 0 until 6) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val u = (x + 0.5f) / size * 2f - 1f
                    val v = (y + 0.5f) / size * 2f - 1f
                    faceDirection(face, u, v, dir)
                    // World into the device's frame: the pose's transpose.
                    radiance(
                        pose[0] * dir[0] + pose[1] * dir[1] + pose[2] * dir[2],
                        pose[3] * dir[0] + pose[4] * dir[1] + pose[5] * dir[2],
                        pose[6] * dir[0] + pose[7] * dir[1] + pose[8] * dir[2],
                        rgb
                    )
                    floats.put(rgb[0]); floats.put(rgb[1]); floats.put(rgb[2]); floats.put(1f)
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
            .usage(
                Texture.Usage.DEFAULT or
                    Texture.Usage.GEN_MIPMAPPABLE or
                    Texture.Usage.COLOR_ATTACHMENT
            )
            .build(engine)
        texture.setImage(
            engine,
            0,
            Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.FLOAT),
            IntArray(6) { it * faceBytes }
        )
        texture.generateMipmaps(engine)
        return texture
    }

    /** As `RoomLighting.faceDirection` — written into [out] rather than allocated per texel. */
    private fun faceDirection(face: Int, u: Float, v: Float, out: FloatArray) {
        when (face) {
            0 -> { out[0] = 1f; out[1] = -v; out[2] = -u }
            1 -> { out[0] = -1f; out[1] = -v; out[2] = u }
            2 -> { out[0] = u; out[1] = 1f; out[2] = v }
            3 -> { out[0] = u; out[1] = -1f; out[2] = -v }
            4 -> { out[0] = u; out[1] = -v; out[2] = 1f }
            else -> { out[0] = -u; out[1] = -v; out[2] = -1f }
        }
    }

    private fun gauss(angle: Float, radius: Float): Float = exp(-(angle / radius) * (angle / radius))

    private fun unit(v: FloatArray): FloatArray {
        var sum = 0f
        for (c in v) sum += c * c
        val len = sqrt(sum)
        return FloatArray(v.size) { v[it] / len }
    }
}
