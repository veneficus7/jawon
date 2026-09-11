package com.jawon.app.ui.wallscene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.google.android.filament.LightManager
import com.google.android.filament.gltfio.FilamentInstance
import dev.romainguy.kotlin.math.Float4
import com.jawon.app.ui.wallscene.WallDimens.CASE_D
import com.jawon.app.ui.wallscene.WallDimens.CASE_H
import com.jawon.app.ui.wallscene.WallDimens.CASE_W
import com.jawon.app.ui.wallscene.WallDimens.PLATE_LIFT
import com.jawon.app.ui.wallscene.WallDimens.TRAY_FRONT_Z
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.node.ImageNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
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
/**
 * Node name the disc answers to when SceneView picks it. Distinct from the album ids the
 * shelved cases carry, so the browse-tap path can never mistake one for the other — the
 * leading NUL is the sentinel doing that work, since no album id can contain one.
 *
 * **Written as an escape, deliberately.** This was a raw 0x00 byte in the source for most
 * of the project's life, which made git classify the whole file as binary — so `ActiveCase.kt`
 * never produced a readable diff in any commit. The value is identical; only the spelling
 * changed. Do not "tidy" it back into a literal control character.
 */
const val DISC_NODE_NAME = "\u0000disc"

class ActiveCaseNodes {
    var root: Node? = null
    var hinge: Node? = null
    var disc: Node? = null

    /**
     * The disc's renderable, held separately from [disc] because picking is a property of
     * the renderable rather than of the group above it — and the disc has to stop being
     * pickable whenever it is hidden. See the visibility effect in `WallSceneScreen`.
     *
     * Typed as [ModelNode] rather than [Node] so `WallSceneScreen` can reach its material
     * instances to bind the album's disc art.
     */
    var discMesh: ModelNode? = null
    var spineArt: ImageNode? = null
    var coverArt: ImageNode? = null

    /**
     * The lit materials the two art plates are rendered with. See `CaseSlot.coverMaterial` —
     * SceneView's `ImageNode` is unlit, so without these the held case's artwork is the one
     * surface in the room that ignores the light falling on it.
     */
    var coverMaterial: com.google.android.filament.MaterialInstance? = null
    var spineMaterial: com.google.android.filament.MaterialInstance? = null

    /**
     * The held case's own underlight, and the bulb beneath it.
     *
     * The room has two lights and this is one of them — the same album underlight the shelf
     * gives every record, following the record when it is pulled out. It has to: the disc
     * lifts clear of the case and there is nothing else in the room to light it. Removing the
     * key light left it rendering nearly black, which is how this got noticed.
     *
     * Carrier and bulb rather than one node for the reason `RoomNodes` records — a `LightNode`'s
     * `apply` runs against a `LightManager.Builder`, so the node cannot be captured where it is
     * declared.
     */
    var underCarrier: Node? = null
    private var underBulb: LightNode? = null

    /** The same light again, riding the disc — see the note where it is mounted. */
    var discUnderCarrier: Node? = null
    private var discUnderBulb: LightNode? = null

    /** Tints the held record's underlight, exactly as the shelf's does. */
    fun setUnderHue(hue: Float) {
        if (hue == appliedUnderHue) return
        val caseLight = underBulb
            ?: underCarrier?.childNodes?.firstNotNullOfOrNull { it as? LightNode }
                ?.also { underBulb = it }
        val discLight = discUnderBulb
            ?: discUnderCarrier?.childNodes?.firstNotNullOfOrNull { it as? LightNode }
                ?.also { discUnderBulb = it }
        if (caseLight == null && discLight == null) return
        appliedUnderHue = hue
        val rgb = beamRgb(hue)
        val peak = maxOf(rgb[0], rgb[1], rgb[2]).coerceAtLeast(1e-4f)
        val colour = Float4(
            mixToward(rgb[0] / peak, RoomLighting.UNDER_WHITE[0]),
            mixToward(rgb[1] / peak, RoomLighting.UNDER_WHITE[1]),
            mixToward(rgb[2] / peak, RoomLighting.UNDER_WHITE[2]),
            1f
        )
        caseLight?.color = colour
        discLight?.color = colour
    }

    private var appliedUnderHue = Float.NaN
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
    materialLoader: MaterialLoader,
    trayModel: FilamentInstance?,
    lidModel: FilamentInstance?,
    discModel: FilamentInstance?
) {
    // The three models load off the main thread, so they arrive as null and become
    // non-null exactly once. Nothing mounts until all three are in.
    //
    // This is safe *because* the transition only ever runs that one way. If a model
    // instance could go back to null the group would unmount, its ImageNodes would be
    // destroyed, and the process would abort — the failure this whole file is shaped
    // around. Do not make this condition depend on anything that can flip back.
    if (trayModel == null || lidModel == null || discModel == null) return

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
            nodes.discMesh = null
            nodes.spineArt = null
            nodes.coverArt = null
            nodes.underCarrier = null
            nodes.discUnderCarrier = null
            // Not destroyed — trap 1. They stay bound to the plates' textures for as long as
            // the scene lives.
            nodes.coverMaterial = null
            nodes.spineMaterial = null
        }
    }

    // The case group. Position and rotation are written every frame by the sequence driver.
    //
    // Mounted HIDDEN. It used to be safe to mount visible, because this group existed from
    // the scene's first frame and `WallSceneScreen`'s visibility effect ran after it and
    // switched it off. Loading the models made the group appear several frames late — long
    // after that effect had already run against null handles and done nothing — so it
    // arrived visible, parked at the wall's origin, a grey slab across the shelves.
    Node(apply = {
        nodes.root = this
        isVisible = false
    }) {
        // **The record's own underlight, carried out of the shelf with it.**
        //
        // The room has two lights and this is one of them: the same album underlight every
        // record on the wall gets, following this one while it is held. It is not optional —
        // the disc lifts clear of the case and there is nothing else in the room to light it,
        // so without this the centrepiece of playback renders nearly black. That is exactly
        // what happened the moment the key light was removed.
        //
        // Reaches further than the shelf's does, because it has further to reach: the disc
        // rises to close to full frame height above the foot of the case, where the shelf's
        // job ends at the top of a sleeve.
        //
        // A child of the case group, so it travels and turns with it. The swing during the
        // turn is deliberate rather than tolerated — a highlight that moves across the lid as
        // the case comes round is what the sequence is for.
        Node(apply = { nodes.underCarrier = this }) {
            LightNode(
                type = LightManager.Type.POINT,
                position = Position(
                    0f,
                    -CASE_H / 2f + RoomLighting.UNDER_LIFT,
                    CASE_D / 2f + RoomLighting.UNDER_FORWARD
                ),
                apply = {
                    intensityCandela(RoomLighting.HELD_UNDER_CANDELA)
                    falloff(RoomLighting.HELD_UNDER_FALLOFF)
                    castShadows(false)
                    // The album's own channel, the same as on the shelf — see
                    // RoomLighting.UNDER_LIGHT_CHANNEL. Only the plates and the disc label
                    // opt in, so this cannot put a hotspot in the lid's glass.
                    lightChannel(0, false)
                    lightChannel(RoomLighting.UNDER_LIGHT_CHANNEL, true)
                }
            )
        }

        // The tray, from tools/models/build_models.py — a moulded shell with real wall
        // thickness, a spindle rosette and hinge knuckles, where this used to be one box.
        //
        // No position argument: the build script works in exactly these axes, so the
        // model's vertices are already in this group's local frame. Its material comes
        // from the glb as well, carrying the KHR_materials_clearcoat that SceneView's
        // stock material has no parameter for.
        ModelNode(
            trayModel,
            apply = {
                isTouchable = false
                // The case in your hand is lit like the ones still on the shelf.
                isShadowCaster = true
                isShadowReceiver = true
            }
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
                isShadowCaster = false
                nodes.spineArt = this
                if (nodes.spineMaterial == null) {
                    nodes.spineMaterial = materialLoader.createTextureInstance(
                        texture,
                        metallic = 0f,
                        roughness = ACT_PAPER_ROUGHNESS,
                        reflectance = ACT_PAPER_REFLECTANCE
                    ).also {
                        materialInstance = it
                        renderableManager.setLightChannel(
                            renderableInstance,
                            RoomLighting.UNDER_LIGHT_CHANNEL,
                            true
                        )
                    }
                }
            }
        )
        // The spindle that used to be a bare cylinder here is part of the tray model now —
        // a central button ringed by the eight fingers that actually grip a disc. It is
        // uncovered exactly when the disc lifts off it, at close to full frame height.
        //
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
                    isShadowCaster = false
                    nodes.coverArt = this
                    // Lit, like the shelf's. See ActiveCaseNodes.coverMaterial.
                    if (nodes.coverMaterial == null) {
                        nodes.coverMaterial = materialLoader.createTextureInstance(
                            texture,
                            metallic = 0f,
                            roughness = ACT_COVER_ROUGHNESS,
                            reflectance = ACT_PAPER_REFLECTANCE
                        ).also {
                        materialInstance = it
                        renderableManager.setLightChannel(
                            renderableInstance,
                            RoomLighting.UNDER_LIGHT_CHANNEL,
                            true
                        )
                    }
                    }
                }
            )
            // Clear polycarbonate front, closing flush with the case's outer face.
            //
            // Also at local zero, and that is the point: the build script gives the lid
            // mesh its **origin on the hinge axis** rather than at the lid's centre, so
            // parenting it here puts the pivot exactly where a real one is. A model
            // centred on itself would swing about its middle instead, which looks like
            // an animation bug and is a modelling one.
            ModelNode(
                lidModel,
                apply = { isTouchable = false }
            )
        }
    }

    // The disc rides to a camera-relative position, so it is a sibling of the case rather
    // than a child of it — same as the prototype.
    Node(apply = {
        nodes.disc = this
        isVisible = false
    }) {
        // **The record's underlight travels with the record.**
        //
        // The disc is a sibling of the case, not a child of it, because it rides to a
        // camera-relative position — roughly half a metre in front of the shelf by the end of
        // the lift. A light parented to the case therefore cannot reach it at any intensity,
        // which is exactly what happened: with the key light gone the disc rendered nearly
        // black while the underlight sat back at the case throwing a perfectly good pool on
        // the tray. Two carriers, one light: the album's underlight, on whichever piece of the
        // album is being looked at.
        //
        // Below and in front, for the same reason the shelf's is — a disc face is a plane and
        // a light in that plane contributes nothing to it.
        Node(apply = { nodes.discUnderCarrier = this }) {
            LightNode(
                type = LightManager.Type.POINT,
                position = Position(
                    0f,
                    -WallDimens.DISC_RADIUS - RoomLighting.DISC_UNDER_DROP,
                    RoomLighting.DISC_UNDER_FORWARD
                ),
                apply = {
                    intensityCandela(RoomLighting.HELD_UNDER_CANDELA)
                    falloff(RoomLighting.HELD_UNDER_FALLOFF)
                    castShadows(false)
                    // The album's own channel, the same as on the shelf — see
                    // RoomLighting.UNDER_LIGHT_CHANNEL. Only the plates and the disc label
                    // opt in, so this cannot put a hotspot in the lid's glass.
                    lightChannel(0, false)
                    lightChannel(RoomLighting.UNDER_LIGHT_CHANNEL, true)
                }
            )
        }

        // A real disc now: 1.2mm thick with a broken edge, where this was an infinitely
        // thin annulus whose every normal was +Z. docs/materials.md records what that cost
        // — `dot(N,V)` spanned 1.0 to 0.994 across the entire face, so any Fresnel term
        // vanished and the disc could only ever read as a flat grey circle.
        //
        // Its album art is bound by WallSceneScreen, which swaps `baseColorMap` on this
        // model's own material rather than replacing the material — replacing it would
        // throw away the clearcoat that makes the lacquer read.
        ModelNode(
            discModel,
            // Casts: the disc rising out of the tray throwing a shadow back onto the case
            // is most of what sells the lift.
            // Touchable, and named, so SceneView's own picking tells the gesture handlers
            // when a drag started on the platter. Deriving the disc's screen circle
            // arithmetically instead was tried and disagreed with the device by a fifth of
            // the screen — the focal-length-to-FOV mapping is not what the obvious formula
            // assumes. Picking sidesteps the question entirely.
            apply = {
                // Starts unpickable: nothing is open, and a hidden-but-pickable disc
                // parked over the rack swallows taps meant for the case behind it.
                isTouchable = false
                isShadowCaster = true
                isShadowReceiver = true
                name = DISC_NODE_NAME
                nodes.discMesh = this
                // The disc is lit by its album's underlight, the carrier riding it above — so
                // its renderables join that light's channel. A glTF model, so this is its
                // entities rather than a single node's instance.
                val rm = engine.renderableManager
                discModel.entities.forEach { entity ->
                    if (rm.hasComponent(entity)) {
                        rm.setLightChannel(
                            rm.getInstance(entity),
                            RoomLighting.UNDER_LIGHT_CHANNEL,
                            true
                        )
                    }
                }
            }
        )
    }
}

/**
 * The held case's art plates are the same printed card the shelf's are — see
 * `PAPER_ROUGHNESS` in `CaseSlots`. Duplicated rather than shared because these are private
 * to each file and the two are free to diverge: the held case is seen from 200mm away, the
 * shelf's from a metre.
 */
private const val ACT_PAPER_ROUGHNESS = 0.72f
private const val ACT_PAPER_REFLECTANCE = 0.5f
private const val ACT_COVER_ROUGHNESS = 0.60f
