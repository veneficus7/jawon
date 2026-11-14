package com.jawon.app.ui.tapescene

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.VelocityTracker
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import com.jawon.app.ui.wallscene.CaseArt
import com.jawon.app.ui.wallscene.RoomHaptics
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.RenderableNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

internal const val RACK_ASSET = "models/tape_rack.glb"
internal const val CLOSED_CASE_ASSET = "models/tape_case_closed.glb"

/**
 * What the racks are printed with: a spine for every box of the pool and a name card for every
 * rack. Allocated once for the life of the scene and written again as the racks are gone along —
 * a texture destroyed while a material still samples it takes the process with it (`ENGINEERING.md`,
 * "never destroy an `ImageNode`").
 */
internal class RackTextures(private val engine: Engine) {

    private fun colour(width: Int, height: Int): Texture = Texture.Builder()
        .width(width).height(height)
        .levels(32 - Integer.numberOfLeadingZeros(maxOf(width, height)))
        .format(Texture.InternalFormat.SRGB8_A8)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
        .build(engine)

    val spines = Array(TapeRackScene.CASE_POOL) { colour(TapeArt.RACK_SPINE_W, TapeArt.RACK_SPINE_H) }
    val labels = Array(TapeRackScene.RACK_POOL) { colour(TapeArt.RACK_LABEL_W, TapeArt.RACK_LABEL_H) }

    val sampler: TextureSampler by lazy {
        TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
            .apply { anisotropy = 8f }
    }

    var alive = true
        private set

    fun write(texture: Texture, bitmap: Bitmap) {
        if (!alive) return
        TextureHelper.setBitmap(engine, texture, 0, bitmap)
        texture.generateMipmaps(engine)
    }

    fun destroy() {
        if (!alive) return
        alive = false
        for (texture in spines.asList() + labels.asList()) runCatching { engine.destroyTexture(texture) }
    }
}

/**
 * **The racks, as the room draws them and a finger goes along them.**
 *
 * `TapeRack` is the arithmetic; this is the pool the racks are drawn from — five rack frames and
 * forty-eight boxes, made once and never again — what is printed on them, and what a touch means:
 *
 * - **Drag up and down** a rack, and throw it: a tick for every tape passed.
 * - **Swipe sideways** to the next rack, or the one before: a heavier tick, and it settles square.
 * - **Touch a spine** and that tape is taken ([onTake]).
 *
 * The boxes are shared between two racks at a time — whichever two the camera is between — half
 * the pool to the even racks and half to the odd, so a box keeps its slot (and its print) for as
 * long as its rack is in sight. A plain object the scene writes its nodes into: nothing here is
 * Compose state.
 */
internal class TapeRackScene(
    private val context: Context,
    private val scope: CoroutineScope,
    private val textures: RackTextures,
    private val haptics: RoomHaptics
) {
    var towers: List<TapeRack.Tower> = emptyList()
        private set
    val motion = RackMotion()

    /** A tape has been touched. */
    var onTake: (TapeRack.Tape) -> Unit = {}

    /** The tape that is out of its slot — on its way to the deck, or in it — and so not drawn there. */
    var out: String? = null

    /** The tape that is playing stands a little proud of its rack. */
    private var playing: String? = null

    private val caseNodes = arrayOfNulls<ModelNode>(CASE_POOL)
    private val caseWants = arrayOfNulls<String>(CASE_POOL)
    private val caseHas = arrayOfNulls<String>(CASE_POOL)
    private val proud = FloatArray(CASE_POOL)

    private val rackNodes = arrayOfNulls<ModelNode>(RACK_POOL)
    private val rackWants = arrayOfNulls<String>(RACK_POOL)
    private val rackHas = arrayOfNulls<String>(RACK_POOL)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val bakers = Dispatchers.IO.limitedParallelism(2)

    /** Something was printed or moved: draw. */
    var changed = true

    private var lastSlot = Int.MIN_VALUE
    private var lastTower = 0

    fun adoptCase(index: Int, node: ModelNode) {
        if (caseNodes[index] === node) return
        caseNodes[index] = node
        for (part in node.renderableNodes) {
            val material = (part as? RenderableNode)?.materialInstance ?: continue
            if (part.name == "jcard_spine" || material.name == "JCardSpine") {
                material.setParameter("baseColorMap", textures.spines[index], textures.sampler)
            }
        }
        node.isVisible = false
    }

    fun adoptRack(index: Int, node: ModelNode) {
        if (rackNodes[index] === node) return
        rackNodes[index] = node
        for (part in node.renderableNodes) {
            val material = (part as? RenderableNode)?.materialInstance ?: continue
            if (part.name == "rack_label" || material.name == "RackLabel") {
                material.setParameter("baseColorMap", textures.labels[index], textures.sampler)
            }
        }
        node.isVisible = false
    }

    /** The library, as racks. Opened at the tape that is playing, if there is one; a rack that changes keeps the hand where it was. */
    fun setTowers(new: List<TapeRack.Tower>, nowPlaying: String?, tall: Float) {
        motion.setRange(TapeRack.lookRange(tall))
        if (new.size == towers.size && new.zip(towers).all { (a, b) -> a.shelfId == b.shelfId && a.tapes.map { it.albumId } == b.tapes.map { it.albumId } }) {
            towers = new
            return
        }
        val opening = towers.isEmpty()
        towers = new
        if (opening) {
            val at = TapeRack.find(new, nowPlaying)
            val range = TapeRack.lookRange(tall)
            motion.place(at?.first ?: 0, at?.let { TapeRack.slotMiddleY(it.second) } ?: range.endInclusive)
        } else {
            motion.goTo(motion.towerTarget.coerceIn(0, (new.size - 1).coerceAtLeast(0)))
        }
        changed = true
    }

    /** Along to the tape that is playing, for coming back to the racks after it was put on from elsewhere. */
    fun turnTo(albumId: String?) {
        val at = TapeRack.find(towers, albumId) ?: return
        if (motion.settled) motion.goTo(at.first, TapeRack.slotMiddleY(at.second))
    }

    val cameraX: Float get() = TapeDimens.rackX(0) + motion.tower * (TapeDimens.RACK_W + TapeDimens.RACK_GAP)
    val cameraY: Float get() = motion.y

    /** Which tower is in front, for TalkBack. */
    val front: Int get() = motion.towerTarget

    /** Moves the hand on by [dt] and puts every box and rack where it stands. True while anything is on its way. */
    fun onFrame(dt: Float, nowPlaying: String?): Boolean {
        playing = nowPlaying
        motion.step(dt)
        // A tick as each tape passes the middle of the frame; a heavier one for each rack.
        val slot = TapeRack.slotAtHeight(motion.y)
        if (lastSlot != Int.MIN_VALUE && slot != lastSlot && slot in 0 until TapeDimens.RACK_SLOTS) haptics.tick()
        lastSlot = slot
        val nearest = Math.round(motion.tower)
        if (nearest != lastTower) {
            haptics.detent()
            lastTower = nearest
        }

        // The racks: the one in front and two each side, each frame from the pool its index says.
        val centre = floor(motion.tower).toInt()
        val shownRacks = BooleanArray(RACK_POOL)
        for (t in centre - 2..centre + 2) {
            if (t !in towers.indices) continue
            val i = ((t % RACK_POOL) + RACK_POOL) % RACK_POOL
            shownRacks[i] = true
            val node = rackNodes[i] ?: continue
            node.position = Position(TapeDimens.rackX(t), TapeDimens.RACKS_BOTTOM, TapeDimens.RACK_FRONT_Z)
            node.isVisible = true
            val tower = towers[t]
            val key = "${tower.name}|${tower.partLabel}"
            if (rackWants[i] != key) {
                rackWants[i] = key
                printLabel(i, key, tower)
            }
        }
        for (i in 0 until RACK_POOL) if (!shownRacks[i]) rackNodes[i]?.isVisible = false

        // The boxes: the two racks the camera is between, the even one's in the first half of the pool.
        val shownCases = BooleanArray(CASE_POOL)
        for (t in listOf(centre, centre + 1)) {
            if (t !in towers.indices) continue
            val half = (t and 1) * TapeDimens.RACK_SLOTS
            for (tape in towers[t].tapes) {
                val i = half + tape.slot
                val node = caseNodes[i] ?: continue
                if (tape.albumId == out) continue
                shownCases[i] = true
                val wanted = if (tape.albumId == playing) PROUD else 0f
                proud[i] += (wanted - proud[i]) * (1f - kotlin.math.exp(-10f * dt))
                node.position = Position(TapeDimens.rackX(t), TapeDimens.slotY(tape.slot), TapeDimens.SLOT_CASE_Z + proud[i])
                node.quaternion = IN_RACK
                node.isVisible = true
                val key = "${tape.albumId}|${tape.title}|${tape.artist}|${tape.coverPath}|${tape.storedHue}"
                if (caseWants[i] != key) {
                    caseWants[i] = key
                    printSpine(i, key, tape)
                }
            }
        }
        for (i in 0 until CASE_POOL) if (!shownCases[i]) caseNodes[i]?.isVisible = false
        return held || !motion.settled || proud.any { it > 0.0002f && it < PROUD - 0.0002f }
    }

    private fun printSpine(i: Int, key: String, tape: TapeRack.Tape) {
        scope.launch {
            val bitmap = withContext(bakers) {
                if (caseWants[i] != key) return@withContext null
                val hue = hueOf(tape)
                TapeArt.spine(context, hue, tape.title, tape.artist, TapeArt.RACK_SPINE_W, TapeArt.RACK_SPINE_H)
            } ?: return@launch
            if (textures.alive && caseWants[i] == key) {
                textures.write(textures.spines[i], bitmap)
                caseHas[i] = key
                changed = true
            }
        }
    }

    private fun printLabel(i: Int, key: String, tower: TapeRack.Tower) {
        scope.launch {
            val bitmap = withContext(bakers) { TapeArt.rackLabel(context, tower.name, tower.partLabel) }
            if (textures.alive && rackWants[i] == key) {
                textures.write(textures.labels[i], bitmap)
                rackHas[i] = key
                changed = true
            }
        }
    }

    // ---- A finger -------------------------------------------------------------------------------------

    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var way = NONE
    private val spot = FloatArray(2)

    private val held: Boolean get() = way != NONE

    fun onTouch(event: MotionEvent, shot: TapeCamera.Shot, widthPx: Float, heightPx: Float, slopPx: Float) {
        val metresPerPx = TapeCamera.rackTall(heightPx / widthPx) / heightPx
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x
                downY = event.y
                lastY = event.y
                downAt = event.eventTime
                way = PENDING
                motion.grab()
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                if (way == PENDING && hypot(event.x - downX, event.y - downY) > slopPx) {
                    way = if (abs(event.x - downX) > abs(event.y - downY) * 1.3f) SIDEWAYS else ALONG
                    lastY = event.y
                }
                if (way == ALONG) {
                    // A finger moving down the glass takes the camera up the rack, as a list scrolls.
                    motion.drag((event.y - lastY) * metresPerPx)
                    lastY = event.y
                    changed = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000)
                val vy = tracker?.yVelocity ?: 0f
                tracker?.recycle()
                tracker = null
                val was = way
                way = NONE
                when {
                    was == ALONG -> motion.fling(vy * metresPerPx)
                    was == SIDEWAYS -> {
                        motion.fling(0f)
                        if (abs(event.x - downX) > widthPx * SWIPE_OF_SCREEN) motion.swipe(event.x < downX, towers.size)
                    }
                    else -> {
                        motion.fling(0f)
                        if (event.actionMasked == MotionEvent.ACTION_UP && event.eventTime - downAt <= TAP_LONGEST_MS) tap(event.x, event.y, shot, widthPx, heightPx)
                    }
                }
                changed = true
            }
        }
    }

    private fun tap(px: Float, py: Float, shot: TapeCamera.Shot, widthPx: Float, heightPx: Float) {
        TapeCamera.onUpright(shot, px, py, widthPx, heightPx, TapeDimens.RACK_FRONT_Z - TapeDimens.SPINE_BACK, spot)
        val at = TapeRack.at(towers, spot[0], spot[1]) ?: return
        val tape = towers[at.first].tapes.firstOrNull { it.slot == at.second } ?: return
        haptics.detent()
        onTake(tape)
    }

    /** What TalkBack's actions do. */
    fun step(by: Int) {
        val tower = towers.getOrNull(motion.towerTarget) ?: return
        val now = TapeRack.slotAtHeight(motion.y).coerceIn(0, (tower.tapes.size - 1).coerceAtLeast(0))
        val to = (now + by).coerceIn(0, (tower.tapes.size - 1).coerceAtLeast(0))
        motion.goTo(motion.towerTarget, TapeRack.slotMiddleY(to))
        changed = true
    }

    fun shelf(forward: Boolean) {
        motion.swipe(forward, towers.size)
        changed = true
    }

    /** The tape level with the middle of the frame, in the rack in front. */
    fun inFront(): TapeRack.Tape? {
        val tower = towers.getOrNull(motion.towerTarget) ?: return null
        val slot = TapeRack.slotAtHeight(motion.y)
        return tower.tapes.firstOrNull { it.slot == slot } ?: tower.tapes.minByOrNull { abs(it.slot - slot) }
    }

    fun takeInFront() {
        inFront()?.let(onTake)
    }

    /** Where the box for [albumId] is drawn, if it is in sight: (x, slot y) — where a tape taken by TalkBack starts from. */
    fun whereIs(albumId: String): Pair<Float, Float>? {
        val at = TapeRack.find(towers, albumId) ?: return null
        return TapeDimens.rackX(at.first) to TapeDimens.slotY(at.second)
    }

    fun release() {
        tracker?.recycle()
        tracker = null
    }

    private fun hueOf(tape: TapeRack.Tape): Float =
        tape.storedHue.takeIf { it != 0f }
            ?: CaseArt.loadCover(tape.coverPath, 64)?.let { CaseArt.dominantHue(it) }
            ?: tape.fallbackHue

    companion object {
        /** Two racks' worth: the two the camera is between. */
        const val CASE_POOL = 2 * TapeDimens.RACK_SLOTS
        const val RACK_POOL = 5

        /** How far the tape that is playing stands out of its rack. */
        const val PROUD = 0.012f

        private const val NONE = 0
        private const val PENDING = 1
        private const val ALONG = 2
        private const val SIDEWAYS = 3
        private const val TAP_LONGEST_MS = 280L
        private const val SWIPE_OF_SCREEN = 0.12f

        /** A box lying in a rack, spine out: yaw 180°. As kotlin-math has it, (x, y, z, w). */
        val IN_RACK = Quaternion(0f, 1f, 0f, 0f)
    }
}
