package com.jawon.app.ui.wallscene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import com.google.android.filament.gltfio.FilamentInstance
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node

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
     * Far enough that the rack is not standing in it — the shelving's boards reach
     * `BOARD_D / 2` forward of the wall plane — and no further, because forward is toward the
     * camera. At 300mm the first attempt put these a third of the way from the wall to the
     * lens, which magnifies an object by half again before its own size is counted. Both
     * mistakes compounded: a big prop, brought close.
     */
    const val FLOOR_PROP_Z = 0.105f

    /**
     * Where the floor props stand across the wall, as fractions of the pannable range.
     *
     * **Four attempts, and the first three all argued about the wrong axis.** Absolute
     * positions put a turntable in the middle of a wide library. Measuring from `roomLeft`
     * put both props outside the frame, because `roomLeft` is the wall, not anywhere the
     * camera can go. Measuring from `minPanX` looked right on paper and still failed on the
     * device, and the screenshot is why: **the wall comes to rest centred on the leftmost
     * ALBUM**, not on `minPanX`, so nothing placed past the last record can ever be centred
     * — and a 235mm deck does not fit in the one empty slot of margin anyway.
     *
     * The answer was that sideways was never the right axis. These stand on the FLOOR, well
     * below the bottom row, so they are reached by panning *down* — which works from
     * anywhere along the wall rather than only at one end — and they are clear of every
     * record by height rather than by distance. Which is also where a shop keeps them.
     *
     * Spread across the range rather than placed at a fixed x, so a library of any width has
     * them somewhere sensible instead of huddled at one end.
     */
    const val TURNTABLE_ALONG = 0.28f
    const val PLANT_ALONG = 0.76f

    /** The clock hangs on the brick, on the other side of the sign from the first poster. */
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
    wallZ: Float,
    /** The camera's own travel, which is what decides whether a prop can be seen at all. */
    minPanX: Float,
    maxPanX: Float
) {
    // **The transform goes on a carrier Node, not on the ModelNode.**
    //
    // Setting `position` inside a ModelNode's own `apply` does not stick: all three props
    // rendered at the world origin, stacked on top of each other in the middle of the rack and
    // in front of the records, which is what the owner was looking at when he asked what was
    // happening. Every other model in this scene is placed by a parent — the cases, the beam,
    // the shards — and this is why.
    prop(
        turntable,
        Position(
            minPanX + (maxPanX - minPanX) * RoomProps.TURNTABLE_ALONG,
            floorY,
            RoomProps.FLOOR_PROP_Z
        ),
        RoomProps.TURNTABLE_TURN_DEG, "turntable"
    )
    prop(
        plant,
        Position(
            minPanX + (maxPanX - minPanX) * RoomProps.PLANT_ALONG,
            floorY,
            RoomProps.FLOOR_PROP_Z
        ),
        RoomProps.PLANT_TURN_DEG, "plant"
    )
    prop(clock, Position(RoomProps.CLOCK_X, rackTopY + RoomProps.CLOCK_ABOVE_CAP, wallZ), 0f, "clock")
}

@Composable
private fun SceneScope.prop(
    instance: FilamentInstance?,
    at: Position,
    turnDeg: Float,
    name: String
) {
    instance ?: return
    key("prop", name) {
        Node(
            position = at,
            rotation = Rotation(0f, turnDeg, 0f)
        ) {
            ModelNode(
                instance,
                apply = {
                    isTouchable = false
                    // These are objects, not fixtures. An object in a lit room that casts no
                    // shadow is the thing that gives a render away.
                    isShadowCaster = true
                    isShadowReceiver = true
                }
            )
        }
    }
}
