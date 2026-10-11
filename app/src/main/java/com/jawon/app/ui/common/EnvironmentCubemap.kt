package com.jawon.app.ui.common

import com.google.android.filament.Engine
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * A room to reflect, painted from a function of direction.
 *
 * Every room here lights itself the same way: no photograph of anywhere, but a cubemap written
 * out pixel by pixel from a description of what is in each direction — a lamp there, a window
 * over there, dark everywhere else — which Filament then prefilters into reflections. The CD
 * room and the pocket player each carry their own copy of this loop, written when there was one
 * room and then two; the vinyl room is the third, and the loop is here now for it and for
 * whatever comes after. (Theirs are left where they are: the pocket player's is pinned, number
 * for number, to the Python its preview renders with.)
 */
object EnvironmentCubemap {

    /**
     * A cubemap [size] pixels to a face, where [radiance] writes the light arriving from the unit
     * direction (x, y, z) into its last argument as linear RGB — above 1 for anything that is a
     * light rather than a surface.
     */
    fun build(engine: Engine, size: Int, radiance: (Float, Float, Float, FloatArray) -> Unit): Texture {
        // RGBA float: the prefilter rejects an LDR single-level cubemap, and a lamp needs headroom
        // above 1.0 or it is a pale patch rather than a light.
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
                    val length = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
                    radiance(dir[0] / length, dir[1] / length, dir[2] / length, rgb)
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
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE or Texture.Usage.COLOR_ATTACHMENT)
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

    /** Filament's face order and orientation: +x, −x, +y, −y, +z, −z. */
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
}
