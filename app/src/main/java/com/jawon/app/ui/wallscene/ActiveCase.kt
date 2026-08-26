package com.jawon.app.ui.wallscene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.jawon.app.ui.wallscene.WallDimens.CASE_D
import com.jawon.app.ui.wallscene.WallDimens.CASE_H
import com.jawon.app.ui.wallscene.WallDimens.CASE_W
import com.jawon.app.ui.wallscene.WallDimens.PLATE_LIFT
import com.jawon.app.ui.wallscene.WallDimens.TRAY_FRONT_Z
import com.jawon.app.ui.wallscene.WallDimens.TRAY_T
import io.github.sceneview.SceneScope
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.ImageNode
import io.github.sceneview.node.Node

/**
 * Handles on the nodes driven imperatively — either per frame by the sequence, or on
 * selection.
 *
 * Two separate reasons these are handles rather than declarative parameters:
 *
 * 1. The specialised node composables (`CubeNode`, `ImageNode`, …) re-push their declarative
 *    position/rotation from a `SideEffect` on every recomposition, which would stomp what the
 *    frame driver just wrote. The bare `Node` composable deliberately does not — so anything
 *    animated per frame is a bare `Node` and renderables hang off it.
 * 2. Destroying an `ImageNode` aborts the process (see [ActiveCaseNodes] usage), so the art
 *    nodes are built once and their bitmaps swapped in place.
 */
class ActiveCaseNodes {
    var root: Node? = null
    var hinge: Node? = null
    var disc: Node? = null
    var spineArt: ImageNode? = null
    var coverArt: ImageNode? = null
}

/**
 * The case as it looks once it leaves the rack: a tray with a hinged lid, its cover and
 * spine art, and the disc.
 *
 * Modelled cover-forward in its own local space — X is the cover's width, Y its height, Z
 * the case's thickness — and the sequence starts it turned 90° edge-on so it reads as a
 * spine, then rotates it to face the viewer. That mirrors the prototype's separate `act`
 * group rather than trying to re-pose the shelved case, which has no tray or lid.
 *
 * Tray and lid together come to exactly [CASE_W], the same thickness as the shelved case it
 * replaces, so the silhouette matches and nothing appears or vanishes at the swap.
 *
 * **This group is mounted for the whole life of the scene and hidden when nothing is open.**
 * Tearing it down instead would destroy its `ImageNode`s, and that aborts the process.
 */
@Composable
fun SceneScope.ActiveCaseNodes(
    nodes: ActiveCaseNodes,
    caseMaterial: MaterialInstance,
    lidMaterial: MaterialInstance,
    discMaterial: MaterialInstance,
    discGeometry: Geometry
) {
    // Stable placeholders: passing a changing bitmap to the ImageNode composable would
    // re-key its remember and rebuild the node. The real art is written to `node.bitmap`
    // afterwards, which re-uploads into the existing texture. Every spine and every cover
    // shares fixed dimensions precisely so that in-place swap is always legal.
    val blankSpine = remember { CaseArt.blank(CaseArt.SPINE_W, CaseArt.SPINE_H) }
    val blankCover = remember { CaseArt.blank(CaseArt.COVER_PX, CaseArt.COVER_PX) }

    DisposableEffect(nodes) {
        onDispose {
            nodes.root = null
            nodes.hinge = null
            nodes.disc = null
            nodes.spineArt = null
            nodes.coverArt = null
        }
    }

    // The case group. Position and rotation are written every frame by the sequence driver.
    Node(apply = { nodes.root = this }) {
        // The tray occupies the back of the case's 10mm; the lid closes flush into the front.
        CubeNode(
            size = Size(CASE_D, CASE_H, TRAY_T),
            position = Position(0f, 0f, -CASE_W / 2f + TRAY_T / 2f),
            materialInstance = caseMaterial,
            apply = { isTouchable = false }
        )
        // On the local -X edge, which is what faces the viewer while the case is still
        // turned edge-on at the start of the sequence.
        ImageNode(
            bitmap = blankSpine,
            size = Size(CASE_W, CASE_H, 0f),
            normal = Direction(z = 1f),
            position = Position(-CASE_D / 2f - PLATE_LIFT, 0f, 0f),
            rotation = Rotation(0f, -90f, 0f),
            apply = {
                isTouchable = false
                nodes.spineArt = this
            }
        )
        // The spindle the disc sits on, revealed once the disc lifts away.
        CylinderNode(
            radius = 0.0085f,
            height = 0.0025f,
            materialInstance = caseMaterial,
            position = Position(0f, 0f, TRAY_FRONT_Z + 0.0012f),
            rotation = Rotation(90f, 0f, 0f),
            apply = { isTouchable = false }
        )
        // Hinged on the left edge of the front face; the driver swings this.
        //
        // The artwork lives in the lid, not on the tray — that is how a real jewel case is
        // put together (the inlay card sits behind the clear front, and the disc sits on the
        // tray underneath). Having the art on the tray made opening reveal artwork and disc
        // stacked on each other, which read as nothing recognisable; this way the lid carries
        // the cover away with it and uncovers the disc.
        Node(
            position = Position(-CASE_D / 2f, 0f, TRAY_FRONT_Z),
            apply = { nodes.hinge = this }
        ) {
            // The inlay, sitting against the inside of the clear front the way a real one does.
            ImageNode(
                bitmap = blankCover,
                size = Size(CASE_D, CASE_H, 0f),
                normal = Direction(z = 1f),
                position = Position(CASE_D / 2f, 0f, 0.0026f),
                apply = {
                    isTouchable = false
                    nodes.coverArt = this
                }
            )
            // Clear polycarbonate front, closing flush with the case's outer face.
            CubeNode(
                size = Size(CASE_D, CASE_H, 0.001f),
                materialInstance = lidMaterial,
                position = Position(CASE_D / 2f, 0f, 0.0035f),
                apply = { isTouchable = false }
            )
        }
    }

    // The disc rides to a camera-relative position, so it is a sibling of the case rather
    // than a child of it — same as the prototype.
    Node(apply = { nodes.disc = this }) {
        MeshNode(
            primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
            vertexBuffer = discGeometry.vertexBuffer,
            indexBuffer = discGeometry.indexBuffer,
            boundingBox = discGeometry.boundingBox,
            materialInstance = discMaterial,
            apply = { isTouchable = false }
        )
    }
}
