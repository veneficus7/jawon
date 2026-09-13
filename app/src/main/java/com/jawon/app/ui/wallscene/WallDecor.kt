package com.jawon.app.ui.wallscene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.google.android.filament.Texture
import io.github.sceneview.SceneScope
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.PlaneNode

/**
 * What hangs on the room's brick: **the owner's own records, framed.**
 *
 * His pick, from four kinds of decor offered: "Your albums, framed". It is the one kind that can
 * never look like set dressing, because it is his library — the prints change as the library
 * does, and they are the records he actually plays.
 *
 * Hung in the band the wall washers light (`RoomLighting.WASHER_AIM_ABOVE_CAP`), spread along the
 * wall at [PITCH_M] with the middle kept clear for the neon sign, so panning the rack walks past
 * a row of framed sleeves the way it walks past shelves.
 *
 * The frames are made of the rack's own timber, which is the point of sharing that texture: a
 * shop frames its wall in the same wood its shelves are built from.
 */
object WallDecor {

    /** The printed sleeve inside the frame. About a 12" sleeve at this room's scale. */
    const val PRINT_M = 0.17f

    /** The frame's rails: their width across the face, and how far they stand off the wall. */
    const val FRAME_T = 0.013f
    const val FRAME_D = 0.014f

    /** How far apart frames hang. One is in shot at a time at the default zoom, as in a shop. */
    const val PITCH_M = 0.62f

    /**
     * How much of the wall's middle is kept clear: the neon sign is 0.24m wide and centred at
     * zero, so nothing may hang nearer the middle than this.
     */
    const val CLEAR_OF_SIGN = 0.30f

    /** At most this many albums are framed; the rest of the wall repeats them. */
    const val MAX_PRINTS = 6

    /** Printed card behind glass: matt, and no more reflective than an ordinary surface. */
    const val PRINT_ROUGHNESS = 0.78f
    const val PRINT_REFLECTANCE = 0.42f

    /** The frame's outer size, including its rails. */
    val OUTER_M = PRINT_M + 2f * FRAME_T

    /**
     * Which albums get framed: **the ones he plays.**
     *
     * `wear` is the patina the wall already wears each case by, so the frames and the cases can
     * never disagree about which records are the favourites. Ties break on the album id, so the
     * wall is the same wall on every launch rather than reshuffling. An album with no cover
     * cannot be printed, so it is skipped rather than framed blank.
     */
    fun chooseAlbums(cases: List<CaseLayout>, limit: Int = MAX_PRINTS): List<CaseLayout> =
        cases.asSequence()
            .filter { !it.coverPath.isNullOrBlank() }
            .distinctBy { it.albumId }
            .sortedWith(compareByDescending<CaseLayout> { it.wear }.thenBy { it.albumId })
            .take(limit)
            .toList()

    /**
     * Where frames hang between [left] and [right]: on a [PITCH_M] grid centred on the rack,
     * skipping the middle where the sign is, and only where a whole frame fits inside the wall.
     */
    fun slots(left: Float, right: Float): List<Float> {
        val reach = maxOf(-left, right)
        val count = (reach / PITCH_M).toInt()
        val half = OUTER_M / 2f
        return (-count..count)
            .map { it * PITCH_M }
            .filter { kotlin.math.abs(it) >= CLEAR_OF_SIGN }
            .filter { it - half >= left && it + half <= right }
    }
}

/** One framed record: which album it is, and the print's texture. */
class FramedAlbum(val albumId: String, val texture: Texture)

/**
 * Hangs [prints] along the wall at [bandY], repeating them if the wall is longer than the list.
 *
 * Every node here takes its own MaterialInstance — a CubeNode and a PlaneNode each destroy the
 * one they are handed — and they share the two textures, which nothing here destroys.
 */
@Composable
fun SceneScope.FramedAlbums(
    prints: List<FramedAlbum>,
    timber: Texture,
    materialLoader: MaterialLoader,
    positions: List<Float>,
    bandY: Float,
    wallZ: Float
) {
    if (prints.isEmpty()) return
    val half = (WallDecor.PRINT_M + WallDecor.FRAME_T) / 2f
    positions.forEachIndexed { index, x ->
        val print = prints[index % prints.size]
        key("frame", index, print.albumId) {
            // The four rails, in the rack's timber.
            listOf(
                Size(WallDecor.OUTER_M, WallDecor.FRAME_T, WallDecor.FRAME_D) to
                    Position(x, bandY + half, wallZ + WallDecor.FRAME_D / 2f),
                Size(WallDecor.OUTER_M, WallDecor.FRAME_T, WallDecor.FRAME_D) to
                    Position(x, bandY - half, wallZ + WallDecor.FRAME_D / 2f),
                Size(WallDecor.FRAME_T, WallDecor.PRINT_M, WallDecor.FRAME_D) to
                    Position(x - half, bandY, wallZ + WallDecor.FRAME_D / 2f),
                Size(WallDecor.FRAME_T, WallDecor.PRINT_M, WallDecor.FRAME_D) to
                    Position(x + half, bandY, wallZ + WallDecor.FRAME_D / 2f)
            ).forEachIndexed { rail, (size, position) ->
                key("rail", rail) {
                    val wood = remember(index, rail, print.albumId) {
                        materialLoader.createTextureInstance(
                            timber,
                            metallic = 0f,
                            roughness = 0.82f,
                            reflectance = WALL_REFLECTANCE
                        )
                    }
                    CubeNode(
                        size = size,
                        position = position,
                        materialInstance = wood,
                        apply = {
                            isTouchable = false
                            isShadowCaster = true
                            isShadowReceiver = true
                        }
                    )
                }
            }
            // The print itself, lit like anything else on this wall.
            val paper = remember(index, print.albumId) {
                materialLoader.createTextureInstance(
                    print.texture,
                    metallic = 0f,
                    roughness = WallDecor.PRINT_ROUGHNESS,
                    reflectance = WallDecor.PRINT_REFLECTANCE
                )
            }
            PlaneNode(
                size = Size(WallDecor.PRINT_M, WallDecor.PRINT_M, 0f),
                normal = Direction(z = 1f),
                materialInstance = paper,
                position = Position(x, bandY, wallZ + 0.002f),
                apply = {
                    isTouchable = false
                    isShadowCaster = false
                    isShadowReceiver = true
                }
            )
        }
    }
}
