package com.jawon.app.ui.vinylscene

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import com.jawon.app.ui.wallscene.CaseArt
import com.jawon.app.ui.wallscene.RoomHaptics
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.RenderableNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.sin

internal const val CRATE_ASSET = "models/vinyl_crate.glb"
internal const val DIVIDER_ASSET = "models/vinyl_divider.glb"

/**
 * What the crate is printed with: a small cover for every sleeve of the pool, three large ones
 * for the record being read and the one each side of it, a label for every card.
 *
 * Allocated once for the life of the scene and written again as the crate is gone through — the
 * rule the CD room paid for (`ENGINEERING.md`, "never destroy an `ImageNode`"): a texture destroyed
 * while a material still samples it takes the process with it. Twenty-four small covers are
 * eight megabytes; the same at a size worth reading would be a hundred and thirty, for sleeves
 * of which all that is ever seen is a top edge.
 */
internal class CrateTextures(private val engine: Engine) {

    private fun colour(width: Int, height: Int): Texture = Texture.Builder()
        .width(width).height(height)
        .levels(32 - Integer.numberOfLeadingZeros(maxOf(width, height)))
        .format(Texture.InternalFormat.SRGB8_A8)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
        .build(engine)

    val small = Array(CrateLayout.POOL) { colour(VinylArt.CRATE_PX, VinylArt.CRATE_PX) }
    val large = Array(LARGE) { colour(VinylArt.SLEEVE_PX, VinylArt.SLEEVE_PX) }
    val label = Array(CrateLayout.CARDS) { colour(VinylArt.LABEL_W, VinylArt.LABEL_H) }
    val back = colour(BACK_PX, BACK_PX)

    val sampler: TextureSampler by lazy {
        TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
            .apply { anisotropy = 8f }
    }

    var alive = true
        private set

    init {
        write(back, VinylArt.cardBack(BACK_PX))
    }

    fun write(texture: Texture, bitmap: Bitmap) {
        if (!alive) return
        TextureHelper.setBitmap(engine, texture, 0, bitmap)
        texture.generateMipmaps(engine)
    }

    fun destroy() {
        if (!alive) return
        alive = false
        for (texture in small.asList() + large.asList() + label.asList() + back) runCatching { engine.destroyTexture(texture) }
    }

    companion object {
        /** The record being read, and the one each side of it. */
        const val LARGE = 3
        private const val BACK_PX = 64
    }
}

/**
 * **The crate, as the room draws it and as a finger goes through it.**
 *
 * `Crate.kt` is the arithmetic — what stands where for a given place in the crate, and how a
 * hand comes to rest. This is the other half: the sleeves that are drawn (a ring of them, each
 * becoming the next record as the last one leaves the far end — nothing here is ever made or
 * destroyed after the room is built), what is printed on them, and what a touch means.
 *
 * - **Drag down** and the record in front falls toward you and the next one is there; up, and it
 *   comes back. Let go and it runs on and stops *on* a record, a tick for each one passed and a
 *   heavier one for a shelf's card.
 * - **Swipe sideways** to the next shelf, or back to the start of this one.
 * - **Touch the record you are reading** and it is lifted out and put on. Touch the pile in front
 *   of it or the stack behind and the crate moves one that way.
 *
 * A plain object the scene writes its nodes into, as `VinylStage` is: none of this is Compose
 * state, because none of it should recompose anything.
 */
internal class VinylCrate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val textures: CrateTextures,
    private val haptics: RoomHaptics
) {
    val motion = CrateMotion()

    var items: List<CrateItem> = emptyList()
        private set
    private var ordinals = IntArray(0)

    /** A record has been chosen; the card at the back has been; the table above has been touched. */
    var onTake: (albumId: String) -> Unit = {}
    var onAdd: () -> Unit = {}
    var onDeck: () -> Unit = {}

    /** Something was printed since the last frame: draw, whatever the cadence was about to do. */
    var changed = true

    // ---- The pool ----------------------------------------------------------------------------------

    private val sleeveNodes = arrayOfNulls<Node>(CrateLayout.POOL)
    private val sleeveFronts = arrayOfNulls<MaterialInstance>(CrateLayout.POOL)
    private val slotWants = arrayOfNulls<String>(CrateLayout.POOL)
    private val slotHas = arrayOfNulls<String>(CrateLayout.POOL)
    private val slotShows = IntArray(CrateLayout.POOL) { SMALL }
    private val lift = FloatArray(CrateLayout.POOL)

    private val largeWants = arrayOfNulls<String>(CrateTextures.LARGE)
    private val largeHas = arrayOfNulls<String>(CrateTextures.LARGE)

    private val cardNodes = arrayOfNulls<Node>(CrateLayout.CARDS)
    private val cardTabs = arrayOfNulls<Node>(CrateLayout.CARDS)
    private val cardLabels = arrayOfNulls<Node>(CrateLayout.CARDS)
    private val cardWants = arrayOfNulls<String>(CrateLayout.CARDS)
    private val cardHas = arrayOfNulls<String>(CrateLayout.CARDS)
    private val cardIs = IntArray(CrateLayout.CARDS)

    private val pose = CrateLayout.Pose()
    private var lastNear = -1
    private var taking: String? = null
    private var takingAt = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val bakers = Dispatchers.IO.limitedParallelism(2)

    /** A sleeve of the pool has been built: take hold of it, and of the two faces it is printed on. */
    fun adoptSleeve(slot: Int, node: ModelNode) {
        // Once: a node's `apply` runs again whenever the room recomposes, and a sleeve hidden and
        // rebound each time would blink.
        if (sleeveNodes[slot] === node) return
        sleeveNodes[slot] = node
        for (part in node.renderableNodes) {
            val material = (part as? RenderableNode)?.materialInstance ?: continue
            when {
                part.name == "sleeve_front" || material.name == "SleeveFront" -> {
                    sleeveFronts[slot] = material
                    material.setParameter("baseColorMap", textures.small[slot], textures.sampler)
                    slotShows[slot] = SMALL
                }
                part.name == "sleeve_back" || material.name == "SleeveBack" ->
                    material.setParameter("baseColorMap", textures.back, textures.sampler)
            }
        }
        node.isVisible = false
    }

    /** A card of the pool has been built. Its tab and the label on it are moved along its top by which card it is. */
    fun adoptCard(index: Int, node: ModelNode) {
        if (cardNodes[index] === node) return
        cardNodes[index] = node
        for (part in node.renderableNodes) {
            when (part.name) {
                "divider_tab" -> cardTabs[index] = part
                "divider_label" -> {
                    cardLabels[index] = part
                    (part as? RenderableNode)?.materialInstance?.setParameter("baseColorMap", textures.label[index], textures.sampler)
                }
            }
        }
        node.isVisible = false
    }

    /** The library, as a crate. A crate that changes under the hand keeps the hand on what it was on. */
    fun setItems(new: List<CrateItem>, nowPlaying: String?) {
        if (new == items) return
        val opening = items.isEmpty()
        val reading = key(items.getOrNull(motion.nearest))
        items = new
        ordinals = Crate.cardOrdinals(new)
        val at = if (opening) {
            // Opened at the record that is on, if one is: that is where you were.
            Crate.indexOf(new, nowPlaying).takeIf { it >= 0 } ?: Crate.start(new)
        } else {
            new.indexOfFirst { key(it) == reading }.takeIf { it >= 0 } ?: motion.nearest
        }
        motion.reset(Crate.stops(new), at)
        lastNear = motion.nearest
        changed = true
    }

    /** Turns the crate to [albumId], if it is in it: where it is opened at when a record is put on from elsewhere. */
    fun turnTo(albumId: String?) {
        val index = Crate.indexOf(items, albumId)
        if (index >= 0 && motion.settled) {
            motion.reset(Crate.stops(items), index)
            lastNear = index
            changed = true
        }
    }

    /** How far back from the middle of the crate the record being read stands: what the camera follows. */
    val readsAt: Float
        get() = CrateLayout.readZ(motion.position, items.size) - READ_MIDDLE

    // ---- A frame ------------------------------------------------------------------------------------------

    /**
     * Moves the hand on by [dt] and puts everything where it now stands. True while anything is
     * still on its way somewhere.
     */
    fun onFrame(dt: Float, now: Long, nowPlaying: String?): Boolean {
        motion.step(dt)
        val count = items.size
        val position = motion.position

        // One tick for a record passed, and a heavier one for a shelf's card — from the motion
        // and not from the finger, so a flick ticks all the way to where it stops.
        val near = motion.nearest
        if (near != front) front = near
        if (near != lastNear && count > 0) {
            if (lastNear >= 0) {
                val by = if (near > lastNear) 1 else -1
                var card = false
                var i = lastNear + by
                while (i != near + by) {
                    if (items.getOrNull(i) is CrateItem.Divider) card = true
                    i += by
                }
                if (card) haptics.detent() else haptics.tick()
            }
            lastNear = near
        }
        if (taking != null && now - takingAt > TAKE_HOLD_MS) taking = null

        var moving = !motion.settled
        // Only once the hand has nearly arrived: a cover is not printed large for a crate going past it.
        val arriving = abs(motion.target - position) < 1.2f
        val ease = 1f - exp(-LIFT_EASE * dt)

        for (slot in 0 until CrateLayout.POOL) {
            val node = sleeveNodes[slot] ?: continue
            val index = CrateLayout.itemFor(slot, position, count)
            val record = items.getOrNull(index) as? CrateItem.Record
            if (record == null) {
                // Nothing, or a card — which the cards draw.
                if (node.isVisible) node.isVisible = false
                slotWants[slot] = null
                lift[slot] = 0f
                continue
            }
            val key = key(record)!!
            val wanted = when (record.albumId) {
                taking -> CrateLayout.PULL_M
                nowPlaying -> CrateLayout.PROUD_M
                else -> 0f
            }
            if (slotWants[slot] != key) {
                slotWants[slot] = key
                lift[slot] = wanted
                printSmall(slot, key, record)
            }
            lift[slot] += (wanted - lift[slot]) * ease
            if (abs(wanted - lift[slot]) < 0.0004f) lift[slot] = wanted else moving = true

            val d = index - position
            CrateLayout.pose(d, position, count, pose)
            val show = pose.drawn && slotHas[slot] == key
            if (node.isVisible != show) node.isVisible = show
            val lean = Math.toRadians(pose.leanDeg.toDouble())
            node.position = Position(0f, lift[slot] * cos(lean).toFloat(), pose.z + lift[slot] * sin(lean).toFloat())
            node.rotation = Rotation(x = pose.leanDeg)

            // The record being read and its neighbours are printed large; the rest are a top edge.
            val which = Math.floorMod(index, CrateTextures.LARGE)
            val close = abs(d) <= 1.5f
            if (close && arriving && largeWants[which] != key) {
                largeWants[which] = key
                printLarge(which, key, record)
            }
            val shows = if (close && largeHas[which] == key) which else SMALL
            if (slotShows[slot] != shows) {
                slotShows[slot] = shows
                sleeveFronts[slot]?.setParameter(
                    "baseColorMap",
                    if (shows == SMALL) textures.small[slot] else textures.large[shows],
                    textures.sampler
                )
            }
        }

        // The cards. Each is whichever card of its number is nearest the hand.
        java.util.Arrays.fill(cardIs, -1)
        val first = floor(position).toInt() - CrateLayout.FRONT
        for (index in maxOf(first, 0) until minOf(first + CrateLayout.POOL, count)) {
            val ordinal = ordinals[index]
            if (ordinal < 0) continue
            val card = ordinal % CrateLayout.CARDS
            val held = cardIs[card]
            if (held < 0 || abs(index - position) < abs(held - position)) cardIs[card] = index
        }
        for (card in 0 until CrateLayout.CARDS) {
            val node = cardNodes[card] ?: continue
            val index = cardIs[card]
            if (index < 0) {
                if (node.isVisible) node.isVisible = false
                continue
            }
            val item = items[index]
            val key = key(item)!!
            if (cardWants[card] != key) {
                cardWants[card] = key
                printLabel(card, key, item)
            }
            CrateLayout.pose(index - position, position, count, pose)
            val show = pose.drawn && cardHas[card] == key
            if (node.isVisible != show) node.isVisible = show
            node.position = Position(0f, 0f, pose.z)
            node.rotation = Rotation(x = pose.leanDeg)
            val along = CrateLayout.tabX(ordinals[index])
            cardTabs[card]?.position = Position(x = along)
            cardLabels[card]?.position = Position(x = along)
        }
        return moving
    }

    // ---- What is printed ------------------------------------------------------------------------------------

    private fun key(item: CrateItem?): String? = when (item) {
        is CrateItem.Record -> "r:${item.albumId}:${item.coverPath}:${item.title}"
        is CrateItem.Divider -> "d:${item.name}:${item.records}"
        CrateItem.Add -> "add"
        null -> null
    }

    private fun printSmall(slot: Int, key: String, record: CrateItem.Record) {
        scope.launch {
            val bitmap = withContext(bakers) {
                // Gone past before its turn came: a flick asks for more covers than it stops at.
                if (slotWants[slot] != key) null
                else VinylArt.sleeveFront(context, CaseArt.loadCover(record.coverPath, VinylArt.CRATE_PX), record.title, record.artist, VinylArt.CRATE_PX)
            } ?: return@launch
            if (textures.alive && slotWants[slot] == key) {
                textures.write(textures.small[slot], bitmap)
                slotHas[slot] = key
                changed = true
            }
        }
    }

    private fun printLarge(which: Int, key: String, record: CrateItem.Record) {
        scope.launch {
            val bitmap = withContext(bakers) {
                if (largeWants[which] != key) null
                else VinylArt.sleeveFront(context, CaseArt.loadCover(record.coverPath, VinylArt.SLEEVE_PX), record.title, record.artist)
            } ?: return@launch
            if (textures.alive && largeWants[which] == key) {
                textures.write(textures.large[which], bitmap)
                largeHas[which] = key
                changed = true
            }
        }
    }

    private fun printLabel(card: Int, key: String, item: CrateItem) {
        scope.launch {
            val bitmap = withContext(bakers) {
                when (item) {
                    is CrateItem.Divider -> VinylArt.dividerLabel(context, item.name, item.records.toString(), accent = false)
                    else -> VinylArt.dividerLabel(context, "+ Add records", "", accent = true)
                }
            }
            if (textures.alive && cardWants[card] == key) {
                textures.write(textures.label[card], bitmap)
                cardHas[card] = key
                changed = true
            }
        }
    }

    // ---- A finger ----------------------------------------------------------------------------------------------

    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var way = NONE
    private var caught = false
    private val spot = FloatArray(2)

    /** A hand is on the crate, or it is still running on from one. */
    val busy: Boolean get() = way != NONE || !motion.settled

    fun onTouch(event: MotionEvent, shot: VinylCamera.Shot, widthPx: Float, heightPx: Float, slopPx: Float) {
        val perRecord = heightPx * RECORD_OF_SCREEN
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastY = event.y
                downAt = event.eventTime
                way = NONE
                // A crate still running is caught by a touch, and that touch is not also a tap.
                caught = !motion.settled
                if (caught) motion.grab()
                tracker = (tracker ?: VelocityTracker.obtain()).also {
                    it.clear()
                    it.addMovement(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY
                if (way == NONE && hypot(dx, dy) > slopPx) {
                    way = if (abs(dy) >= abs(dx)) DIG else SHELF
                    if (way == DIG && !caught) motion.grab()
                    lastY = event.y
                }
                if (way == DIG) {
                    // Down the screen is toward you: the record in front comes over, and the next is there.
                    motion.drag((event.y - lastY) / perRecord)
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(event)
                when (way) {
                    DIG -> {
                        val speed = tracker?.let {
                            it.computeCurrentVelocity(1000)
                            it.yVelocity / perRecord
                        } ?: 0f
                        motion.release(speed)
                    }
                    SHELF -> {
                        if (caught) motion.release(0f)
                        val dx = event.x - downX
                        if (abs(dx) > widthPx * SHELF_SWIPE) {
                            // Leftward is onward, as a page is turned.
                            motion.goTo(Crate.shelfStart(items, motion.target.roundToInt(), forward = dx < 0f))
                        }
                    }
                    else -> if (caught) motion.release(0f) else if (event.eventTime - downAt <= TAP_LONGEST_MS) {
                        tap(event.x, event.y, shot, widthPx, heightPx)
                    }
                }
                way = NONE
                caught = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (way == DIG || caught) motion.release(0f)
                way = NONE
                caught = false
            }
        }
    }

    private fun tap(px: Float, py: Float, shot: VinylCamera.Shot, widthPx: Float, heightPx: Float) {
        // The table, at the top of the frame: back up to the deck.
        if (py < heightPx * DECK_BAND) {
            onDeck()
            return
        }
        val index = motion.nearest
        val item = items.getOrNull(index) ?: return
        CrateLayout.pose(0f, motion.position, items.size, pose)
        val met = VinylCamera.onSleeve(
            shot, px, py, widthPx, heightPx,
            VinylDimens.FLOOR_Y, VinylDimens.CRATE_Z + pose.z, pose.leanDeg, spot
        )
        if (!met || abs(spot[0]) > VinylDimens.CRATE_W / 2 + 0.02f) return
        when {
            // Above the one being read, on the screen, is the stack behind it; below is the pile in front.
            spot[1] > VinylDimens.SLEEVE + 0.012f -> motion.nudge(1)
            spot[1] < -0.012f -> motion.nudge(-1)
            abs(spot[0]) <= VinylDimens.SLEEVE / 2 -> take(item)
        }
    }

    private fun take(item: CrateItem) {
        when (item) {
            is CrateItem.Record -> {
                haptics.detent()
                taking = item.albumId
                takingAt = SystemClock.uptimeMillis()
                onTake(item.albumId)
            }
            CrateItem.Add -> {
                haptics.detent()
                onAdd()
            }
            else -> Unit
        }
    }

    // ---- What TalkBack's actions do: what a finger does, by name ------------------------------------

    /**
     * Which one is in front, for Compose: what TalkBack reads out. State, unlike the rest of the
     * crate, because it is read in a composition — and it changes once a record, not once a frame.
     */
    var front by mutableIntStateOf(0)
        private set

    /** What is being read: the one in front. */
    val inFront: CrateItem? get() = items.getOrNull(front)

    /** The next record back, or the one in front of it. */
    fun step(by: Int) = motion.nudge(by)

    /** The first record of the next shelf, or of this one — or the one before. */
    fun shelf(forward: Boolean) = motion.goTo(Crate.shelfStart(items, motion.target.roundToInt(), forward))

    /** The one in front, taken out. */
    fun takeInFront() {
        inFront?.let(::take)
    }

    /** The room is going. */
    fun release() {
        tracker?.recycle()
        tracker = null
    }

    private companion object {
        const val SMALL = -1
        const val NONE = 0
        const val DIG = 1
        const val SHELF = 2

        /** Where the record being read stands in the middle of a long crate: what the camera is set up on. */
        val READ_MIDDLE = CrateLayout.REST_BACK + CrateLayout.BEHIND * CrateLayout.PITCH_BACK

        /** How far a finger goes for one record: a tenth of the screen's height, or so. */
        const val RECORD_OF_SCREEN = 0.085f

        /** How far across the screen a sideways swipe has to go to be one. */
        const val SHELF_SWIPE = 0.10f

        /** The top of the frame is the table the deck stands on. */
        const val DECK_BAND = 0.15f

        const val TAP_LONGEST_MS = 280L

        /** How long a record that has been taken is held up out of the crate before it is the one that is on. */
        const val TAKE_HOLD_MS = 520L

        /** How quickly a sleeve rises and settles: the fraction left after a second is e^-this. */
        const val LIFT_EASE = 12f
    }
}
