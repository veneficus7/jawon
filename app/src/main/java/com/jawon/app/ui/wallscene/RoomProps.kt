package com.jawon.app.ui.wallscene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import com.google.android.filament.gltfio.FilamentInstance
import io.github.sceneview.node.ModelNode

/**
 * The things in this shop that are not records: a deck, a clock, a plant.
 *
 * The last of the four kinds of decor the owner picked. Everything else in the room is a
 * record, a fixture or a surface, and a room made only of those reads as a display rather than
 * as somewhere a person works. These are what a shop accumulates.
 *
 * ## Why they stand on the floor and not on the rack
 *
 * The obvious place for a turntable is the top of the rack, and it will not go there. A cap
 * board is [WallDimens.BOARD_D] deep — 135mm — and a real deck is **340mm** deep, so it would
 * hang two thirds of itself out over the aisle. That is not a modelling problem to be solved by
 * shrinking the deck: the props are at real size deliberately, because a turntable being nearly
 * three CD cases wide is the fact that makes the rack read as furniture rather than as a
 * texture. The thing that had to move was the prop, not its scale.
 *
 * So the floor, which this room gained recently and which had nothing standing on it. Props sit
 * clear of the rack's own footprint, out where the aisle is, and they are what the floor is for.
 */
object RoomProps {

    const val TURNTABLE_ASSET = "models/turntable.glb"
    const val CLOCK_ASSET = "models/clock.glb"
    const val PLANT_ASSET = "models/plant.glb"

    /**
     * How far out from the wall a floor prop stands, to the centre of its footprint.
     *
     * Far enough that the rack is not standing in it: the shelving's own boards reach
     * `BOARD_D / 2` forward of the wall plane, and a deck is 340mm deep, so anything less than
     * about a quarter of a metre puts the two objects through each other.
     */
    const val FLOOR_PROP_Z = 0.30f

    /**
     * Where each floor prop stands along the wall.
     *
     * Well out past the rack's usual framing rather than beside the records: a prop that sits
     * inside the browsing frame is in the way of the only thing the room is for. These are
     * found by panning, the way the posters and the sign are.
     */
    const val TURNTABLE_X = -1.32f
    const val PLANT_X = 1.44f

    /** The clock hangs on the brick, over on the other side of the sign from the first poster. */
    const val CLOCK_X = -0.94f

    /**
     * How far above the rack's cap the clock's centre sits.
     *
     * Higher than the poster band: a clock in a shop is above eye level, where it can be seen
     * over the racks from anywhere in the room, and a clock at sleeve height reads as another
     * thing hung on the wall rather than as the clock.
     */
    const val CLOCK_ABOVE_CAP = 0.42f

    /**
     * How far each floor prop is turned off square, in degrees.
     *
     * Nothing in a shop is parallel to the wall it stands against. The deck faces very slightly
     * into the room because somebody stood at it; the plant is turned further, because nobody
     * ever squares up a plant.
     */
    const val TURNTABLE_TURN_DEG = -9f
    const val PLANT_TURN_DEG = 24f
}

/**
 * Mounts the three props.
 *
 * Each takes an instance loaded once by the caller and is given a transform and nothing else —
 * no per-frame work, no state and no pooling, because there are three of them and they never
 * move. They cast and receive, unlike every fixture in this room: these are objects, and an
 * object in a lit room that casts no shadow is the thing that gives a render away.
 */
@Composable
fun SceneScope.RoomPropNodes(
    turntable: FilamentInstance?,
    clock: FilamentInstance?,
    plant: FilamentInstance?,
    floorY: Float,
    rackTopY: Float,
    wallZ: Float
) {
    turntable?.let { instance ->
        key("prop-turntable") {
            ModelNode(
                instance,
                apply = {
                    position = Position(RoomProps.TURNTABLE_X, floorY, RoomProps.FLOOR_PROP_Z)
                    rotation = Rotation(0f, RoomProps.TURNTABLE_TURN_DEG, 0f)
                    isTouchable = false
                    isShadowCaster = true
                    isShadowReceiver = true
                }
            )
        }
    }
    plant?.let { instance ->
        key("prop-plant") {
            ModelNode(
                instance,
                apply = {
                    position = Position(RoomProps.PLANT_X, floorY, RoomProps.FLOOR_PROP_Z)
                    rotation = Rotation(0f, RoomProps.PLANT_TURN_DEG, 0f)
                    isTouchable = false
                    isShadowCaster = true
                    isShadowReceiver = true
                }
            )
        }
    }
    clock?.let { instance ->
        key("prop-clock") {
            ModelNode(
                instance,
                apply = {
                    position = Position(
                        RoomProps.CLOCK_X,
                        rackTopY + RoomProps.CLOCK_ABOVE_CAP,
                        wallZ
                    )
                    isTouchable = false
                    // A clock on a wall casts onto that wall, and the washers are right there.
                    isShadowCaster = true
                    isShadowReceiver = true
                }
            )
        }
    }
}
