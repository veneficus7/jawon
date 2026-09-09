package com.jawon.app.ui.wallscene

import com.jawon.app.data.local.ShelfWithAlbums
import kotlin.math.roundToInt

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
     * How far the floor sits below the lowest the camera can look — see [WallLayout.floorY].
     *
     * Under the 0.167 half-frame height the camera has at its closest zoom, so the floor is in
     * shot whenever the user reaches the bottom of the library at any zoom. Going much larger
     * would put it off screen at close zoom; much smaller and the bottom shelf would appear to
     * rest on the ground, which loses the plinth.
     */
    const val FLOOR_DROP = 0.115f

    /**
     * How far the floor reaches out from the wall, toward and past the camera.
     *
     * Past [CAMERA_Z] deliberately, and past [WallSceneScreen]'s furthest zoom too. A floor that
     * stopped short of the lens would show its own front edge as a hard line across the bottom
     * of the screen with blackness nearer than the ground — a step, not a floor. Extending under
     * the camera costs two more triangles and removes the edge entirely.
     */
    const val FLOOR_REACH = 1.62f

    /** Thin, like the wall backing. Nothing ever sees its underside. */
    const val FLOOR_T = 0.01f

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

    /**
     * Camera clip planes, and the reason cover art used to vanish when zoomed out.
     *
     * A depth buffer resolves distance hyperbolically: almost all of its precision is spent
     * just past the near plane, and what is left thins out rapidly with distance. How finely
     * it can separate two surfaces therefore depends on the *ratio* of these two numbers far
     * more than on either alone.
     *
     * SceneView's defaults are a 5cm near plane against a 1km far plane — a ratio of twenty
     * thousand to one, chosen for scenes measured in metres. This room is measured in
     * centimetres and its artwork plates sit fractions of a millimetre off the faces they
     * decorate, which that ratio cannot reliably resolve at any distance and cannot resolve
     * at all once the wall is a few metres away. The plate and the case body then flicker or
     * swap, which is what "the artwork disappears, and worse when zoomed out" was.
     *
     * 0.12m is safely nearer than anything ever drawn: the disc sits 0.55m from the lens at
     * its closest and a case rises RISE_Z toward it. 14m clears the widest wall a library is
     * likely to build plus its margin. The ratio drops from 20,000:1 to about 117:1.
     */
    const val NEAR_PLANE = 0.12f
    const val FAR_PLANE = 14f
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
    val y: Float,
    /**
     * How played-in this case looks, `0f`..`1f`, from [Patina.forListening]. Baked into the
     * spine rather than applied as a material property, because wear is dirt and abrasion in
     * particular *places* — it is not a uniform darkening.
     */
    val wear: Float = 0f
)

data class RowLayout(
    val shelfId: String,
    val name: String,
    val accentHue: Float,
    val row: Int,
    val centerY: Float,
    val boardY: Float,
    /** Where the label plate sits along the board — the centre of the shelf. */
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
     * Every case in the order it hangs — top row first, left to right within a row.
     *
     * This is the order a thumb travels in, which is what the crate flip scrubs along.
     */
    val casesInWallOrder: List<CaseLayout>
        get() = cases.sortedWith(compareBy({ it.row }, { it.col }))

    /**
     * The album a crate flip lands on, from how far down the rail the thumb is.
     *
     * **Wall order, not alphabetical order, and that is the whole design.** `PLAN.md` §9 #7
     * describes this as flipping a crate with your thumb and snapping to alphabet dividers,
     * which quietly assumes the wall is filed A–Z. It is not: albums sit in `slotIndex` order
     * within a shelf and shelves in their own order, which is import order until someone
     * rearranges them. A rail sorted A–Z over a wall that is not would send the wall lurching
     * backwards and forwards as the thumb moved steadily down — the one thing a scrubber must
     * never do.
     *
     * So the rail is a position along the wall and the letters are what you *find* there, the
     * way the dividers in a real crate describe the crate rather than reordering it. If the
     * collection happens to be filed alphabetically the rail reads A–Z on its own, and if it
     * is filed by mood or by year it still tracks the thumb honestly.
     */
    fun caseAtFraction(fraction: Float): CaseLayout? {
        val ordered = casesInWallOrder
        if (ordered.isEmpty()) return null
        val index = (fraction.coerceIn(0f, 1f) * (ordered.size - 1))
            .roundToInt()
            .coerceIn(0, ordered.lastIndex)
        return ordered[index]
    }

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

    /**
     * Where the room's floor sits, derived from the library rather than fixed.
     *
     * **There is no fixed bottom to this room, which is why this is computed.** The wall grows
     * downward as shelves are added — `rowCenterY` spreads the rows around y=0 — so a floor at a
     * constant height would be buried inside the shelving for a large library and far below the
     * frame for a small one. Deriving it from the lowest row is the same discipline the pan
     * bounds already follow (ENGINEERING.md: "Shelf bounds derived from content, never unbounded").
     *
     * Dropped below [minPanY] rather than below the last board, and that choice is what makes it
     * visible at all: `minPanY` is the lowest the camera can be centred, so anything within half
     * a frame height of it is on screen when the user pans to the bottom of the library. The
     * drop is smaller than the tightest half-frame the camera can have — `ZOOM_NEAR * TAN_HALF_FOV`
     * is about 0.167 — so the floor is in frame at every zoom level, not just when pulled back.
     *
     * The gap it leaves under the bottom shelf reads as the plinth a real shelving unit stands
     * on, which is the other half of the point: a shelf that meets the floor at its own last
     * board looks like a wall, and one standing clear of it looks like furniture.
     */
    val floorY: Float get() = minPanY - WallDimens.FLOOR_DROP

    companion object {
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
            plateX = 0f
        )

        /**
         * One shelf = one row of the wall, ordered by `Shelf.order`. Albums run along the
         * row in `slotIndex` order, but their *positions* come from their index in the list
         * rather than from the stored index — so a shelf is always packed and always centred
         * on its own label, whatever the database happens to hold.
         */
        fun from(
            shelves: List<ShelfWithAlbums>,
            /**
             * Per-album wear from [Patina.byAlbum]. Passed in rather than read here so this
             * stays a pure function of its arguments — an album absent from the map has
             * never been played and gets a clean case.
             */
            wearByAlbum: Map<String, Float> = emptyMap(),
            /** How the records are filed along each shelf. */
            sort: WallSort = WallSort.SHELF,
            /** Total listening per album, for [WallSort.PLAYED]. */
            listenedMs: Map<String, Long> = emptyMap()
        ): WallLayout {
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

            fun rowCenterY(row: Int) = ((rowCount - 1) / 2f - row) * WallDimens.ROW_PITCH

            /**
             * Where the `col`th album on a shelf of `count` sits.
             *
             * **Centred on its OWN shelf, and packed.** Two things were wrong here. It
             * divided by the widest shelf's album count rather than this shelf's, so every
             * shelf shorter than the longest one hung off to the left instead of sitting
             * under its own label. And it was briefly keyed on stored slot indices, which
             * left a hole where a removed album had been — the gap is not what the owner
             * wants; records should close ranks.
             *
             * Packing does mean the neighbours move when one is removed. That is handled
             * where it belongs, in the scene: `CaseSlots` eases each case to its new place
             * rather than teleporting it, so the shelf visibly closes up.
             */
            fun colX(col: Int, count: Int) =
                (col - (count - 1) / 2f) * WallDimens.SLOT_PITCH

            val rows = shelves.mapIndexed { row, shelf ->
                RowLayout(
                    shelfId = shelf.shelf.id,
                    name = shelf.shelf.name,
                    accentHue = shelf.shelf.accentHue,
                    row = row,
                    centerY = rowCenterY(row),
                    boardY = rowCenterY(row) - WallDimens.CASE_H / 2f - WallDimens.BOARD_H / 2f,
                    // Dead centre of the shelf. The label is the tape stuck on the front of
                    // the board and it marks the middle of the run, which is also what the
                    // albums are centred on — so the shelf reads as one thing with its name
                    // under it, rather than a name at one end and records somewhere else.
                    plateX = 0f
                )
            }

            // Packed by position in the list, which the DAO has already sorted by slotIndex.
            // Removing an album therefore closes the gap; `CaseSlots` animates that rather
            // than letting the neighbours jump.
            val cases = shelves.flatMapIndexed { row, shelf ->
                val count = shelf.albums.size
                // Ordered here rather than in the query: the DAO returns slotIndex order, and
                // a sort is a view over the shelf rather than a change to how it is filed —
                // switching back to AS FILED has to give the arrangement the user made.
                WallSort.order(shelf.albums, sort, listenedMs).mapIndexed { col, album ->
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
                        x = colX(col, count),
                        y = rowCenterY(row),
                        wear = wearByAlbum[album.id] ?: 0f
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
                wallWidth = shelves.maxOf { it.albums.size }.coerceAtLeast(1) *
                    WallDimens.SLOT_PITCH + WallDimens.WALL_MARGIN * 2f,
                wallHeight = rowCount * WallDimens.ROW_PITCH + WallDimens.WALL_MARGIN * 2f,
                minPanX = panXRange.first,
                maxPanX = panXRange.second,
                minPanY = rowCenterY(rowCount - 1) - WallDimens.ROW_PITCH,
                maxPanY = rowCenterY(0) + WallDimens.ROW_PITCH,
                loaded = true
            )
        }

        /**
         * The letter a title files under: `A`..`Z`, or `#` for anything else.
         *
         * Leading articles are skipped, because a crate divider that files half a collection
         * under T is no use to anybody.
         */
        fun initialOf(title: String): Char {
            val trimmed = title.trim()
            val withoutArticle = ARTICLES.firstOrNull { article ->
                trimmed.length > article.length &&
                    trimmed.regionMatches(0, article, 0, article.length, ignoreCase = true)
            }?.let { trimmed.substring(it.length).trim() } ?: trimmed

            val first = withoutArticle.firstOrNull() ?: return '#'
            val upper = first.uppercaseChar()
            return if (upper in 'A'..'Z') upper else '#'
        }

        private val ARTICLES = listOf("The ", "A ", "An ")

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
