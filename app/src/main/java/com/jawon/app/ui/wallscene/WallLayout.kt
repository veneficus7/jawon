package com.jawon.app.ui.wallscene

import com.jawon.app.data.local.ShelfWithAlbums
import kotlin.math.abs

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

    /**
     * Every album gets a bay it can turn a full circle inside.
     *
     * The rack used to be packed: a 10mm case in an 11.2mm slot, spine out, like a shop.
     * Cases now levitate and present themselves, and a case turning about its own axis
     * sweeps a circle whose diameter is its *diagonal* in plan —
     * `hypot(CASE_W, CASE_D)` = 125.4mm — not its 10mm thickness. At the old pitch a case
     * needed eleven neighbours' worth of clearance and drove straight through them.
     *
     * 150mm leaves ~25mm of air between the swept circles of adjacent albums, so nothing
     * ever intersects at any angle. The wall is a gallery now rather than a rack: fewer
     * albums per screen, each one an object rather than a label.
     */
    const val SLOT_SWEEP = 0.1254f          // hypot(CASE_W, CASE_D), the turning circle
    const val SLOT_GAP = 0.0246f
    const val SLOT_PITCH = SLOT_SWEEP + SLOT_GAP

    /**
     * Rows only have to clear the case's height — turning about the vertical axis does not
     * change it — so this grew far less than the pitch did. The extra over 142mm is where
     * the underlight beam lives.
     */
    const val ROW_PITCH = 0.205f

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
     * Pulled back from 0.5m because the bays are thirteen times wider than the old slots.
     *
     * At 0.5m a 150mm bay filled the frame with a single album and its neighbours were off
     * screen entirely, which is not browsing. At 0.78m a portrait phone frames roughly one
     * album centred with the next peeking in either side, and about three rows — close
     * enough to read the artwork, far enough that panning still feels like moving along a
     * shelf rather than scrubbing a filmstrip. Main tuning knob.
     */
    const val CAMERA_Z = 0.78f
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
    val maxPanY: Float = 0f,

    /**
     * Whether this layout came from the database, as opposed to being the placeholder the
     * flow starts on.
     *
     * **These two were indistinguishable, and conflating them cost a bug and a feature.**
     * The state flow starts on `WallLayout()`, so for the first frames after the splash an
     * app with a full library looks exactly like an app with an empty one. The wall backing
     * was drawn for both, which is what flashed a lit brown panel on launch; the fix at the
     * time was to stop drawing it whenever `rows` was empty. That killed the flash and also
     * guaranteed that a genuinely empty library could never render anything either — which
     * is why the first screen a new user sees is a black void with one line of text.
     *
     * So: "nothing to draw yet" is a different state from "nothing in the library", and this
     * is what separates them.
     */
    val loaded: Boolean = false
) {
    /**
     * The library has loaded and there is nothing on the shelves — the new-user state, and
     * per `PLAN.md` §4 the most important screen in the app.
     *
     * There used to be an `isEmpty` here, defined as `rows.isEmpty()`, and it is deliberately
     * gone rather than kept alongside this. An empty library now gets a shelf to look at, so
     * that property would answer *false* for the emptiest possible library — a name that
     * means the opposite of what it says is worse than no name at all.
     *
     * Note this is about *cases*, not rows: a user who has made shelves but put no albums on
     * them is in the same position as one who has just installed the app.
     */
    val isWaiting: Boolean get() = loaded && cases.isEmpty()

    /**
     * The album nearest a point on the wall — what the magnet centres on.
     *
     * Browsing settles *onto* a record rather than wherever friction ran out. The wall is a
     * gallery of objects at a 150mm pitch, not a continuous strip, so coming to rest halfway
     * between two albums is never a position anyone meant to be in.
     *
     * **One album, both axes.** This replaced a pair of per-axis helpers that snapped X to
     * the nearest column and Y to the nearest row independently — two answers that need not
     * describe the same record, and on a wall whose shelves hold different numbers of albums
     * usually do not. The vertical half was worse than merely inconsistent: it centred the
     * *row*, so the album standing in that row still sat low in frame, which is what
     * "snapping works horizontally only" was describing.
     *
     * Snapping to a real case rather than to a multiple of the pitch matters at the ends of a
     * row: the bounds allow a full empty slot of margin either side, and a grid would happily
     * centre that gap.
     *
     * Distances are normalised by each axis's own pitch before comparing, because rows are
     * 205mm apart and slots 150mm — comparing raw metres would bias the magnet toward
     * vertical neighbours for no reason a user could see.
     *
     * Null when there is nothing to snap to, so an empty wall stays where it is rather than
     * yanking to the origin.
     */
    fun nearestCase(targetX: Float, targetY: Float): CaseLayout? = cases.minByOrNull {
        val dx = (it.x - targetX) / WallDimens.SLOT_PITCH
        val dy = (it.y - targetY) / WallDimens.ROW_PITCH
        dx * dx + dy * dy
    }

    companion object {
        /**
         * One shelf = one row of the wall, ordered by `Shelf.order`; albums run along the
         * row in `slotIndex` order (the DAO already sorts them, so the list index is used
         * directly — that keeps a shelf packed even if stored slot indices have gaps).
         */
        /**
         * The shelf a brand-new library gets, so the room has something in it.
         *
         * `PLAN.md` §4: the empty state "must read as 'your shelves, waiting' rather than
         * 'broken app' — one labelled empty shelf, warm lighting, a single obvious way to add
         * the first album." A real board under the real lamp is most of that; without it the
         * app opens on black and the lamp lights nothing.
         *
         * Synthetic rather than written to the database. A row created here vanishes the
         * moment a real shelf exists, whereas seeding a row on first launch would leave every
         * user with a shelf they did not make and might not want — and would need a migration
         * to change its mind later.
         */
        private fun waitingRow() = RowLayout(
            shelfId = "waiting",
            name = "Your first shelf",
            accentHue = 0.09f,
            row = 0,
            centerY = 0f,
            boardY = -WallDimens.CASE_H / 2f - WallDimens.BOARD_H / 2f,
            plateX = -WallDimens.SLOT_PITCH / 2f + WallDimens.PLATE_W / 2f
        )

        fun from(shelves: List<ShelfWithAlbums>): WallLayout {
            // Loaded, and there is genuinely nothing — not the placeholder. One lit, labelled
            // board, and no cases.
            if (shelves.isEmpty()) {
                return WallLayout(
                    rows = listOf(waitingRow()),
                    wallWidth = 3f * WallDimens.SLOT_PITCH + WallDimens.WALL_MARGIN * 2f,
                    wallHeight = WallDimens.ROW_PITCH + WallDimens.WALL_MARGIN * 2f,
                    loaded = true
                )
            }

            val rowCount = shelves.size
            // Widest shelf by the slots it *occupies*, not by how many albums it holds — see
            // the case loop below for why those differ.
            val maxCols = shelves
                .maxOf { shelf -> (shelf.albums.maxOfOrNull { it.slotIndex } ?: -1) + 1 }
                .coerceAtLeast(1)

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

            // Position comes from the album's OWN `slotIndex`, not its position in this list.
            //
            // It used to be the list index, which meant removing an album slid every album
            // after it one slot to the left — the shelf closed the gap and a neighbour
            // snapped into the deleted record's place. On a shelf you have just taken a
            // record out of, the gap is the truthful thing to show, and it is also what stops
            // the wall rearranging itself under someone who is looking at it.
            //
            // This reverses the older note about list indices keeping a shelf packed. That
            // was written to tolerate gaps in stored indices; gaps are now the point.
            val cases = shelves.flatMapIndexed { row, shelf ->
                shelf.albums.map { album ->
                    CaseLayout(
                        albumId = album.id,
                        title = album.title,
                        artist = album.artist,
                        storedHue = album.spineHue,
                        fallbackHue = stableHue(album.id),
                        coverPath = album.caseCoverUri,
                        discFacePath = album.discFaceUri,
                        row = row,
                        col = album.slotIndex,
                        x = colX(album.slotIndex),
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
                maxPanY = rowCenterY(0) + WallDimens.ROW_PITCH,
                loaded = true
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
