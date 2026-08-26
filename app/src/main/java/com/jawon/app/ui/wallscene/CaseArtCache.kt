package com.jawon.app.ui.wallscene

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CaseArtwork(
    val spine: Bitmap,
    val cover: Bitmap?,
    /** Generated from [hue], not uploaded — see [CaseArt.bakeBack]. */
    val back: Bitmap,
    val hue: Float
)

/**
 * Per-album artwork, loaded on demand as cases come into view and capped so panning a large
 * library doesn't grow without bound.
 *
 * Only the CPU-side bitmaps are cached here. The GPU textures belong to the `ImageNode`s
 * that render them — `ImageNode.destroy()` releases its own material instance and texture —
 * so a case leaving the frustum window frees its VRAM regardless of what this holds.
 *
 * Evicted bitmaps are dropped rather than recycled: an `ImageNode` keeps a reference to the
 * bitmap it was built from, and recycling one out from under Filament is not worth the few
 * megabytes it would save.
 */
class CaseArtCache(
    private val fonts: CaseArt.Fonts,
    /**
     * Track titles for an album, for printing on its back inlay. Injected as a function
     * rather than a DAO so this class stays free of the data layer, the way the rest of the
     * wall-scene package is.
     */
    private val trackTitles: suspend (String) -> List<String> = { emptyList() },
    private val capacity: Int = 40
) {
    private val entries = mutableStateMapOf<String, CaseArtwork>()
    private val insertionOrder = ArrayDeque<String>()

    operator fun get(albumId: String): CaseArtwork? = entries[albumId]

    fun has(albumId: String): Boolean = entries.containsKey(albumId)

    /**
     * Decodes the cover, resolves the spine hue and bakes the spine. A hue explicitly stored
     * on the album wins (PLAN.md marks `spineHue` user-overridable); otherwise it comes from
     * the cover, and failing that from the stable per-album fallback.
     */
    suspend fun load(case: CaseLayout) {
        if (has(case.albumId)) return
        // Read outside the IO block below only because it is already a suspend call on its
        // own dispatcher; the bitmap work still happens off the main thread.
        val titles = trackTitles(case.albumId)
        val artwork = withContext(Dispatchers.IO) {
            val cover = CaseArt.loadCoverSquare(case.coverPath)
            val hue = case.storedHue.takeIf { it != 0f }
                ?: cover?.let { CaseArt.dominantHue(it) }
                ?: case.fallbackHue
            CaseArtwork(
                spine = CaseArt.bakeSpine(case.albumId, case.title, case.artist, hue, fonts),
                cover = cover,
                back = CaseArt.bakeBack(
                    case.albumId, case.title, case.artist, hue, titles, fonts
                ),
                hue = hue
            )
        }
        if (!has(case.albumId)) {
            entries[case.albumId] = artwork
            insertionOrder.addLast(case.albumId)
        }
    }

    /** Drops the oldest entries that aren't currently on screen. */
    fun trim(keep: Set<String>) {
        while (entries.size > capacity) {
            val victim = insertionOrder.firstOrNull { it !in keep } ?: return
            insertionOrder.remove(victim)
            entries.remove(victim)
        }
    }
}
