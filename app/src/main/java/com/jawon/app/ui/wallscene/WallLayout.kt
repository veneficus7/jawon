package com.jawon.app.ui.wallscene

import com.jawon.app.data.local.ShelfWithAlbums

/**
 * Wall geometry, in metres — Filament's unit. These are the prototype's exported
 * values converted ×0.1 (the prototype works in units where a 142mm case is 1.42),
 * and per ENGINEERING.md they are starting points for on-device tuning, not fixed
 * requirements.
 */
object WallDimens {
    // Real jewel-case dimensions. The prototype exaggerated the spine to 16mm "for spine
    // legibility"; this is the true 10mm, which costs a little text width on the wall but
    // makes the case read as an actual CD case once it is out in your hand.
    const val CASE_W = 0.010f
    const val CASE_H = 0.142f
    const val CASE_D = 0.125f
    const val SLOT_GAP = 0.0012f
    const val SLOT_PITCH = CASE_W + SLOT_GAP
    const val ROW_PITCH = 0.178f

    const val BOARD_H = 0.011f
    const val BOARD_D = CASE_D + 0.010f

    /** Shelf label plate, sized to sit on the board's front edge. */
    const val PLATE_W = 0.05f
    const val PLATE_H = 0.0095f

    /** How far artwork planes sit proud of the case body, to avoid z-fighting. */
    const val PLATE_LIFT = 0.0004f

    /**
     * How far the now-playing album's case stands out of its slot (PLAN.md §9 #3). Browse
     * away and you can still see what is on — which is the product's own answer to a
     * persistent mini-player bar, an anti-pattern it explicitly rules out.
     */
    const val PROUD_Z = 0.022f

    /** A real CD: 120mm across, 15mm centre hole. */
    const val DISC_RADIUS = 0.060f
    const val DISC_HOLE_RADIUS = 0.0075f

    /**
     * A real jewel case is [CASE_W] overall — the hinged lid closes flush into that, it does
     * not sit on top of it. Splitting the 10mm into a 6mm tray plus the lid is what keeps the
     * opened case exactly as thick as the shelved one it replaces, so nothing appears or
     * vanishes at the swap.
     */
    const val TRAY_T = 0.006f
    /** Front face of the tray — where the disc sits and where the lid hinges. */
    const val TRAY_FRONT_Z = -CASE_W / 2f + TRAY_T

    /** Wall plane sits just behind the back face of a shelved case. */
    const val WALL_Z = -CASE_D / 2f - 0.006f

    /** Slack around the content so the wall's own edges stay off-screen at full pan. */
    const val WALL_MARGIN = 0.35f

    /**
     * Filament derives vertical FOV from focal length against a 35mm frame:
     * `tan(fovY/2) = 12 / focalMm`. 33mm reproduces the prototype's 40° vertical FOV.
     * Focal length (rather than [CameraNode.setProjection]) is the stable knob — SceneView
     * re-runs `updateProjection()` on every surface resize, which would discard a
     * directly-set FOV.
     */
    const val FOCAL_LENGTH_MM = 33.0
    const val TAN_HALF_FOV = 12.0f / FOCAL_LENGTH_MM.toFloat()

    /**
     * The prototype's 0.28m was tuned for a landscape browser; on a portrait phone that
     * frames barely one row. This shows ~2 rows and ~10 spines. Main tuning knob.
     */
    const val CAMERA_Z = 0.5f
}

data class CaseLayout(
    val albumId: String,
    val title: String,
    val artist: String,
    /** Raw `Album.spineHue`; `0f` means unset, in which case the cover decides. */
    val storedHue: Float,
    /** Used when there is neither a stored hue nor a cover to derive one from. */
    val fallbackHue: Float,
    val coverPath: String?,
    /** The second image M1 has been collecting all along — shown on the disc itself. */
    val discFacePath: String?,
    val row: Int,
    val col: Int,
    val x: Float,
    val y: Float
)

data class RowLayout(
    val shelfId: String,
    val name: String,
    val accentHue: Float,
    val row: Int,
    val centerY: Float,
    val boardY: Float,
    /** Where the label plate sits along the board — the start of the shelf's run. */
    val plateX: Float
)

/**
 * The whole wall, derived from the library. Pure arithmetic — no Android or Filament
 * types — so the positioning maths stays reviewable on its own.
 */
data class WallLayout(
    val cases: List<CaseLayout> = emptyList(),
    val rows: List<RowLayout> = emptyList(),
    val wallWidth: Float = WallDimens.WALL_MARGIN * 2f,
    val wallHeight: Float = WallDimens.WALL_MARGIN * 2f,
    val minPanX: Float = 0f,
    val maxPanX: Float = 0f,
    val minPanY: Float = 0f,
    val maxPanY: Float = 0f
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    companion object {
        /**
         * One shelf = one row of the wall, ordered by `Shelf.order`; albums run along the
         * row in `slotIndex` order (the DAO already sorts them, so the list index is used
         * directly — that keeps a shelf packed even if stored slot indices have gaps).
         */
        fun from(shelves: List<ShelfWithAlbums>): WallLayout {
            if (shelves.isEmpty()) return WallLayout()

            val rowCount = shelves.size
            val maxCols = shelves.maxOf { it.albums.size }.coerceAtLeast(1)

            fun rowCenterY(row: Int) = ((rowCount - 1) / 2f - row) * WallDimens.ROW_PITCH
            fun colX(col: Int) = (col - (maxCols - 1) / 2f) * WallDimens.SLOT_PITCH

            val rows = shelves.mapIndexed { row, shelf ->
                RowLayout(
                    shelfId = shelf.shelf.id,
                    name = shelf.shelf.name,
                    accentHue = shelf.shelf.accentHue,
                    row = row,
                    centerY = rowCenterY(row),
                    boardY = rowCenterY(row) - WallDimens.CASE_H / 2f - WallDimens.BOARD_H / 2f,
                    // Like a record-shop divider: at the start of this shelf's run, or at the
                    // wall's left edge when the shelf is still empty.
                    plateX = colX(0) + WallDimens.PLATE_W / 2f - WallDimens.SLOT_PITCH / 2f
                )
            }

            val cases = shelves.flatMapIndexed { row, shelf ->
                shelf.albums.mapIndexed { col, album ->
                    CaseLayout(
                        albumId = album.id,
                        title = album.title,
                        artist = album.artist,
                        storedHue = album.spineHue,
                        fallbackHue = stableHue(album.id),
                        coverPath = album.caseCoverUri,
                        discFacePath = album.discFaceUri,
                        row = row,
                        col = col,
                        x = colX(col),
                        y = rowCenterY(row)
                    )
                }
            }

            // ENGINEERING.md's locked "defence against infinite scroll": the scrollable extent is
            // occupied slots plus exactly one empty slot of margin, so the user physically
            // cannot pan past the last album.
            val panXRange = if (cases.isEmpty()) {
                0f to 0f
            } else {
                (cases.minOf { it.x } - WallDimens.SLOT_PITCH) to
                    (cases.maxOf { it.x } + WallDimens.SLOT_PITCH)
            }

            return WallLayout(
                cases = cases,
                rows = rows,
                wallWidth = maxCols * WallDimens.SLOT_PITCH + WallDimens.WALL_MARGIN * 2f,
                wallHeight = rowCount * WallDimens.ROW_PITCH + WallDimens.WALL_MARGIN * 2f,
                minPanX = panXRange.first,
                maxPanX = panXRange.second,
                minPanY = rowCenterY(rowCount - 1) - WallDimens.ROW_PITCH,
                maxPanY = rowCenterY(0) + WallDimens.ROW_PITCH
            )
        }

        /**
         * Last resort when an album has neither a stored `spineHue` (M1 only ever writes
         * `0f`) nor a cover to derive one from — a stable per-album hue beats a wall of
         * identically-coloured cases.
         */
        private fun stableHue(albumId: String): Float {
            val h = albumId.fold(0) { acc, c -> acc * 31 + c.code }
            return ((h % 360) + 360) % 360 / 360f
        }
    }
}
