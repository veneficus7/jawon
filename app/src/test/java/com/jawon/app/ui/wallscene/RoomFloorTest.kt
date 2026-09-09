package com.jawon.app.ui.wallscene

import com.jawon.app.data.local.Album
import com.jawon.app.data.local.Shelf
import com.jawon.app.data.local.ShelfWithAlbums
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the floor goes, which is the whole question.
 *
 * **A floor at the wrong height is not a subtle bug — it is an invisible feature.** This room has
 * no fixed bottom: the wall grows downward as shelves are added, because `rowCenterY` spreads the
 * rows around y=0. So a constant height would be buried inside the shelving for a large library
 * and far below the frame for a small one, and in both cases the work would look like it had not
 * been done. None of that needs a device to check — it is arithmetic against the camera's own
 * frustum, and that is what this file is.
 */
class RoomFloorTest {

    /** Half the frame height, in world units, at a given camera distance. */
    private fun halfFrame(cameraZ: Float) = cameraZ * WallDimens.TAN_HALF_FOV

    /** The closest the camera is allowed, and therefore the tightest frame it ever has. */
    private val tightestHalfFrame = halfFrame(ZOOM_NEAR)

    private fun layoutOf(shelves: Int, albumsPerShelf: Int): WallLayout =
        WallLayout.from(
            (0 until shelves).map { s ->
                ShelfWithAlbums(
                    shelf = Shelf(
                        id = "shelf$s",
                        name = "Shelf $s",
                        order = s,
                        accentHue = 0f,
                        createdAt = 0L
                    ),
                    albums = (0 until albumsPerShelf).map { a ->
                        Album(
                            id = "a$s-$a",
                            shelfId = "shelf$s",
                            title = "Album $a",
                            artist = "Artist",
                            year = 2000,
                            caseCoverUri = null,
                            discFaceUri = null,
                            spineHue = 0f,
                            slotIndex = a,
                            playCount = 0,
                            addedAt = 0L
                        )
                    }
                )
            },
            emptyMap(),
            WallSort.SHELF,
            emptyMap()
        )

    @Test
    fun `the floor is in frame when the library is panned to the bottom`() {
        // The point of deriving it from minPanY rather than from the last board. minPanY is the
        // lowest the camera can be centred, so the floor has to be within half a frame of it —
        // and within the *tightest* half frame, or it vanishes at close zoom, which is the zoom
        // somebody browsing actually uses.
        for (shelves in 1..8) {
            val layout = layoutOf(shelves, albumsPerShelf = 3)
            val drop = layout.minPanY - layout.floorY

            assertTrue("$shelves shelves: floor is above the lowest pan", drop > 0f)
            assertTrue(
                "$shelves shelves: floor $drop below the lowest pan, but the tightest " +
                    "half-frame is only $tightestHalfFrame — it would be off screen",
                drop < tightestHalfFrame
            )
        }
    }

    @Test
    fun `the floor never cuts through the shelving`() {
        // It has to clear the lowest board by a real margin, or the bottom shelf grows out of the
        // ground and the plinth — the thing that makes a shelf read as furniture rather than as
        // wall — is gone.
        for (shelves in 1..8) {
            val layout = layoutOf(shelves, albumsPerShelf = 3)
            val lowestBoard = layout.rows.minOf { it.boardY }
            val clearance = lowestBoard - layout.floorY

            assertTrue(
                "$shelves shelves: only $clearance between the bottom board and the floor",
                clearance > WallDimens.CASE_H / 2f
            )
        }
    }

    @Test
    fun `the floor moves down as the library grows`() {
        // The room's bottom follows its content, the same discipline the pan bounds already
        // follow. A floor that stayed put while the wall extended past it would end up inside
        // the shelving.
        val floors = (1..6).map { layoutOf(it, albumsPerShelf = 3).floorY }

        for (i in 1 until floors.size) {
            assertTrue(
                "floor did not drop from ${floors[i - 1]} to ${floors[i]}",
                floors[i] < floors[i - 1]
            )
        }
    }

    @Test
    fun `the floor reaches past the camera so its own front edge is never seen`() {
        // A floor that stopped short of the lens would draw a hard line across the bottom of the
        // screen with blackness *nearer* than the ground — which reads as a step, not a floor.
        // It has to clear the furthest the camera can pull back, not merely the default.
        assertTrue(
            "floor reaches ${WallDimens.FLOOR_REACH} but the camera can stand at $ZOOM_FAR",
            WallDimens.FLOOR_REACH > ZOOM_FAR
        )
    }

    @Test
    fun `an empty library still has a floor somewhere sane`() {
        // The placeholder layout the state flow starts on. Nothing should divide by zero or put
        // the floor at infinity while the database is still loading.
        val empty = WallLayout()

        assertTrue(empty.floorY.isFinite())
        assertTrue(empty.floorY < empty.minPanY)
    }

    @Test
    fun `the floor sits below the wall panel's own centre`() {
        // Sanity on the sign of everything: if FLOOR_DROP were ever negative, the floor would be
        // a ceiling and every test above would still pass except this one.
        val layout = layoutOf(shelves = 3, albumsPerShelf = 4)

        assertTrue(layout.floorY < (layout.maxPanY + layout.minPanY) / 2f)
    }
}
