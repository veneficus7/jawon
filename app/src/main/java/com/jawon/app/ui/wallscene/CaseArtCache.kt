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
    val hue: Float,
    /**
     * What this artwork was baked from. Compared on every load to decide whether the cached
     * copy still describes the album — see [CaseArtCache.load].
     */
    val signature: String = ""
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
    internal val fonts: CaseArt.Fonts,
    /**
     * What to print on an album's back inlay — its track titles, runtime and format.
     * Injected as a function rather than a DAO so this class stays free of the data layer,
     * the way the rest of the wall-scene package is.
     */
    private val backInlay: suspend (String) -> CaseArt.BackInlay =
        { CaseArt.BackInlay(emptyList()) },
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
        // Cached only counts if it was baked from what the album says *now*.
        //
        // This used to be `if (has(albumId)) return`, keyed on the id alone, which was right
        // while an album's details could never change after import. They can be edited now,
        // and the spine and back inlay have the title, artist and hue printed *into* them —
        // so an album whose art was already on screen would keep its old lettering and its
        // old colour until the entry happened to be evicted or the app restarted.
        //
        // Comparing a signature rather than having the editor call `invalidate` keeps the
        // rule in one place: whoever changes an album, by whatever route, gets fresh art.
        // The wall re-runs this whenever `visibleCases` changes, and a CaseLayout carries
        // every field below, so an edit already reaches here on its own.
        val signature = signatureOf(case)
        if (entries[case.albumId]?.signature == signature) return
        // Read outside the IO block below only because it is already a suspend call on its
        // own dispatcher; the bitmap work still happens off the main thread.
        val inlay = backInlay(case.albumId)
        val artwork = withContext(Dispatchers.IO) {
            val cover = CaseArt.loadCoverSquare(case.coverPath)
            val hue = case.storedHue.takeIf { it != 0f }
                ?: cover?.let { CaseArt.dominantHue(it) }
                ?: case.fallbackHue
            CaseArtwork(
                spine = CaseArt.bakeSpine(
                    case.albumId, case.title, case.artist, hue, fonts, case.wear
                ),
                // Worn on the lid rather than on the picture — see [CaseArt.wearCover] for
                // why the cover and not just the spine carries this.
                cover = cover?.let { CaseArt.wearCover(it, case.albumId, case.wear) },
                back = CaseArt.bakeBack(
                    case.albumId, case.title, case.artist, hue, inlay, fonts
                ),
                hue = hue,
                signature = signature
            )
        }
        // `addLast` only for an album that was not already held, so re-baking an edited
        // album keeps its place in the eviction queue instead of being listed twice.
        if (!has(case.albumId)) insertionOrder.addLast(case.albumId)
        entries[case.albumId] = artwork
    }

    /**
     * Everything the baked artwork depends on.
     *
     * `discFacePath` is deliberately absent: it is handed to the disc's own node straight
     * from the layout and nothing here is baked from it, so including it would throw away
     * three good bitmaps to redraw them identically.
     */
    private fun signatureOf(case: CaseLayout): String = buildString {
        append(case.title); append(' ')
        append(case.artist); append(' ')
        append(case.storedHue); append(' ')
        append(case.coverPath); append(' ')
        // Wear is baked into the spine, so a case played enough to age has to re-bake. Safe
        // to include only because `Patina` quantises it: a continuous value here would
        // re-bake three bitmaps per album on every single write to `PlayEvent`.
        append(case.wear)
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
