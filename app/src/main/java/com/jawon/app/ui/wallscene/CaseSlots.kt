package com.jawon.app.ui.wallscene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import kotlin.math.sin
import com.google.android.filament.gltfio.FilamentInstance
import com.jawon.app.ui.wallscene.WallDimens.CASE_D
import com.jawon.app.ui.wallscene.WallDimens.CASE_H
import com.jawon.app.ui.wallscene.WallDimens.CASE_W
import com.jawon.app.ui.wallscene.WallDimens.PLATE_LIFT
import com.jawon.app.ui.wallscene.WallDimens.PROUD_Z
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.ImageNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node

/**
 * One reusable case on the wall: the body that gets picked, and its two art plates.
 */
class CaseSlot {
    var root: Node? = null
    var body: Node? = null
    var spineArt: ImageNode? = null
    var coverArt: ImageNode? = null

    /**
     * Where this slot sits on the wall before the idle motion is added.
     *
     * [bind] writes these rather than writing `root.position` directly, because the frame
     * driver now owns that transform: every shelved case hovers and turns, and those are
     * per-frame values that must not be stomped by the next binding pass. Binding decides
     * *which album and where*; the frame decides *how it is floating right now*.
     */
    var baseX = 0f
    var baseY = 0f
    var baseZ = 0f

    /** True when this slot is showing an album, so the frame driver can skip parked ones. */
    var occupied = false

    /**
     * Per-slot offset into the hover and turn cycles, so a shelf does not bob and spin in
     * lockstep like a chorus line. Derived from the album id, so a given album always floats
     * the same way and re-binding a slot to it does not make it jump.
     */
    var phase = 0f
}

/**
 * The wall's pool of case slots.
 *
 * **Why a pool at all.** The wall used to mount one node per visible album and drop it when
 * it left the frustum window — and dropping it destroyed its `ImageNode`s. On this SceneView
 * version `ImageNode.destroy()` hands `MaterialLoader.destroyMaterialInstance()` a wrapper
 * re-fetched via `getMaterialInstanceAt(0)` rather than the instance the loader tracks, so
 * the instance survives and Filament aborts the *process* on the next commit with "Invalid
 * texture still bound to MaterialInstance". A hard vertical fling pulls whole rows out of
 * the window at once and killed the app within a few swipes.
 *
 * So nothing is ever destroyed. Slots are keyed by index, never by album, so no composable
 * leaves composition; [bind] then points slot *i* at whichever album is currently visible
 * there. This is the same discipline [ActiveCaseNodes] already uses for the opened case, for
 * exactly the same reason — the wall simply never got it.
 */
class CaseSlots {
    val slots = mutableListOf<CaseSlot>()

    /** Grows to [size], never shrinks — see [WallSceneScreen]'s pool sizing. */
    fun ensure(size: Int) {
        while (slots.size < size) slots += CaseSlot()
    }
}

/**
 * How a shelved case hangs in its beam.
 *
 * Kept as plain arithmetic beside [OpenSequence] and [Scratch] for the same reason those are:
 * the feel lives in these numbers and should be reviewable without a device attached.
 *
 * **Every visible case does this, every frame.** That is affordable because it is transform
 * writes on nodes that already exist — no recomposition, no allocation, no new draw calls —
 * and because the frustum window keeps the visible set to a couple of dozen. It is emphatically
 * not affordable as a real light per case, which is why the beam is geometry.
 *
 * ---
 *
 * **The unresolved part: a full 360° turn is incompatible with a densely packed rack.**
 *
 * A case is 125mm deep and its slot is 11.2mm wide. Turning one to face the viewer needs
 * eleven slots' worth of clearance, so it cannot be done without either
 *
 * - **widening the pitch to ~140mm**, which turns the wall from a record-shop rack into a
 *   sparse gallery of one or two albums per screen — closer to flipping a crate one sleeve at
 *   a time, and arguably the more precious-feeling product, but a different one; or
 * - **turning only the focused case**, so the wall stays dense and the album nearest the
 *   centre rises, turns to face you and presents itself while its neighbours stay spine-on.
 *
 * That is a product decision, not a technical one, and it changes the camera distance, the
 * pan feel and how many albums a screen can hold. Until it is made, this file does the most a
 * dense rack can physically hold: cases lift off their boards, breathe, lean, and sway just
 * far enough to walk a highlight across the cover.
 */
object ShelfHover {

    /** How far a case rises off its shelf, in metres. Just enough to break contact. */
    const val LIFT = 0.011f

    /** The bob on top of that lift, and how long a full breath takes. */
    private const val BOB = 0.0022f
    private const val BOB_PERIOD_S = 5.4f

    /**
     * Yaw, as a small oscillation rather than a continuous turn — and the ceiling on it is
     * geometry, not taste.
     *
     * A shelved case is [WallDimens.CASE_D] deep (125mm) sitting in a slot
     * [WallDimens.SLOT_PITCH] wide (11.2mm). Yawing it by θ swings its corner
     * `CASE_D/2 · sin θ` sideways, so anything past about 4.5° drives the case bodily through
     * the albums either side of it. A continuous 360° turn was tried and does exactly that:
     * the wall becomes a pile of intersecting cases.
     *
     * Full rotation needs the rack to stop being a rack — see the note in `ShelfHover`'s KDoc.
     * Until that call is made, this is the largest yaw the current spacing can physically
     * hold, which is still enough to walk a specular highlight across each cover.
     */
    private const val YAW_DEG = 3.5f
    private const val YAW_PERIOD_S = 6.7f

    /**
     * Tilt is the axis with room. Rows are [WallDimens.ROW_PITCH] apart (178mm) against a
     * 142mm case, so there is ~36mm of vertical slack and a few degrees of lean costs a
     * centimetre of it.
     */
    private const val TILT_DEG = 4.5f
    private const val TILT_PERIOD_S = 8.1f

    /** Vertical offset at time [t] seconds for a case with the given [phase]. */
    fun lift(t: Float, phase: Float): Float =
        LIFT + sin((t / BOB_PERIOD_S + phase) * TWO_PI) * BOB

    /** Y rotation in degrees — a sway within what the slot pitch allows, not a full turn. */
    fun turn(t: Float, phase: Float): Float =
        sin((t / YAW_PERIOD_S + phase) * TWO_PI) * YAW_DEG

    /** X tilt in degrees — the lean that keeps the turn from reading as flat. */
    fun tilt(t: Float, phase: Float): Float =
        sin((t / TILT_PERIOD_S + phase) * TWO_PI) * TILT_DEG

    private const val TWO_PI = 6.2831855f
}

/** A stable 0..1 phase for an album, so its case always floats the same way. */
private fun albumPhase(albumId: String): Float {
    val h = albumId.fold(0) { acc, c -> acc * 31 + c.code }
    return (((h % 997) + 997) % 997) / 997f
}

/**
 * Points each slot at a visible album, or hides it when there is nothing to show.
 *
 * Everything here is assigned imperatively rather than passed as a composable parameter.
 * That is the whole point: handing `ImageNode` a changing `bitmap` re-keys its `remember`
 * and rebuilds the node, which is the thing that aborts. Writing `node.bitmap` instead
 * re-uploads into the texture that already exists.
 *
 * Safe only because every spine shares [CaseArt.SPINE_W] × [CaseArt.SPINE_H] and every cover
 * is [CaseArt.COVER_PX] square — see [CaseArt.loadCoverSquare].
 */
fun CaseSlots.bind(
    bindings: List<Pair<CaseLayout, CaseArtwork?>>,
    selectedAlbumId: String?,
    nowPlayingAlbumId: String?
) {
    slots.forEachIndexed { index, slot ->
        val binding = bindings.getOrNull(index)
        if (binding == null) {
            // Spare slot: parked, not removed.
            slot.root?.isVisible = false
            slot.body?.name = null
            slot.occupied = false
            return@forEachIndexed
        }

        val (case, artwork) = binding
        slot.occupied = true
        slot.baseX = case.x
        slot.baseY = case.y
        // The playing album stands out of its slot, so browsing away still shows you
        // what is on (PLAN.md §9 #3).
        slot.baseZ = if (case.albumId == nowPlayingAlbumId) PROUD_Z else 0f
        // Stable per-album, so the same record always hangs the same way.
        slot.phase = albumPhase(case.albumId)
        // Hidden while its case is out in the room, so there are never two of the same album.
        slot.root?.isVisible = case.albumId != selectedAlbumId
        // What the tap handler reads back to know which album was picked.
        slot.body?.name = case.albumId

        slot.spineArt?.let { node ->
            val spine = artwork?.spine
            node.isVisible = spine != null
            if (spine != null) node.bitmap = spine
        }
        slot.coverArt?.let { node ->
            val cover = artwork?.cover
            // Albums with no cover keep the node and hide it. Omitting the node instead —
            // which is what the old code did — is itself a source of the churn being fixed.
            node.isVisible = cover != null
            if (cover != null) node.bitmap = cover
        }
    }
}

/**
 * Builds the pool. Mounted once and never torn down; [bind] does all the work afterwards.
 */
@Composable
fun SceneScope.CaseSlotNodes(
    slots: CaseSlots,
    size: Int,
    caseInstances: List<FilamentInstance>
) {
    slots.ensure(size)

    // Shared placeholders. Every art node is built from these and never from real artwork,
    // so the composable's remember is keyed on something that never changes.
    val blankSpine = remember { CaseArt.blank(CaseArt.SPINE_W, CaseArt.SPINE_H) }
    val blankCover = remember { CaseArt.blank(CaseArt.COVER_PX, CaseArt.COVER_PX) }

    DisposableEffect(slots) {
        onDispose {
            slots.slots.forEach {
                it.root = null
                it.body = null
                it.spineArt = null
                it.coverArt = null
            }
        }
    }

    repeat(size) { index ->
        // Keyed by INDEX, not album id. That is what keeps these composables in composition
        // as the wall scrolls; keying by album is what destroyed them.
        key(index) {
            val slot = slots.slots[index]
            Node(apply = { slot.root = this }) {
                // One instance of the shared closed-case asset — geometry, material and
                // textures are held once and this only adds a transform. Modelled in the
                // shelved frame (X thickness, Z depth, spine at +Z), so no rotation is
                // needed here and the art-plate positions below are unchanged.
                ModelNode(
                    caseInstances[index],
                    apply = {
                        slot.body = this
                        // Cases shadow each other along the shelf. DESIGN.md is explicit
                        // that this inter-case shadowing "is where the sense of depth on a
                        // packed shelf comes from" — so both flags, not just receive.
                        isShadowCaster = true
                        isShadowReceiver = true
                    }
                ) {
                    // The art plates never cast. They are decals sitting PLATE_LIFT proud of
                    // the faces they decorate, so casting would have them shadow their own
                    // case and shimmer as the light moves.
                    ImageNode(
                        bitmap = blankSpine,
                        size = Size(CASE_W, CASE_H, 0f),
                        normal = Direction(z = 1f),
                        position = Position(0f, 0f, CASE_D / 2f + PLATE_LIFT),
                        apply = {
                            slot.spineArt = this
                            isShadowCaster = false
                        }
                    )
                    // The cover faces +X, matching where the opened case's lid ends up: its
                    // cover is on local +Z, and the turn starts at +90, which maps that to
                    // world +X. Facing -X here is what made the artwork jump sides the
                    // moment the case reshelved.
                    //
                    // Sits PLATE_LIFT proud of the case face now, where it used to sit
                    // 1.2mm inside it — behind a thin transparent panel that stood in for
                    // the clear front. That panel is gone: the closed-case model carries
                    // clearcoat, which is the plastic sheen it existed to provide, as a
                    // second specular lobe on an opaque surface. Across hundreds of slots
                    // that trades a blended, depth-sorted draw per case for nothing at all.
                    // Left where it was, the artwork would now be buried inside solid
                    // geometry.
                    ImageNode(
                        bitmap = blankCover,
                        size = Size(CASE_D, CASE_H, 0f),
                        normal = Direction(z = 1f),
                        position = Position(CASE_W / 2f + PLATE_LIFT, 0f, 0f),
                        rotation = io.github.sceneview.math.Rotation(0f, 90f, 0f),
                        apply = {
                            slot.coverArt = this
                            isShadowCaster = false
                        }
                    )
                }
            }
        }
    }
}
