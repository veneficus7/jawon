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

    private var frames = 0L
    private var totalMs = 0.0
    private var worstMs = 0.0

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

    /** Start again — for the button that lets a tester measure one specific thing. */
    fun reset() {
        frames = 0
        totalMs = 0.0
        worstMs = 0.0
        slowFrames = 0
    }

    fun snapshot(): Snapshot = Snapshot(
        detectedTier = detectedTier,
        currentTier = currentTier,
        stepDowns = stepDowns,
        frames = frames,
        averageMs = if (frames > 0) totalMs / frames else 0.0,
        worstMs = worstMs,
        slowPercent = if (frames > 0) (slowFrames * 100.0 / frames) else 0.0
    )

    data class Snapshot(
        val detectedTier: QualityTier?,
        val currentTier: QualityTier?,
        val stepDowns: Int,
        val frames: Long,
        val averageMs: Double,
        val worstMs: Double,
        val slowPercent: Double
    ) {
        /** The number people recognise. Derived from the mean, not counted separately. */
        val averageFps: Int get() = if (averageMs > 0) (1000.0 / averageMs).roundToInt() else 0

        /** Whether the room has been on screen long enough for the numbers to mean anything. */
        val hasData: Boolean get() = frames > 120
    }
}
