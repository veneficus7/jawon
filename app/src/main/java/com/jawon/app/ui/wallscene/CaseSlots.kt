package com.jawon.app.ui.wallscene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import com.google.android.filament.MaterialInstance
import com.google.android.filament.gltfio.FilamentInstance
import com.jawon.app.ui.wallscene.WallDimens.CASE_D
import com.jawon.app.ui.wallscene.WallDimens.CASE_H
import com.jawon.app.ui.wallscene.WallDimens.CASE_W
import com.jawon.app.ui.wallscene.WallDimens.PLATE_LIFT
import com.jawon.app.ui.wallscene.WallDimens.PROUD_Z
import com.jawon.app.ui.wallscene.WallDimens.TRAY_FRONT_Z
import io.github.sceneview.SceneScope
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import com.google.android.filament.LightManager
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.node.ImageNode
import io.github.sceneview.node.LightNode
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
     * The lit materials the three art plates are rendered with, one per plate, made once.
     *
     * **SceneView's `ImageNode` is unlit, and that was the single biggest thing stopping this
     * room looking real.** `MaterialLoader.createImageInstance` builds from
     * `materials/image_texture.filamat`, whose `MAT_SHAD` chunk is `0x00` — unlit — against
     * `0x01` for `opaque_textured`. Read out of the shipped blob rather than guessed; the file
     * sizes say it too, since a lit material compiles every lighting variant and runs 1.2-1.4MB
     * where the unlit ones are 15-115KB.
     *
     * So every album cover on the wall rendered at flat texture brightness, ignoring the key
     * light, the shelf strips, the wash and the spill entirely. The records — the subject of
     * the whole room — were the one thing in it no light could touch, which is most of what
     * "it looks like cheap plastic" was describing.
     *
     * It was measurable all along and the measurement was being read backwards: across four
     * separate lighting changes the cover art came out at 106.1, 106.5, 106.9, 107.0. That
     * looked like the records holding steady while the room came up around them. It was the
     * covers being untouchable.
     *
     * Held per slot and created once, because a node's `apply` runs on every recomposition and
     * building a `MaterialInstance` in there would leak one per frame.
     */
    var coverMaterial: MaterialInstance? = null
    var spineMaterial: MaterialInstance? = null
    var backMaterial: MaterialInstance? = null

    /** The printed back inlay, generated per album — see [CaseArt.bakeBack]. */
    var backArt: ImageNode? = null

    /**
     * The underlight this album levitates in. A SIBLING of [root], never a child: the case
     * turns and the beam must not turn with it, or the shaft of light swings around the bay
     * like a searchlight.
     */
    var beam: Node? = null

    /**
     * The beam's own material instances, so its colour can be set per album.
     *
     * Held here rather than reached for through [beam] because gltfio hands them out per
     * `FilamentInstance`: instancing shares the geometry and the textures, but each instance
     * gets its own material instances, which is exactly what makes a per-album colour
     * affordable at several hundred cases.
     */
    var beamMaterials: Array<MaterialInstance>? = null

    /**
     * The carrier this album's real underlight hangs from, and the bulb beneath it.
     *
     * Two nodes rather than one for the reason `RoomNodes` records: a `LightNode`'s `apply`
     * block runs against a `LightManager.Builder`, not against the node, so the node cannot be
     * captured where it is declared — and the carrier is a plain `Node`, so casting *it* to
     * `LightNode` yields null. The carrier is what moves; the bulb is what takes a colour.
     */
    var underCarrier: Node? = null
    private var underBulb: LightNode? = null

    private fun bulb(): LightNode? =
        underBulb ?: underCarrier?.childNodes?.firstNotNullOfOrNull { it as? LightNode }
            ?.also { underBulb = it }

    /**
     * Last hue written to [beamMaterials], so binding does not re-push the same colour into
     * native on every pass. `Float.NaN` because it never compares equal to itself, so the
     * first bind always writes.
     */
    private var appliedBeamHue = Float.NaN

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

    /**
     * Where this case is heading, as opposed to where it is.
     *
     * Shelves pack, so removing an album moves every record after it one slot along. Writing
     * that straight into [baseX] teleports them — the shelf heals in a single frame, which
     * is the one thing about it that reads as a database update rather than as objects on a
     * shelf. The frame driver eases [baseX] toward this instead.
     *
     * Only ever a *glide*: when a slot is handed a different album (browsing, pooling) it
     * must appear where it belongs immediately, so [bind] snaps both together in that case.
     * See [settleTowardTarget].
     */
    var targetX = 0f
    var targetY = 0f
    var targetZ = 0f

    /**
     * Ease this case toward where it belongs, frame-rate independently.
     *
     * The `1 - exp(-rate * dt)` form rather than a fixed fraction per frame, for the reason
     * `WallZoom` uses it: a plain lerp converges at a speed that depends on how often it is
     * called, and this app runs at both 60 and 120.
     *
     * **Lands exactly, and then does nothing.** An exponential approach never quite arrives, so
     * without the snap below every slot on the wall kept running an `exp` every frame for the
     * rest of the session to move itself by a millionth of a millimetre — and, worse, kept
     * reporting movement to everything downstream that only wants to know when a case has
     * actually gone somewhere. Inside [SETTLE_EPS] the two are the same place at this scale.
     */
    fun settleTowardTarget(dt: Float) {
        if (dt <= 0f) return
        val dx = targetX - baseX
        val dy = targetY - baseY
        val dz = targetZ - baseZ
        if (abs(dx) < SETTLE_EPS && abs(dy) < SETTLE_EPS && abs(dz) < SETTLE_EPS) {
            if (dx != 0f || dy != 0f || dz != 0f) snapToTarget()
            return
        }
        val k = 1f - kotlin.math.exp(-REPACK_RATE * dt)
        baseX += dx * k
        baseY += dy * k
        baseZ += dz * k
    }

    /**
     * Put the underlight at the foot of this case's bay, if it is not already there.
     *
     * **The beam does not move.** It stands on the board and never turns — the case above it
     * hovers and leans, the shaft of light does not — so its position changes only when a shelf
     * repacks around a deletion or the slot is handed to a different album. The frame driver was
     * nevertheless writing it every frame, and a position write is not free: SceneView composes
     * a transform matrix and pushes it across to Filament's transform manager, which for a wall
     * of visible bays came to something over a thousand pointless native writes a second.
     *
     * Guarded here rather than at the call site so the last-written place travels with the slot,
     * which is what makes it correct across pooling: a slot handed to a new album has a new
     * [baseX], so the comparison fails and the beam is placed. Trap 9 in ENGINEERING.md is the same
     * hazard from the other direction — this is the frame driver writing *less*, not `bind`
     * restoring less.
     */
    fun placeBeam(x: Float, y: Float, z: Float) {
        if (x == beamAtX && y == beamAtY && z == beamAtZ) return
        beam?.position = Position(x, y, z)
        // The real light rides with the emissive cone, lifted off the board and pushed
        // forward — see RoomLighting.UNDER_FORWARD for why forward is not optional.
        underCarrier?.position = Position(
            x,
            y + RoomLighting.UNDER_LIFT,
            z + RoomLighting.UNDER_FORWARD
        )
        beamAtX = x
        beamAtY = y
        beamAtZ = z
    }

    /**
     * Where [placeBeam] last put the beam. NaN so the first call always writes: NaN compares
     * false against everything, including itself.
     */
    private var beamAtX = Float.NaN
    private var beamAtY = Float.NaN
    private var beamAtZ = Float.NaN

    /**
     * Where this slot's underlight stands, and what colour it is, for the one real light the
     * room spends on it.
     *
     * The emissive geometry above is what every bay gets — one draw for the whole wall, which
     * is the only reason a wall of two hundred albums can each have their own glow. But
     * emissive geometry lights nothing: it blooms and it is not in the shading, so the colour
     * DESIGN.md calls "where the app's colour lives" never actually landed on the board or on
     * the case standing in it. A Filament spot per bay is not available at this count, so the
     * room spends exactly one, on whichever record is being looked at, and the frame driver
     * moves it. See `RoomLighting.SPILL_CANDELA`.
     *
     * NaN until [placeBeam] and [setBeamHue] have run, which is how the driver knows there is
     * nothing to point at yet.
     */
    val beamX: Float get() = beamAtX
    val beamY: Float get() = beamAtY
    val beamZ: Float get() = beamAtZ
    val beamHue: Float get() = appliedBeamHue

    /** Put this case where it belongs immediately, with no travel. */
    fun snapToTarget() {
        baseX = targetX
        baseY = targetY
        baseZ = targetZ
    }

    /** True when this slot is showing an album, so the frame driver can skip parked ones. */
    var occupied = false

    /**
     * Per-slot offset into the hover and turn cycles, so a shelf does not bob and spin in
     * lockstep like a chorus line. Derived from the album id, so a given album always floats
     * the same way and re-binding a slot to it does not make it jump.
     */
    var phase = 0f

    /**
     * Degrees the user has turned this album by hand, on top of its automatic presentation
     * turn. Persists after the finger lifts — letting go of a record you turned to look at
     * should not snap it back.
     */
    var manualTurn = 0f

    /**
     * Degrees per second still carried from a flick, decaying to nothing. This is what makes
     * a case feel like it has mass rather than being dragged on a rail.
     */
    var spinVelocity = 0f

    /**
     * Elapsed time since this slot started being discarded, in seconds, or `null` while it is
     * not — see [CaseDiscard]. A field [bind] never touches, for the same reason
     * [manualTurn] and [spinVelocity] are not: it is transient per-frame state, not part of
     * where this slot sits or which album is in it.
     *
     * **This is what makes deferring the actual deletion safe.** `WallSceneScreen` does not
     * call `deleteAlbum` the moment the user confirms — it waits out the animation first, so
     * the album stays in the database and [bind] keeps pointing this exact pool index at it
     * for the whole ~0.6s discard. Deleting immediately would drop the album from `layout` on
     * the next recomposition, shift every album after it up one position in the visible list,
     * and hand this pool index to a *different* record mid-flight — exactly the class of bug
     * ENGINEERING.md's "never destroy a node, never repoint one mid-animation" warnings are about,
     * just with a re-bind standing in for a destroy.
     */
    var discardClock: Float? = null

    /** Set once, when the discard starts, and read every frame by [discardClock]'s driver. */
    var discardDirection = 1f

    /**
     * Tint this album's underlight.
     *
     * Testers asked for the colour chosen at upload to become the album's underlight. The
     * hue is already resolved for every album — [CaseArtCache] takes the one stored on the
     * album if the user set it, then the cover's dominant hue, then a stable per-album
     * fallback — so every record on the wall gets its own colour with no extra step, and a
     * user who does pick one simply overrides it.
     *
     * **Guarded by `hasParameter`.** These materials come from the glTF asset rather than
     * from a `.mat` in this repository, so the parameter list is whatever gltfio generated
     * for the beam's emissive material. Filament logs and ignores an unknown parameter name,
     * which would leave every beam stuck on the asset's authored colour with nothing to
     * explain why — so the name is checked rather than assumed, and both spellings gltfio
     * might have produced are tried.
     */
    fun setBeamHue(hue: Float) {
        if (hue == appliedBeamHue) return
        val materials = beamMaterials ?: return
        appliedBeamHue = hue

        val rgb = beamRgb(hue)
        // The bulb takes a muted version of the same hue. A light's colour is a normalised
        // tint — brightness is intensityCandela — so beamRgb, which is authored at BEAM_VALUE
        // for an emissive material, has to come back to a peak of 1 first.
        bulb()?.let { light ->
            val peak = maxOf(rgb[0], rgb[1], rgb[2]).coerceAtLeast(1e-4f)
            light.color = Float4(
                mixToward(rgb[0] / peak, RoomLighting.UNDER_WHITE[0]),
                mixToward(rgb[1] / peak, RoomLighting.UNDER_WHITE[1]),
                mixToward(rgb[2] / peak, RoomLighting.UNDER_WHITE[2]),
                1f
            )
        }
        materials.forEach { instance ->
            val material = instance.material
            if (material.hasParameter(EMISSIVE_PARAM)) {
                instance.setParameter(EMISSIVE_PARAM, rgb[0], rgb[1], rgb[2])
            }
            if (material.hasParameter(BASE_COLOR_PARAM)) {
                instance.setParameter(BASE_COLOR_PARAM, rgb[0], rgb[1], rgb[2], 1f)
            }
        }
    }

    /**
     * Dims this album's underlight to [amount] of its normal brightness.
     *
     * Used while the album is being removed, so its light leaves with it instead of being cut
     * on the first frame — a shelf light that vanishes the instant a case moves reads as a
     * fault in the shelf rather than as the record going.
     *
     * Deliberately re-derives the colour rather than scaling whatever is there, so repeated
     * calls cannot compound into black. [appliedBeamHue] is left alone, which means the next
     * `bind` of this slot restores full brightness on its own.
     */
    /**
     * Show or hide the whole underlight — the emissive cone and the light it now stands for.
     *
     * One call rather than two writes at four call sites: they are the same fixture, and the
     * bug where a pooled slot keeps a stale piece of the last album is the single most common
     * failure in this file.
     */
    fun setBeamVisible(visible: Boolean) {
        beam?.isVisible = visible
        underCarrier?.isVisible = visible
        // **Hiding the carrier does not switch the light off** — trap 24, "hiding a node does
        // not switch off a light in it": SceneView's visibility reaches renderables and never a
        // light. So an empty slot's bulb kept shining wherever the slot was parked, on the
        // covers around it. Dark by intensity, and back to full when the slot has an album.
        bulb()?.setIntensityCandela(if (visible) RoomLighting.UNDER_CANDELA else 0f)
    }

    fun setBeamGlow(amount: Float) {
        val materials = beamMaterials ?: return
        if (appliedBeamHue.isNaN()) return
        val rgb = beamRgb(appliedBeamHue)
        val k = amount.coerceIn(0f, 1f)
        materials.forEach { instance ->
            if (instance.material.hasParameter(EMISSIVE_PARAM)) {
                instance.setParameter(EMISSIVE_PARAM, rgb[0] * k, rgb[1] * k, rgb[2] * k)
            }
        }
    }

    private companion object {
        const val EMISSIVE_PARAM = "emissiveFactor"
        const val BASE_COLOR_PARAM = "baseColorFactor"

        /**
         * How briskly a case closes the gap to its slot, per second, as an exponential rate.
         *
         * 7 puts a one-bay shift (150mm) at roughly 400ms of visible travel before it is
         * inside a millimetre of home — long enough to read as the record being slid along
         * the shelf rather than cutting, short enough that the wall does not feel loose
         * under a pan. Deliberately unhurried: this plays right after the removal, and the
         * shelf healing is the last beat of that, not a separate event.
         */
        const val REPACK_RATE = 7f

        /**
         * Close enough to home to stop moving, in metres.
         *
         * A tenth of a millimetre, against a case 125mm wide and shelves 150mm apart: three
         * orders of magnitude below anything the eye can resolve on this wall, and below the
         * point where the transform even changes the rendered pixels. What it buys is the
         * arrival — see [settleTowardTarget].
         */
        const val SETTLE_EPS = 0.0001f
    }
}

/**
 * An album's hue as linear RGB for the underlight, at [BEAM_VALUE] so it clears bloom's
 * threshold and actually glows rather than sitting there as a coloured shape.
 *
 * Saturation is held below full: a fully saturated beam reads as a stage gel, and DESIGN.md
 * wants a shop lit warmly, not a nightclub. The conversion is done here rather than through
 * `android.graphics.Color` because Filament wants linear values and that returns sRGB.
 */
internal fun beamRgb(hue: Float): FloatArray {
    val h = ((hue % 1f) + 1f) % 1f * 6f
    val sector = floor(h).toInt()
    val f = h - sector
    val v = BEAM_VALUE
    val p = v * (1f - BEAM_SATURATION)
    val q = v * (1f - BEAM_SATURATION * f)
    val t = v * (1f - BEAM_SATURATION * (1f - f))
    return when (sector % 6) {
        0 -> floatArrayOf(v, t, p)
        1 -> floatArrayOf(q, v, p)
        2 -> floatArrayOf(p, v, t)
        3 -> floatArrayOf(p, q, v)
        4 -> floatArrayOf(t, p, v)
        else -> floatArrayOf(v, p, q)
    }
}

/**
 * Above 1.0 on purpose, and no longer far above it.
 *
 * Bloom runs thresholded — DESIGN.md asks for glow "on the lamp and the disc's specular
 * highlight only, not a global haze" — so anything at or below 1.0 cannot bloom at all. That
 * was the bug the beam had when first built: it emitted 0.3 and was structurally incapable of
 * glowing. Hence 4.5, which fixed it.
 *
 * **4.5 then became the opposite problem, and a measurement found it.** A vertical luminance
 * profile down a lit sleeve came out bright at the top, dark in the middle and bright again at
 * the bottom — a record lit from above by the new shelf strip *and* from below by this. Every
 * one of the owner's reference photographs lights a record from above only; light arrives at
 * the top of a sleeve and dies toward the bottom, and nothing glows upward out of a shelf.
 *
 * So this is now spill rather than a source: enough to clear the threshold and keep the pool of
 * album colour DESIGN.md calls "where the app's colour lives", not enough to compete with the
 * strip above it for what is lighting the record.
 */
private const val BEAM_VALUE = 1.75f

/** Coloured, not gelled. See [beamRgb]. */
/**
 * The printed card an album's art sits on — except that on a real CD none of these three
 * plates is bare card: every one of them is read *through* polystyrene.
 *
 * **`reflectance` is a 0..1 remap, not a percentage** — `F0 = 0.16 * reflectance²`, so 0.5 is
 * the ~4% a dielectric really returns, and is also SceneView's own default. Trap 3, which has
 * already turned this room's wall black once.
 *
 * **Smoothed from 0.72 on 2026-09-13**, with [COVER_ROUGHNESS]. Spines and inlays stop well
 * short of the cover: a spine is read edge-on down a shelf, where a near-mirror strobes as
 * the wall pans.
 */
private const val PAPER_ROUGHNESS = 0.34f
private const val PAPER_REFLECTANCE = 0.5f

/**
 * The cover is the front of a jewel case, and the front of a jewel case is polished plastic.
 *
 * It was 0.60 — "a touch smoother than the spine, but only a touch", on the reasoning that a
 * glossy plate under a glossy shell stacks two speculars and reads as laminate. **That
 * reasoning expired when the shell was cleared to 8% opacity** so the art could be seen
 * through it (`WallSceneScreen.SHELL_TINT`): a specular multiplied by 0.08 is not a second
 * specular, and the owner's verdict on the result was that the cases had "lost their
 * shininess". Nothing was reflecting the room any more, so the plate has to be the plastic.
 *
 * **What actually shines here is the reflection cubemap, not a lamp.** A case front is flat
 * and faces the camera, so the half-vector to any fixture in this room sits ~33° off its
 * normal — outside a tight GGX lobe, which is why no amount of candela ever put a highlight
 * on a sleeve. The environment is a different matter: the mirror direction points back past
 * the camera into `RoomLighting.buildRoomCubemap`, which has the tube's strips written across
 * it at eleven times the void. At 0.60 those strips are mush; here they resolve into bands
 * that slide as the wall pans, and tilt a case and they sweep. See the note in
 * `RoomLighting.ambientSphericalHarmonics`: without those reflections "a jewel case mirrors
 * nothing and reads as painted card".
 */
private const val COVER_ROUGHNESS = 0.16f

/** Mixes a normalised channel toward the warm lamp by [RoomLighting.UNDER_TINT]. */
internal fun mixToward(hue: Float, white: Float): Float =
    white + (hue - white) * RoomLighting.UNDER_TINT

private const val BEAM_SATURATION = 0.62f

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
 * The wall was a packed rack of spines and is now a gallery of bays: the owner chose to widen
 * the spacing so every album owns an area it can turn a full circle inside. See
 * [WallDimens.SLOT_PITCH] for the arithmetic that sets the bay width.
 */
object ShelfHover {

    /** How far a case rises off its shelf, in metres. Just enough to break contact. */
    const val LIFT = 0.011f

    /** The bob on top of that lift, and how long a full breath takes. */
    private const val BOB = 0.0022f
    private const val BOB_PERIOD_S = 5.4f

    /**
     * A full, continuous presentation turn — roughly 32 seconds a revolution.
     *
     * This is what the widened bays bought. Each album now owns a
     * [WallDimens.SLOT_PITCH] bay against a [WallDimens.SLOT_SWEEP] turning circle, so a
     * case can come all the way round — cover, spine, back, rear edge — without ever
     * reaching into its neighbour's air.
     *
     * Slow on purpose. Fast enough and it reads as a spinning icon in a menu; at this speed
     * you catch it mid-turn and it reads as an object being shown to you.
     */
    private const val TURN_DEG_PER_S = 11.2f

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

    /**
     * Y rotation in degrees. Continuous and phase-offset, so a shelf never turns in unison
     * and re-binding a slot never makes its case snap to a new angle.
     *
     * Starts at -90 so a case with zero phase presents its cover, not its spine: rotating
     * -90 about Y maps the case's +X face (the cover) onto +Z, toward the camera.
     */
    /**
     * @param speed fraction of full speed, `0f`..`1f`. See `AppPreferences.caseSpin`.
     *
     * At zero this returns [RESTING_TURN] rather than freezing each case at whatever angle
     * its phase puts it at — a stopped wall should look filed, not caught mid-sweep.
     *
     * Changing speed does move every case instantly, because the angle is derived from the
     * clock rather than accumulated. That is invisible in practice: the only control for it
     * is in Settings, which is a full screen, so the wall is never on screen while it changes.
     */
    fun turn(t: Float, phase: Float, speed: Float): Float =
        if (speed <= 0f) RESTING_TURN
        else (-90f + t * TURN_DEG_PER_S * speed + phase * 360f) % 360f

    /**
     * Where a case sits when turning is stopped: square on, cover facing you.
     *
     * The same -90 [turn] starts from, and for the same reason — rotating -90 about Y maps
     * the case's +X face onto +Z. Without the phase term every case lands on the same angle.
     */
    const val RESTING_TURN = -90f

    /**
     * How fast a hand-flicked case sheds its spin, as a fraction retained per second.
     *
     * Not friction in the physical sense — there is nothing for a levitating case to rub
     * against — so this is tuned for feel: long enough that a flick spins a couple of turns
     * and you watch it come round, short enough that it settles back into its slow automatic
     * presentation rather than whirling indefinitely.
     */
    const val SPIN_RETAIN_PER_S = 0.22f

    /** Screen pixels of horizontal drag per degree of turn. */
    const val DRAG_PX_PER_DEG = 2.4f

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
    // Which slot each album should land on.
    //
    // **An album keeps the slot it is already on**, and only albums new to the wall take
    // free ones. This used to be positional — `bindings[i]` went to `slots[i]` — which meant
    // panning one bay along handed every slot a different album and re-uploaded every spine
    // and cover on the wall, and deleting an album shuffled every survivor onto a new slot.
    //
    // Positional assignment is also what would defeat the repack glide: a case can only
    // *travel* to its new place if it is still the same case, on the same slot, from one
    // bind to the next. See [CaseSlot.targetX].
    val slotOfAlbum = HashMap<String, Int>(slots.size)
    slots.forEachIndexed { index, slot -> slot.body?.name?.let { slotOfAlbum[it] = index } }

    val assigned = arrayOfNulls<Pair<CaseLayout, CaseArtwork?>>(slots.size)
    val newcomers = ArrayList<Pair<CaseLayout, CaseArtwork?>>()
    bindings.forEach { binding ->
        val index = slotOfAlbum[binding.first.albumId]
        if (index != null && assigned[index] == null) assigned[index] = binding
        else newcomers += binding
    }
    var free = 0
    newcomers.forEach { binding ->
        while (free < assigned.size && assigned[free] != null) free++
        // The pool can be smaller than the visible set for a frame or two while it grows.
        // Dropping the overflow is what the positional version did too, just less visibly.
        if (free >= assigned.size) return@forEach
        assigned[free] = binding
        free++
    }

    slots.forEachIndexed { index, slot ->
        val binding = assigned[index]
        if (binding == null) {
            // Spare slot: parked, not removed.
            slot.root?.isVisible = false
            slot.body?.name = null
            slot.occupied = false
            slot.setBeamVisible(false)
            return@forEachIndexed
        }

        val (case, artwork) = binding
        slot.occupied = true
        slot.setBeamVisible(true)

        // Undo anything the discard animation did to this slot's scale.
        //
        // **This is the bug that broke the wall the first time the animation shipped.**
        // `CaseDiscard` shrinks a case to nothing on its way out, and this function — which
        // is what hands a pooled slot to its next album — restored position, visibility and
        // artwork but never scale. Since the pool is index-keyed and deliberately never
        // destroyed, one deletion left that slot at zero size permanently, and every album
        // that later landed on the same index rendered invisible. What gave it away was a
        // shelf of glowing underlights with no cases above them: the beam is a sibling of
        // the case root, so it was never scaled.
        //
        // Skipped while a discard is actually running, because the frame driver owns the
        // scale then — the album stays in the database for the length of the animation, so
        // `bind` keeps being called on a slot that is mid-flight.
        if (slot.discardClock == null) slot.root?.scale = Scale(1f)
        artwork?.hue?.let { slot.setBeamHue(it) }
        // Where this case belongs now — and whether it should *travel* there.
        //
        // Shelves pack, so deleting an album shifts everything after it one slot along and
        // re-centres the whole row. Those cases should slide. But this same pool hands a
        // slot to a different album constantly while browsing, and a recycled slot must
        // appear where it belongs immediately — gliding there would send cases flying
        // across the wall on every pan. The album id is what tells the two apart.
        //
        // `body.name` is the previous binding: `bind` sets it below, and parking a slot
        // clears it to null, so a fresh or spare slot correctly reads as "not this album".
        val sameAlbum = slot.body?.name == case.albumId
        slot.targetX = case.x
        slot.targetY = case.y
        // The playing album stands out of its slot, so browsing away still shows you
        // what is on (PLAN.md §9 #3).
        slot.targetZ = if (case.albumId == nowPlayingAlbumId) PROUD_Z else 0f
        if (!sameAlbum) slot.snapToTarget()
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
        slot.backArt?.let { node ->
            val back = artwork?.back
            // Unlike the cover, this is generated rather than supplied, so it exists for
            // every album that has finished loading — there is no "no back" case.
            node.isVisible = back != null
            if (back != null) node.bitmap = back
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
    materialLoader: MaterialLoader,
    caseInstances: List<FilamentInstance>,
    beamInstances: List<FilamentInstance>
) {
    slots.ensure(size)

    // Shared placeholders. Every art node is built from these and never from real artwork,
    // so the composable's remember is keyed on something that never changes.
    val blankSpine = remember { CaseArt.blank(CaseArt.SPINE_W, CaseArt.SPINE_H) }
    val blankCover = remember { CaseArt.blank(CaseArt.COVER_PX, CaseArt.COVER_PX) }
    val blankBack = remember { CaseArt.blank(CaseArt.BACK_W, CaseArt.BACK_H) }

    DisposableEffect(slots) {
        onDispose {
            slots.slots.forEach {
                it.root = null
                it.body = null
                it.spineArt = null
                it.coverArt = null
                it.backArt = null
                // The instances themselves are deliberately NOT destroyed. Trap 1: handing a
                // MaterialInstance back while a texture is still bound to it aborts the
                // process on the next commit, and these stay bound to the plates' textures for
                // as long as the scene lives.
                it.underCarrier = null
                it.coverMaterial = null
                it.spineMaterial = null
                it.backMaterial = null
            }
        }
    }

    repeat(size) { index ->
        // Keyed by INDEX, not album id. That is what keeps these composables in composition
        // as the wall scrolls; keying by album is what destroyed them.
        key(index) {
            val slot = slots.slots[index]
            // The underlight, mounted alongside the case rather than inside it so the shaft
            // stays put while the album turns. Emissive geometry, not a light: Filament will
            // not hand out one spot per album, and this costs a single draw.
            ModelNode(
                beamInstances[index],
                apply = {
                    slot.beam = this
                    // Per-instance, so each album's beam can take its own colour. See
                    // CaseSlot.setBeamHue.
                    slot.beamMaterials = beamInstances[index].materialInstances
                    isVisible = false
                    isTouchable = false
                    // It is light. It neither casts nor receives.
                    isShadowCaster = false
                    isShadowReceiver = false
                }
            )
            // The real light this album is lit by. A sibling of the case for the same reason
            // the emissive cone is one: the case turns, and a light that turned with it would
            // swing its pool around the bay.
            //
            // A POINT, and on its own light channel — see RoomLighting.UNDER_LIGHT_CHANNEL.
            // Only the case's printed plates share that channel, so the point can light the
            // artwork whichever way the case has turned without reaching the back panel behind
            // it or reflecting in the glass in front of it. Hidden until `bind` gives this slot
            // an album.
            Node(apply = {
                slot.underCarrier = this
                isVisible = false
            }) {
                LightNode(
                    type = LightManager.Type.POINT,
                    apply = {
                        intensityCandela(RoomLighting.UNDER_CANDELA)
                        falloff(RoomLighting.UNDER_FALLOFF)
                        // Off the room's default channel and onto its own. A light that
                        // stayed on channel 0 as well would still reach everything.
                        lightChannel(0, false)
                        lightChannel(RoomLighting.UNDER_LIGHT_CHANNEL, true)
                        // Around a hundred of these are on screen at once. Clustered forward
                        // rendering is fine with that; a hundred shadow casters would not be,
                        // and there is no shadow map budget remotely near it.
                        castShadows(false)
                    }
                )
            }

            // Mounted HIDDEN, like the active case group and for the same reason. `bind`
            // decides which slots show an album and where they sit, and it runs from a
            // LaunchedEffect — after the first frames. Left visible, every pooled slot
            // renders at its default position, so the wall opens on a pile of cases stacked
            // at the origin before binding scatters them to their bays.
            Node(apply = {
                slot.root = this
                isVisible = false
            }) {
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
                            // Printed paper, and lit — see CaseSlot.coverMaterial.
                            if (slot.spineMaterial == null) {
                                slot.spineMaterial = materialLoader.createTextureInstance(
                                    texture,
                                    metallic = 0f,
                                    roughness = PAPER_ROUGHNESS,
                                    reflectance = PAPER_REFLECTANCE
                                ).also {
                                    materialInstance = it
                                    // Lit by this album's own underlight, which nothing but a
                                    // case's printed plates may be. See
                                    // RoomLighting.UNDER_LIGHT_CHANNEL.
                                    renderableManager.setLightChannel(
                                        renderableInstance,
                                        RoomLighting.UNDER_LIGHT_CHANNEL,
                                        true
                                    )
                                    // Off the room's own channel: the art is lit by its underlight and nothing
                                    // else. See RoomLighting.ROOM_LIGHT_CHANNEL.
                                    renderableManager.setLightChannel(
                                        renderableInstance,
                                        RoomLighting.ROOM_LIGHT_CHANNEL,
                                        false
                                    )
                                }
                            }
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
                        // Just inside the lid's outer face, so the clear shell sits over the
                        // artwork the way a jewel case's front does.
                        //
                        // Getting this wrong is easy and silent. The tray spans -0.005..+0.001
                        // and the LID spans +0.001..+0.005, so `TRAY_FRONT_Z - PLATE_LIFT`
                        // (+0.0006) is inside the *opaque tray* — the artwork disappears
                        // entirely, buried in solid plastic, with nothing in any log to say
                        // so. It has to be near the lid's outer face, not the tray's front.
                        position = Position(CASE_W / 2f - 0.0006f, 0f, 0f),
                        rotation = io.github.sceneview.math.Rotation(0f, 90f, 0f),
                        apply = {
                            slot.coverArt = this
                            isShadowCaster = false
                            // **The cover is lit now.** See CaseSlot.coverMaterial for what it
                            // was and why that was the biggest single thing wrong with the
                            // room. Slightly smoother than the spine, because this is the
                            // printed inlay seen through the shell — but only slightly: the
                            // hard sheen belongs to the shell's clearcoat above it.
                            if (slot.coverMaterial == null) {
                                slot.coverMaterial = materialLoader.createTextureInstance(
                                    texture,
                                    metallic = 0f,
                                    roughness = COVER_ROUGHNESS,
                                    reflectance = PAPER_REFLECTANCE
                                ).also {
                                    materialInstance = it
                                    // Lit by this album's own underlight, which nothing but a
                                    // case's printed plates may be. See
                                    // RoomLighting.UNDER_LIGHT_CHANNEL.
                                    renderableManager.setLightChannel(
                                        renderableInstance,
                                        RoomLighting.UNDER_LIGHT_CHANNEL,
                                        true
                                    )
                                    // Off the room's own channel: the art is lit by its underlight and nothing
                                    // else. See RoomLighting.ROOM_LIGHT_CHANNEL.
                                    renderableManager.setLightChannel(
                                        renderableInstance,
                                        RoomLighting.ROOM_LIGHT_CHANNEL,
                                        false
                                    )
                                }
                            }
                        }
                    )
                    // The printed back, facing -X. Needed the moment cases began turning a
                    // full circle: half of every revolution used to be bare plastic.
                    // Generated from the album's hue rather than uploaded — see bakeBack.
                    ImageNode(
                        bitmap = blankBack,
                        size = Size(CASE_D, CASE_H, 0f),
                        normal = Direction(z = 1f),
                        position = Position(-CASE_W / 2f - PLATE_LIFT, 0f, 0f),
                        rotation = io.github.sceneview.math.Rotation(0f, -90f, 0f),
                        apply = {
                            slot.backArt = this
                            isShadowCaster = false
                            if (slot.backMaterial == null) {
                                slot.backMaterial = materialLoader.createTextureInstance(
                                    texture,
                                    metallic = 0f,
                                    roughness = PAPER_ROUGHNESS,
                                    reflectance = PAPER_REFLECTANCE
                                ).also {
                                    materialInstance = it
                                    // Lit by this album's own underlight, which nothing but a
                                    // case's printed plates may be. See
                                    // RoomLighting.UNDER_LIGHT_CHANNEL.
                                    renderableManager.setLightChannel(
                                        renderableInstance,
                                        RoomLighting.UNDER_LIGHT_CHANNEL,
                                        true
                                    )
                                    // Off the room's own channel: the art is lit by its underlight and nothing
                                    // else. See RoomLighting.ROOM_LIGHT_CHANNEL.
                                    renderableManager.setLightChannel(
                                        renderableInstance,
                                        RoomLighting.ROOM_LIGHT_CHANNEL,
                                        false
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
