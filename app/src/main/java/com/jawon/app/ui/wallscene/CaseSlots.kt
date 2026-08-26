package com.jawon.app.ui.wallscene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.google.android.filament.MaterialInstance
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
import io.github.sceneview.node.Node

/**
 * One reusable case on the wall: the body that gets picked, and its two art plates.
 */
class CaseSlot {
    var root: Node? = null
    var body: Node? = null
    var spineArt: ImageNode? = null
    var coverArt: ImageNode? = null
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
            return@forEachIndexed
        }

        val (case, artwork) = binding
        slot.root?.position = Position(
            case.x,
            case.y,
            // The playing album stands out of its slot, so browsing away still shows you
            // what is on (PLAN.md §9 #3).
            if (case.albumId == nowPlayingAlbumId) PROUD_Z else 0f
        )
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
    caseMaterial: MaterialInstance,
    lidMaterial: MaterialInstance
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
                CubeNode(
                    size = Size(CASE_W, CASE_H, CASE_D),
                    materialInstance = caseMaterial,
                    apply = {
                        slot.body = this
                        // Cases shadow each other along the shelf. DESIGN.md is explicit
                        // that this inter-case shadowing "is where the sense of depth on a
                        // packed shelf comes from" — so both flags, not just receive.
                        isShadowCaster = true
                        isShadowReceiver = true
                        // Sells the contact line where the case meets its board, which a
                        // 1024px shadow map is too coarse to resolve.
                        setScreenSpaceContactShadows(true)
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
                    ImageNode(
                        bitmap = blankCover,
                        size = Size(CASE_D, CASE_H, 0f),
                        normal = Direction(z = 1f),
                        position = Position(CASE_W / 2f - 0.0012f, 0f, 0f),
                        rotation = io.github.sceneview.math.Rotation(0f, 90f, 0f),
                        apply = {
                            slot.coverArt = this
                            isShadowCaster = false
                        }
                    )
                    // The clear front, flush with the outer face — the same panel the opened
                    // case carries in its lid. Without it the plastic sheen vanished the
                    // instant a case returned to the shelf.
                    CubeNode(
                        size = Size(0.0008f, CASE_H, CASE_D),
                        materialInstance = lidMaterial,
                        position = Position(CASE_W / 2f - 0.0004f, 0f, 0f),
                        apply = {
                            isTouchable = false
                            // Clear plastic. Casting would draw it as an opaque slab in the
                            // shadow pass and black out the case.
                            isShadowCaster = false
                        }
                    )
                }
            }
        }
    }
}
