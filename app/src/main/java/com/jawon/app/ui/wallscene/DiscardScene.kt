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
 * The disc that breaks when an album is removed.
 *
 * Lives in its own file rather than inside `WallSceneScreen`, which is already two thousand
 * lines and is where every 3D bug in this project has ended up being traced. The discard owns
 * a small, self-contained set of nodes and one per-frame function, so it can be read — and
 * changed — without holding the rest of the scene in your head.
 *
 * **The shards are the disc.** `disc_shards.glb` holds 27 fragments that, at rest, sit
 * exactly where a whole disc would; [CaseDiscard.shardAt] returns zero offsets until the
 * impact, so the same nodes read as one disc falling and then as the pieces it comes
 * apart into. That is why there is no separate disc model here — a second one would need its
 * own `FilamentInstance`, and would have to be swapped for the shards on the exact frame of
 * the break.
 *
 * **One discard at a time.** The nodes are allocated once and reused for whichever album is
 * being removed, which is safe because a long-press is ignored while one is running — the
 * same "never two cases mid-animation" guarantee the open sequence gets structurally.
 */
class DiscardNodes {
    /** Parent of the shards; placed at the case being removed. */
    var group: Node? = null

    /**
     * The fragments, each paired with what the geometry knows about it.
     *
     * Both facts come off the glTF node rather than being duplicated in Kotlin: the build
     * script puts each shard's origin at its own centroid, so [Piece.restX]/[Piece.restY] is
     * simply where the node already sits, and it encodes the piece's share of the disc's area
     * in the node's name. Keeping the layout in one place is the point — a second copy of it
     * here would drift the first time the fracture pattern changed.
     */
    var shards: List<Piece> = emptyList()

    /** One fragment: its node, where it rested in the whole disc, and its relative mass. */
    class Piece(val node: Node, val restX: Float, val restY: Float, val mass: Float)

    /** Which album is being removed, or null when nothing is. */
    var albumId: String? = null

    /** Seconds into [CaseDiscard.DURATION_S], or null when idle. */
    var clock: Float? = null

    val isRunning: Boolean get() = clock != null
}

/**
 * Mounts the discard's nodes, hidden.
 *
 * Mounted hidden and never destroyed, for the same reason the wall's case slots are pooled:
 * nothing in this scene should be torn down and rebuilt on a state change. See `CaseSlots`
 * for the failure that discipline exists to prevent.
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
                // Broken plastic still catches the lamp, but it must not cast shadows onto
                // the shelf it is falling past — 27 tumbling shadow casters for half a
                // second is a lot of shadow-map churn for something nobody is looking at.
                isShadowCaster = false
                isShadowReceiver = false

                // Read the fragments off the asset by the names the Blender script gave
                // them: `shard_<index>_<per-mille area>`.
                nodes.shards = this.nodes
                    .mapNotNull { node -> node.name?.let { name -> node to name } }
                    .filter { (_, name) -> name.startsWith(SHARD_PREFIX) }
                    .mapNotNull { (node, name) ->
                        val parts = name.removePrefix(SHARD_PREFIX).split('_')
                        val index = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                        val perMille = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                        val rest = node.position
                        node.isVisible = false
                        index to DiscardNodes.Piece(
                            node = node,
                            restX = rest.x,
                            restY = rest.y,
                            mass = perMille / 999f
                        )
                    }
                    .sortedBy { it.first }
                    .map { it.second }

                // The album's own artwork, continuous across the cracks, because the shards
                // carry the disc's UV convention unchanged. Safe to reuse the active case's
                // texture: removal only happens from the wall, and no case can be open then.
                materialInstances.forEach { primitives ->
                    primitives.forEach { instance ->
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
            }
        )
    }
}

private const val SHARD_PREFIX = "shard_"

/**
 * Advances one frame of a discard, moving both the case on the shelf and the disc coming out
 * of it. Returns true on the frame it finishes.
 *
 * The caller owns *when* the album leaves the database; this owns only what is on screen. The
 * two are deliberately decoupled — the row stays until the animation ends, so the pool slot
 * keeps pointing at it and cannot be handed to a different record mid-flight.
 */
fun driveDiscard(
    nodes: DiscardNodes,
    slot: CaseSlot,
    dt: Float,
    reduceMotion: Boolean
): Boolean {
    val clock = nodes.clock ?: return false
    val advanced = clock + dt
    nodes.clock = advanced

    val t = advanced / CaseDiscard.DURATION_S
    val frame = CaseDiscard.frameAt(t)
    val root = slot.root

    if (reduceMotion) {
        // DESIGN.md drops ambient motion under reduced motion but keeps deliberate
        // transitions, and this is one — the user just confirmed it. What it loses is the
        // tumbling, which is the part that carries vestibular motion: the case simply
        // shrinks away and the disc never appears.
        val shrink = (1f - t).coerceIn(0f, 1f)
        root?.scale = Scale(shrink)
        nodes.group?.isVisible = false
    } else {
        root?.position = Position(
            slot.baseX,
            slot.baseY + frame.caseY,
            slot.baseZ + frame.caseZ
        )
        // Tipped about X, so the case leans back and the disc slides out of its open edge
        // toward the viewer rather than sideways.
        root?.rotation = Rotation(frame.caseTipDeg, 0f, 0f)
        root?.scale = Scale(frame.caseScale.coerceAtLeast(0.001f))

        val group = nodes.group
        if (group != null) {
            group.isVisible = frame.discVisible
            group.position = Position(
                slot.baseX,
                slot.baseY + frame.discY,
                slot.baseZ + frame.discZ
            )
            // The whole disc tips as it falls, so it meets the shelf edge-on. After the
            // impact the group stops turning and the fragments carry the motion.
            group.rotation = Rotation(frame.discTiltDeg, 0f, 0f)

            val since = CaseDiscard.secondsSinceImpact(t)
            nodes.shards.forEachIndexed { index, piece ->
                val shard = CaseDiscard.shardAt(
                    index = index,
                    restX = piece.restX,
                    restY = piece.restY,
                    mass = piece.mass,
                    seconds = since
                )
                piece.node.isVisible = shard.scale > 0.01f
                // Offsets are added to where the piece already rests, so before the impact
                // every fragment sits exactly where it did in the whole disc.
                piece.node.position = Position(
                    piece.restX + shard.offsetX,
                    piece.restY + shard.offsetY,
                    shard.offsetZ
                )
                // About its own centroid, because the build script put each origin there.
                piece.node.rotation = Rotation(shard.spinX, shard.spinY, shard.spinZ)
                piece.node.scale = Scale(shard.scale)
            }
        }
    }

    if (!frame.done) return false

    // Hand everything back clean. The slot's scale in particular — leaving it at zero is
    // what broke the wall the first time this animation shipped, because the pool is
    // index-keyed and every album that later landed on this slot rendered invisible.
    nodes.clock = null
    nodes.albumId = null
    nodes.group?.isVisible = false
    nodes.shards.forEach { it.node.isVisible = false }
    root?.scale = Scale(1f)
    root?.rotation = Rotation(0f, 0f, 0f)
    return true
}
