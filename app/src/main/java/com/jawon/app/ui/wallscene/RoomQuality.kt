package com.jawon.app.ui.wallscene

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * How much of the room's rendering this device gets.
 *
 * **Why this exists, and why it adapts rather than just detecting.**
 *
 * Every performance number in `ENGINEERING.md` was measured on one Galaxy S25 Ultra, cooled, and
 * that turned out to be a poor sample: of the first five testers, three reported the app
 * "not that smooth" — on S23 and S23 Ultra hardware, which is a flagship a generation back,
 * not a budget phone. If it stutters there it will be worse everywhere else.
 *
 * A device database is the usual answer and it is the wrong one here. There is no way to
 * enumerate the GPUs this will run on, the same SoC throttles differently in different
 * chassis, and the S25 measurements already showed the same build swinging between 124fps
 * cool and 88fps hot. So the tier is a *guess* refined by *measurement*: pick a starting
 * point from what the device says about itself, then watch actual frame times and step down
 * if the guess was optimistic.
 *
 * Stepping is one-way within a session. A renderer that oscillates between quality levels is
 * more distracting than one that is simply a notch lower, and the thermal case means a
 * device that has slowed once will usually slow again.
 */
enum class QualityTier {
    /**
     * Everything on. **Never chosen automatically — see [detect].**
     *
     * Measured on a cooled Galaxy S25 Ultra, browsing the wall:
     *
     * | tier | fps |
     * |---|---|
     * | HIGH | **44** |
     * | HIGH without SSCT | 67 |
     * | MEDIUM | **124** |
     * | LOW | 124 |
     *
     * Three times the frame cost of MEDIUM on the fastest phone this has ever run on, for
     * full-resolution ambient occlusion, cone tracing, bent normals, contact-hardening
     * shadows and a 2048 map. Screen-space cone tracing alone is a third of it. Nothing in
     * that list survives being looked at next to those numbers, and MEDIUM is not visibly
     * poorer at arm's length.
     *
     * Kept rather than deleted because the measurements are worth being able to re-run, and
     * because hardware moves. It is a ceiling, not a default.
     */
    HIGH,

    /** The expensive-but-subtle passes come down. Aimed at the S23-class reports. */
    MEDIUM,

    /** Depth of field and contact shading off entirely; the room still reads. */
    LOW;

    val below: QualityTier?
        get() = when (this) {
            HIGH -> MEDIUM
            MEDIUM -> LOW
            LOW -> null
        }

    // ---- What each tier actually changes -------------------------------------------

    /** Depth of field is the single most expensive thing during the open sequence. */
    val depthOfField: Boolean get() = this != LOW

    /**
     * Ambient occlusion resolution, as a fraction. Full-res AO is what resolves the 1mm seam
     * where the lid meets the tray; at half it survives as shading without the crisp line.
     */
    val aoResolution: Float get() = if (this == HIGH) 1.0f else 0.5f

    /** Contact shading at all. It is most of what grounds objects, so it goes last. */
    val ambientOcclusion: Boolean get() = this != LOW

    /**
     * Screen-space cone tracing, the directional half of the occlusion. Cheap relative to
     * AO itself but the first thing worth losing once AO is already halved.
     */
    val ssct: Boolean get() = this == HIGH

    /**
     * Contact-hardening shadows. PCSS samples a variable penumbra per pixel; PCF is a fixed
     * kernel and much cheaper. The shadow still lands in the same place either way.
     */
    val softShadows: Boolean get() = this == HIGH

    /** Shadow map, in texels per side. Halving the side quarters the memory. */
    val shadowMapPx: Int
        get() = when (this) {
            HIGH -> 2048
            MEDIUM -> 1024
            LOW -> 512
        }

    /**
     * IBL cubemap face size. Baked once at startup, so unlike the rest of this it cannot
     * change when the tier steps down mid-session — the environment is built before the
     * first frame and rebuilding it would stall.
     */
    val cubemapPx: Int
        get() = when (this) {
            HIGH -> 256
            MEDIUM -> 128
            LOW -> 64
        }

    /**
     * Dust motes per square metre of wall.
     *
     * Never zero, and thinned rather than cut. Every mote is a blended quad, so the cost is
     * real, but it is overdraw rather than geometry and `DustGeometry`'s `MIN_MOTES` floor
     * means even LOW keeps a room with air in it. The large savings are elsewhere — turning
     * off depth of field and ambient occlusion is worth far more than deleting the dust, and
     * costs the room much less of its character.
     */
    val dustDensity: Float
        get() = when (this) {
            HIGH -> 700f
            MEDIUM -> 380f
            LOW -> 220f
        }

    companion object {

        /**
         * The opening guess, from what the device will tell us without rendering anything.
         *
         * Deliberately crude. RAM class and API level are weak proxies for GPU throughput —
         * they say more about when the phone was built than what it can draw — but they are
         * available before the first frame, and [FrameWatch] corrects the guess within a few
         * seconds if it is wrong. A more elaborate detector would still be a guess, just a
         * slower one.
         *
         * Biased toward starting one notch low rather than high. Being handed a slightly
         * plainer room is a much smaller insult than three seconds of stutter on first
         * launch, and stepping *down* is the only direction available afterwards.
         *
         * **[MEDIUM] is the ceiling. This never returns [HIGH].** It used to, for any recent
         * 8GB device, and that is what an S25 Ultra is — so the best phone available was
         * being handed a configuration that runs at 44fps on it. See [HIGH] for the numbers.
         * There is no device this could be right for: HIGH is not a tier for good hardware,
         * it is a tier for hardware that does not exist.
         */
        fun detect(context: Context): QualityTier {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return MEDIUM

            if (am.isLowRamDevice) return LOW

            val memory = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val totalGb = memory.totalMem / (1024.0 * 1024.0 * 1024.0)

            return when {
                totalGb < 4.0 -> LOW
                // Everything else starts here. MEDIUM measured 124fps on an S25 Ultra, the
                // same as LOW, so there is nothing to be gained by aiming higher and a great
                // deal to lose — and FrameWatch still drops anything that cannot hold it.
                else -> MEDIUM
            }
        }
    }
}

/**
 * Watches frame intervals and reports when the room is running slower than it should.
 *
 * The whole point is to catch the devices that were never tested. It is deliberately slow to
 * fire: a handful of long frames is a texture upload or a garbage collection, not a device
 * that cannot keep up, and dropping quality for those would be both wrong and visible.
 *
 * No allocation and no branching worth speaking of — this runs inside the frame callback.
 */
class FrameWatch {

    private var slowFrames = 0
    private var totalFrames = 0

    /**
     * Feed it the frame delta in seconds. Returns true when the evidence is strong enough to
     * step down a tier.
     *
     * A "slow" frame is one that missed a 60fps deadline by a clear margin. Not 120: plenty
     * of perfectly good devices run at 60, and treating that as failure would downgrade
     * every one of them.
     *
     * **It can fire more than once.** It used to latch after a single step, which quietly
     * capped how much help a struggling device could get: one notch, and then nothing however
     * badly it ran. A phone that cannot hold MEDIUM should reach LOW. There is no oscillation
     * risk in letting it repeat, because the tier only ever moves one way and stops at LOW —
     * the caller has nowhere further to go.
     */
    fun onFrame(dt: Float): Boolean {
        totalFrames++
        if (dt > SLOW_FRAME_S) slowFrames++

        if (totalFrames < WINDOW_FRAMES) return false

        val ratio = slowFrames.toFloat() / totalFrames
        // Reset the window either way, so a rough patch during startup does not count
        // against the device forever.
        totalFrames = 0
        slowFrames = 0

        return ratio > SLOW_FRAME_RATIO
    }

    private companion object {
        /** ~50fps. Comfortably past a 60fps deadline, well short of calling 60fps a problem. */
        const val SLOW_FRAME_S = 0.020f

        /** About four seconds at 60fps before the first verdict. */
        const val WINDOW_FRAMES = 240

        /** A third of frames missing the mark is a device struggling, not a hiccup. */
        const val SLOW_FRAME_RATIO = 0.33f
    }
}
