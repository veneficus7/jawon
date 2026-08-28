package com.jawon.app.ui.wallscene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.gltfio.FilamentInstance
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale

import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node

/**
 * Removing an album, on screen.
 *
 * Lives in its own file rather than inside `WallSceneScreen`, which is already two thousand
 * lines and is where every 3D bug in this project has ended up being traced. This owns a small
 * set of nodes and one per-frame function, so it can be read and changed without holding the
 * rest of the scene in your head.
 *
 * **It drives the held case** — the same tray, lid and hinge the open sequence uses — because
 * removal should read in the same physical language as opening, and because the shelved case
 * has no interior to show a disc in. See [CaseDiscard] for the choreography.
 *
 * **The fragments stand in for the disc rather than joining it.** `disc_shards.glb` holds 27
 * pieces that at rest sit exactly where a whole disc would, so the same nodes read as an
 * intact disc in the tray and then as the pieces it breaks into — no second model, and no
 * swap on the frame of the strike.
 *
 * **One removal at a time.** These nodes are allocated once and reused, which is safe because
 * a long-press is refused while one is running.
 */
class DiscardNodes {
    /** Holds the fragments; follows the case's tray so the lid can shut over them. */
    var group: Node? = null

    /**
     * The fragments, each paired with what the geometry knows about it.
     *
     * Both facts are read off the glTF node rather than duplicated in Kotlin: the build script
     * puts each shard's origin at its own centroid, so the rest position is simply where the
     * node already sits, and it encodes the piece's share of the disc's area in the node name.
     * A second copy of that layout here would drift the first time the fracture changed.
     */
    var shards: List<Piece> = emptyList()

    /** One fragment: its node, where it rested in the whole disc, and its relative mass. */
    class Piece(val node: Node, val restX: Float, val restY: Float, val mass: Float)

    /**
     * The fragments' materials, so the strike can make them blaze.
     *
     * The flash is emission on the pieces themselves rather than a light shining on them.
     * That was partly forced — SceneView's `LightNode` hands its `apply` block a
     * `LightManager.Builder`, not the node, so there is no handle to drive intensity per
     * frame — but it is the better image regardless: the disc lighting up *from inside* reads
     * as something happening to it, where a lamp pointed at it reads as stage lighting.
     */
    var shardMaterials: List<com.google.android.filament.MaterialInstance> = emptyList()

    /** Which album is being removed, and where its case sat, or null when idle. */
    var albumId: String? = null
    var slotX = 0f
    var slotY = 0f

    /** Seconds into [CaseDiscard.DURATION_S], or null when idle. */
    var clock: Float? = null

    val isRunning: Boolean get() = clock != null
}

/**
 * Mounts the removal's nodes, hidden.
 *
 * Never destroyed, for the same reason the wall's case slots are pooled: nothing in this scene
 * should be torn down and rebuilt on a state change. See `CaseSlots` for the failure that
 * discipline exists to prevent.
 */
@Composable
fun SceneScope.DiscardSceneNodes(
    nodes: DiscardNodes,
    shardsInstance: FilamentInstance?,
    discTexture: Texture,
    sampler: TextureSampler
) {
    DisposableEffect(nodes) {
        onDispose {
            nodes.group = null
            nodes.shards = emptyList()
            nodes.shardMaterials = emptyList()
        }
    }

    if (shardsInstance == null) return

    Node(apply = {
        nodes.group = this
        isVisible = false
        isTouchable = false
    }) {
        ModelNode(
            shardsInstance,
            apply = {
                isTouchable = false
                // Hidden HERE as well as on the parent group.
                //
                // SceneView does not propagate visibility down, so a hidden group with a
                // visible model in it still draws — which is exactly what it did: launching
                // the app showed a disc-shaped object standing at the world origin, in front
                // of the shelves, for no reason anyone could see. Every shard node below is
                // hidden individually for the same reason.
                isVisible = false
                // Broken plastic catches the lamp, but 27 tumbling shadow casters for half a
                // second is a great deal of shadow-map churn for something nobody is reading.
                isShadowCaster = false
                isShadowReceiver = false

                // Read the fragments off the asset by the names the build script gave them:
                // `shard_<index>_<per-mille area>`.
                nodes.shards = this.nodes
                    .mapNotNull { child -> child.name?.let { child to it } }
                    .filter { (_, name) -> name.startsWith(SHARD_PREFIX) }
                    .mapNotNull { (child, name) ->
                        val parts = name.removePrefix(SHARD_PREFIX).split('_')
                        val index = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                        val perMille = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                        val ex = parts.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                        val ey = parts.getOrNull(3)?.toIntOrNull() ?: return@mapNotNull null
                        child.isVisible = false
                        index to DiscardNodes.Piece(
                            node = child,
                            // From the NAME, not from `child.position`.
                            //
                            // SceneView's `Node.position` is the node's own transform and
                            // starts at identity — it is not the glTF node's translation,
                            // which is where each shard's centroid actually lives. Reading it
                            // gave zero for every piece, and writing it back then wiped the
                            // real placement, so all 27 fragments collapsed onto the disc's
                            // centre in a heap. The build script encodes the centroid in the
                            // name for exactly this reason; see it for the format.
                            restX = (ex - 1000) / 10000f,
                            restY = (ey - 1000) / 10000f,
                            mass = perMille / 999f
                        )
                    }
                    .sortedBy { it.first }
                    .map { it.second }

                // The album's own artwork, continuous across every crack, because the shards
                // carry the disc's UV convention unchanged. Safe to share the active case's
                // texture: a removal cannot start while a case is open.
                nodes.shardMaterials = materialInstances.flatten()
                nodes.shardMaterials.forEach { instance ->
                    instance.setParameter("baseColorMap", discTexture, sampler)
                    val material = instance.material
                    if (material.hasParameter("metallicFactor")) {
                        instance.setParameter("metallicFactor", DISC_LABEL_METALLIC)
                    }
                    if (material.hasParameter("roughnessFactor")) {
                        instance.setParameter("roughnessFactor", DISC_LABEL_ROUGHNESS)
                    }
                    if (material.hasParameter("reflectance")) {
                        instance.setParameter("reflectance", DISC_LABEL_REFLECTANCE)
                    }
                }
            }
        )
    }
}

/**
 * Sets how hard the fragments are glowing, 0..1.
 *
 * Cold, against the room's amber — DESIGN.md keeps saturated colour to the underlights, and
 * white going blue reads as not belonging to this room at all, which is what makes the strike
 * feel like it came from outside it.
 *
 * The peak is far above 1.0 on purpose: bloom is thresholded, so anything at or below it
 * cannot flare at all. That is the same trap the underlight beam shipped with originally.
 */
private fun DiscardNodes.setGlow(amount: Float) {
    val v = amount.coerceIn(0f, 1f) * FLASH_EMISSION
    shardMaterials.forEach { instance ->
        val material = instance.material
        if (material.hasParameter("emissiveFactor")) {
            instance.setParameter("emissiveFactor", v * 0.82f, v * 0.90f, v)
        }
        if (material.hasParameter("emissiveStrength")) {
            instance.setParameter("emissiveStrength", 1f + amount * 2.5f)
        }
    }
}

private const val SHARD_PREFIX = "shard_"


/** Peak emission on the fragments at the strike. Well past bloom's threshold, so it flares. */
private const val FLASH_EMISSION = 5.5f

/**
 * Advances one frame of a removal, driving the held case, the fragments and the flash.
 * Returns true on the frame it finishes.
 *
 * The caller owns *when* the album leaves the database; this owns only what is on screen. The
 * two are deliberately decoupled — the row stays until the animation ends, so the pool slot
 * keeps pointing at it and cannot be handed to a different record mid-flight.
 */
fun driveDiscard(
    nodes: DiscardNodes,
    active: ActiveCaseNodes,
    dt: Float,
    panX: Float,
    panY: Float,
    reduceMotion: Boolean
): Boolean {
    val clock = nodes.clock ?: return false
    val advanced = clock + dt
    nodes.clock = advanced

    val t = advanced / CaseDiscard.DURATION_S
    val frame = CaseDiscard.frameAt(t, nodes.slotX, nodes.slotY, panX, panY)

    if (reduceMotion) {
        // DESIGN.md drops ambient motion but keeps deliberate transitions, and this is one —
        // the user just confirmed it. What it loses is the tumbling and the flash, which are
        // the parts carrying vestibular motion and a hard luminance spike.
        active.root?.position = Position(panX, panY, 0f)
        active.root?.scale = Scale((1f - t).coerceIn(0.001f, 1f))
        nodes.group?.isVisible = false
        nodes.setGlow(0f)
    } else {
        active.root?.position = Position(
            frame.caseX,
            frame.caseY,
            frame.caseZ
        )
        active.root?.rotation = Rotation(frame.caseRotX, frame.caseRotY, frame.caseRotZ)
        active.root?.scale = Scale(1f)
        active.hinge?.rotation = Rotation(0f, frame.hingeRotY, 0f)

        // Whole until it is struck, fragments after — never both. The fragment set cannot
        // stand in for an intact disc: its pieces are bevelled and their cut edges jittered
        // independently, so at zero offset there are still seams between them and the disc
        // reads as already broken before anything has hit it.
        active.disc?.isVisible = frame.wholeDiscVisible

        val group = nodes.group
        if (group != null) {
            group.isVisible = frame.shardsVisible
            // Rides with the case, in the tray, so the lid shuts over the pieces.
            group.position = Position(
                frame.caseX,
                frame.caseY,
                frame.caseZ + WallDimens.TRAY_FRONT_Z + 0.0014f
            )
            group.rotation = Rotation(frame.caseRotX, frame.caseRotY, frame.caseRotZ)

            val since = CaseDiscard.secondsSinceImpact(t)
            nodes.shards.forEachIndexed { index, piece ->
                val shard = CaseDiscard.shardAt(
                    index, piece.restX, piece.restY, piece.mass, since
                )
                piece.node.isVisible = frame.shardsVisible
                // Offsets add to where the piece already rests, so each fragment appears
                // exactly where that part of the disc was and then cracks away from it.
                piece.node.position = Position(
                    piece.restX + shard.offsetX,
                    piece.restY + shard.offsetY,
                    shard.offsetZ
                )
                // About its own centroid, because the build script put each origin there.
                piece.node.rotation = Rotation(shard.tiltX, shard.tiltY, shard.tiltZ)
            }
        }

        // The strike itself: the fragments blaze from within and fade over a quarter second.
        nodes.setGlow(frame.flash)
    }

    if (!frame.done) return false

    // Hand everything back clean. The active case in particular: the open sequence writes its
    // transform every frame while a case is open, but nothing resets it otherwise, so a
    // removal that left it rotated would show the *next* opened album mid-tumble.
    nodes.clock = null
    nodes.albumId = null
    nodes.group?.isVisible = false
    nodes.setGlow(0f)
    active.root?.isVisible = false
    active.root?.rotation = Rotation(0f, 0f, 0f)
    active.root?.scale = Scale(1f)
    active.hinge?.rotation = Rotation(0f, 0f, 0f)
    active.disc?.isVisible = false
    return true
}
