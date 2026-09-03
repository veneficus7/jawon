package com.jawon.app.ui.wallscene

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * What the room is actually managing on this device.
 *
 * **This exists because the project's biggest open question cannot be answered by testing.**
 * Three of the first five testers reported that the room was "not that smooth", on S23-class
 * hardware, and every performance number in `ENGINEERING.md` comes from a single Galaxy S25 Ultra.
 * The quality tiers were written in response and have never been observed doing anything.
 * Buying the phones would cost money; asking the people already holding them to send a
 * screenshot costs a Settings section.
 *
 * So this is not telemetry and it is not for the app to act on — [FrameWatch] already does the
 * acting. It is a readout a tester can photograph, which turns "not that smooth" into a tier,
 * a frame time and a device name.
 *
 * ### Nothing here may cost a frame
 *
 * [onFrame] runs inside the frame callback at up to 120Hz, so it allocates nothing, branches
 * barely, and keeps a running mean rather than a list of samples. The fields are plain `var`s
 * read on demand rather than a `StateFlow`, because a state write per frame would recompose
 * the settings screen sixty times a second and, worse, teach the next person that driving
 * Compose from the frame loop is acceptable — which `ENGINEERING.md` rule 3 exists to prevent.
 */
@Singleton
class RoomDiagnostics @Inject constructor() {

    /** The tier `QualityTier.detect` chose from RAM and API level, before any measurement. */
    @Volatile var detectedTier: QualityTier? = null
        private set

    /** Where the tier is now. Different from [detectedTier] means [FrameWatch] stepped down. */
    @Volatile var currentTier: QualityTier? = null
        private set

    /** How many times the room has given up a notch this session. */
    @Volatile var stepDowns: Int = 0
        private set

    /**
     * The fraction of the surface the room last actually drew itself at.
     *
     * Filament moves this between [QualityTier.minRenderScale] and [QualityTier.maxRenderScale]
     * on its own, so unlike everything else here it is a reading rather than a setting — and it
     * is the only way to tell a device that is comfortably at its ceiling from one that has
     * quietly been scaling down to hold its frame rate. Pushed in from the frame driver rather
     * than read here: the getter is a JNI call and this class is deliberately free of Filament
     * types.
     */
    @Volatile var renderScale: Float = 1f
        private set

    fun onRenderScale(scale: Float) {
        renderScale = scale
    }

    private var frames = 0L
    private var totalMs = 0.0
    private var worstMs = 0.0

    /**
     * Of those frames, how many were actually rasterised rather than skipped by [RoomCadence].
     *
     * The one number that says whether the cadence is doing anything. A room that is genuinely
     * idle should sit far below [frames]; one that never drops below it is either being touched
     * the whole time or has something reporting motion that has in fact stopped — which is the
     * failure mode worth being able to see, because it costs nothing visible and saves nothing.
     */
    private var drawnFrames = 0L

    /** Frames slower than 20ms — the same threshold [FrameWatch] calls a miss. */
    private var slowFrames = 0L

    fun onTierDetected(tier: QualityTier) {
        detectedTier = tier
        currentTier = tier
    }

    fun onTierStepped(tier: QualityTier) {
        currentTier = tier
        stepDowns++
    }

    /** @param dt seconds since the previous frame, exactly as the frame driver computed it. */
    fun onFrame(dt: Float) {
        // The driver clamps dt at 1/24s for its own integration; a frame reported at the clamp
        // says "at least this bad" and counting it as exactly that would flatter a device that
        // is struggling. Counted as slow, excluded from the mean.
        val ms = dt * 1000.0
        frames++
        if (dt > 0.020f) slowFrames++
        if (ms > worstMs) worstMs = ms
        totalMs += ms
    }

    /**
     * Whether the frame the driver has just finished preparing was drawn.
     *
     * Separate from [onFrame] rather than an argument to it because the two are known at
     * different points: the frame time is the first thing the driver has and the draw decision
     * is the last, and moving [onFrame] to the tail would delay [FrameWatch]'s own reading by a
     * frame for no reason.
     */
    fun onFrameDrawn(drew: Boolean) {
        if (drew) drawnFrames++
    }

    /** Start again — for the button that lets a tester measure one specific thing. */
    fun reset() {
        frames = 0
        totalMs = 0.0
        worstMs = 0.0
        slowFrames = 0
        drawnFrames = 0
    }

    fun snapshot(): Snapshot = Snapshot(
        detectedTier = detectedTier,
        currentTier = currentTier,
        stepDowns = stepDowns,
        renderScale = renderScale,
        frames = frames,
        drawnFrames = drawnFrames,
        averageMs = if (frames > 0) totalMs / frames else 0.0,
        worstMs = worstMs,
        slowPercent = if (frames > 0) (slowFrames * 100.0 / frames) else 0.0
    )

    data class Snapshot(
        val detectedTier: QualityTier?,
        val currentTier: QualityTier?,
        val stepDowns: Int,
        val renderScale: Float,
        val frames: Long,
        val drawnFrames: Long,
        val averageMs: Double,
        val worstMs: Double,
        val slowPercent: Double
    ) {
        /** The number people recognise. Derived from the mean, not counted separately. */
        val averageFps: Int get() = if (averageMs > 0) (1000.0 / averageMs).roundToInt() else 0

        /**
         * What fraction of the vsyncs offered were actually rendered.
         *
         * The saving, in one number: 100 means the room drew everything it was asked to, which
         * is what it did before [RoomCadence] existed.
         */
        val drawnPercent: Double
            get() = if (frames > 0) (drawnFrames * 100.0 / frames) else 0.0

        /**
         * Frames per second actually rasterised, as opposed to [averageFps], which is the rate
         * the driver ran at. The gap between the two is the whole point.
         */
        val drawnFps: Int
            get() = if (averageMs > 0) (drawnPercent * 10.0 / averageMs).roundToInt() else 0

        /** Whether the room has been on screen long enough for the numbers to mean anything. */
        val hasData: Boolean get() = frames > 120
    }
}
