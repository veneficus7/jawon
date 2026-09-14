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
     * Full-resolution ambient occlusion, bent normals, contact-hardening shadows, a 2048
     * shadow map and a 256px reflection cubemap. What the room is meant to look like.
     *
     * **Measured, browsing the wall on a cooled Galaxy S25 Ultra:**
     *
     * | configuration | fps |
     * |---|---|
     * | HIGH as originally shipped | 44 |
     * | HIGH with cone tracing off | 67 |
     * | MEDIUM | 124 |
     *
     * **A warning about profiling this.** Numbers taken while [FrameWatch] is live are worth
     * very little: it steps the tier down mid-window, so the run stops measuring the tier it
     * started in. That is how a bisection here produced ~70fps for HIGH no matter *which*
     * individual setting was removed, including settings that turned out to cost nothing.
     * The two rows above that can be compared honestly are the first two, which differ by one
     * flag under identical conditions. Disable the adaptation before trusting anything finer.
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
     * Screen-space cone tracing — the directional half of the occlusion.
     *
     * **Off everywhere, and this is the one measurement worth trusting.** Turning it off,
     * with nothing else changed and the same conditions either side, took browsing on a
     * cooled S25 Ultra from **44fps to 67**. Half the frame budget, for a directional
     * darkening you have to be told about to notice, in a room lit by one lamp.
     *
     * It was described here as "cheap relative to AO itself", which was a guess and was
     * wrong. Everything else HIGH does — full-resolution AO, bent normals, contact-hardening
     * shadows, the 2048 map, the 256px cubemap — stays on, because those are what make the
     * room look the way the owner wants it and none of them showed a comparable cost.
     */
    val ssct: Boolean get() = false

    /**
     * Contact-hardening shadows. PCSS samples a variable penumbra per pixel; PCF is a fixed
     * kernel and much cheaper. The shadow still lands in the same place either way.
     */
    val softShadows: Boolean get() = this == HIGH

    /**
     * Whether the shelf fixtures cast shadows at all.
     *
     * The room used to have exactly one shadow caster — the key light — and losing it in the
     * two-light redesign lost every shadow in the scene. They come back from one diode per
     * ribbon (see `RoomLighting.WASH_SHADOW_COLUMN`), which is one shadow map per visible row
     * rather than one for the whole room.
     *
     * That is a real increase in the scarcest resource in the renderer, so the bottom tier does
     * without. It is also the reason this is read from the *initial* tier rather than the live
     * one: shadow casting is a property of the light as it is built, and rebuilding lights
     * mid-session to chase a tier step is the kind of scene surgery trap 1 punishes.
     */
    val shadowsOnFixtures: Boolean get() = this != LOW

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
     * How large the room's own render target is, as a fraction of the surface, at rest and
     * under load. Filament scales between the two to hold a GPU frame-time target; see
     * [RoomAtmosphere.apply].
     *
     * **Only the room scales.** The deck, the tracklist, the lyrics and every flat screen are
     * Compose, drawn into a separate layer over the Filament surface at whatever the panel is
     * running. Nothing anyone reads gets softer.
     *
     * **Why 0.75 is the ceiling and not 1.0.** The test device runs its panel at 1440x3120 on
     * 6.9 inches — about 498 pixels per inch, where the eye at arm's length resolves somewhere
     * near 330. Three quarters of that is 1080x2340, which is still above acuity, and is also
     * *exactly* the FHD+ mode Samsung ships as this phone's factory default. So the room draws
     * at the resolution the manufacturer considers correct for the screen, and the interface
     * stays at the one the owner chose. Half the pixels of the wall, for a difference that has
     * to be looked for on a scene that is deliberately dark, fogged and bloomed.
     *
     * **This is a look decision, and it is the one number to turn.** Raise the ceiling toward
     * 1.0 if the wall's spines read soft; the saving falls off as the square.
     */
    val maxRenderScale: Float
        get() = when (this) {
            // **Raised to 1.0, and the paragraph above is the argument it lost.**
            //
            // That reasoning is sound about acuity and wrong about this scene. It compares
            // resolution against what the eye resolves on a still image at arm's length, and the
            // room is not that: it is a dark, fogged, bloomed box whose whole appeal is in thin
            // bright things against black — spine lettering, the lamp's underlight, the rim of a
            // case, single dust motes. Those are exactly the features an upscale destroys first,
            // because each is a pixel or two wide and upscaling averages it with the dark
            // beside it. The result does not read as "slightly softer", it reads as less moody,
            // which is what was reported and is a fair description of losing the highlights.
            //
            // The device has the budget: the diagnostics on the test phone report 120fps with
            // 0.0% slow frames at HIGH, and the tier system is not stepping. Spending it on the
            // one thing the app is *for* is the right call, and the dynamic floor below still
            // catches a hot phone.
            HIGH -> 1.0f
            MEDIUM -> 0.85f
            LOW -> 0.68f
        }

    /**
     * The floor the same scaling may fall to when the GPU cannot keep up — which on a phone
     * mostly means when it is hot.
     *
     * This is the half of the tier system that was missing. Stepping a tier is coarse, one-way
     * and visible: it removes contact shading, or depth of field, permanently for the session.
     * Resolution is continuous, reversible, and recovers on its own once the device cools. A
     * thermally throttled phone should get a slightly softer room for a minute, not a
     * permanently plainer one.
     */
    val minRenderScale: Float
        get() = when (this) {
            HIGH -> 0.60f
            MEDIUM -> 0.55f
            LOW -> 0.50f
        }

    /**
     * Multisample anti-aliasing: coverage samples per pixel, or 1 for none (FXAA instead).
     *
     * **What aliases in this room is geometry, and that is MSAA's job.** The owner reported
     * jagged edges, and a 3x crop of his screenshot shows why: the rims of the cases are
     * thinner than a pixel, so they rasterise as broken dashes along each edge, and the case
     * outlines step. FXAA works on the finished image and cannot reconstruct a line that was
     * never drawn continuously; four coverage samples per pixel draw it. It was ruled out once
     * on the grounds that the room's aliasing was all in textures — true of the spine lettering,
     * and not of the thing he was looking at.
     *
     * Four on HIGH, two on MEDIUM. Both are cheap where it matters: on a tiling GPU the extra
     * samples live and resolve in tile memory, and Filament renders through
     * `EXT_multisampled_render_to_texture` so they never reach DRAM. LOW keeps FXAA — the
     * floor of the tier system is the device that cannot afford anything, and the room's
     * record there is that it has never been the one anybody needed.
     */
    val msaaSamples: Int
        get() = when (this) {
            HIGH -> 4
            MEDIUM -> 2
            LOW -> 1
        }

    /**
     * Steps per pixel for the volumetric fog (fog.mat), or 0 for no fog at all.
     *
     * **The most expensive thing in the room per pixel, and it is every pixel.** The fog is a
     * raymarch — for each pixel the shader walks the view ray through the air in front of the
     * rack, sampling density and the fittings' light at every step — over a quad that fills the
     * frame. Fourteen steps on HIGH is enough for a spot's cone to read as a smooth volume with
     * the per-pixel dither dissolving the steps into grain; seven on MEDIUM keeps the beams at
     * half the cost and a coarser grain. LOW has none: the floor of the tier system is the
     * device that cannot afford anything, and the room is whole without it.
     */
    val fogSteps: Int
        get() = when (this) {
            HIGH -> 14
            MEDIUM -> 7
            LOW -> 0
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
         * [HIGH] is reachable again, on capable hardware. It was briefly capped at [MEDIUM]
         * after HIGH measured 44fps — but the expensive part of HIGH was cone tracing, which
         * is now off at every tier, and MEDIUM visibly costs the room something the owner
         * wants. [FrameWatch] is what protects devices that cannot hold it.
         */
        fun detect(context: Context): QualityTier {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return MEDIUM

            if (am.isLowRamDevice) return LOW

            val memory = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val totalGb = memory.totalMem / (1024.0 * 1024.0 * 1024.0)

            return when {
                totalGb < 4.0 -> LOW
                totalGb < 7.0 -> MEDIUM
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> HIGH
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
     * Feed it one **drawn** frame: how long since the previous drawn one, and how long the
     * cadence expected that to be. Returns true when the evidence is strong enough to step
     * down a tier.
     *
     * ## Why it takes two numbers, and what it used to get wrong
     *
     * This was fed the **vsync-to-vsync delta** and called anything over 20ms slow. That was
     * right exactly once: when the room drew every vsync at 60Hz, the vsync delta *was* the
     * frame cost. Two changes since broke it, and nothing noticed because a watchdog that
     * never fires looks identical to a device that is coping.
     *
     * [RoomCadence] means most vsyncs are deliberately not drawn, so the delta stopped being a
     * measure of anything the room did. And asking the panel for 120Hz put the delta at 8.3ms,
     * which cannot cross a 20ms threshold at all — the watchdog built after three of five
     * testers reported stutter had become incapable of reporting stutter.
     *
     * The signal that survives both is whether the *drawn* frames arrive when the cadence said
     * they would. At `LIVE` on a 120Hz panel that is every 8.3ms; at `DRIFTING` it is every
     * 33ms; both are a promise the room made, and missing it by half again is the room failing
     * to keep up rather than the room idling on purpose.
     *
     * The caller skips samples where the divisor changed across the interval — the gap after a
     * finger lifts is legitimately long and means nothing.
     *
     * **It can fire more than once.** It used to latch after a single step, which quietly
     * capped how much help a struggling device could get: one notch, and then nothing however
     * badly it ran. A phone that cannot hold MEDIUM should reach LOW. There is no oscillation
     * risk in letting it repeat, because the tier only ever moves one way and stops at LOW —
     * the caller has nowhere further to go.
     *
     * @param intervalS seconds since the previous drawn frame.
     * @param expectedS what the cadence promised: `divisor / refreshRate`.
     */
    fun onDrawnFrame(intervalS: Float, expectedS: Float): Boolean {
        if (expectedS <= 0f) return false

        totalFrames++
        if (intervalS > expectedS * LATE_FACTOR) slowFrames++

        if (totalFrames < WINDOW_FRAMES) return false

        val ratio = slowFrames.toFloat() / totalFrames
        // Reset the window either way, so a rough patch during startup does not count
        // against the device forever.
        totalFrames = 0
        slowFrames = 0

        return ratio > SLOW_FRAME_RATIO
    }

    private companion object {
        /**
         * How far past its promise a drawn frame has to land to count as late.
         *
         * Half again, not a fixed millisecond figure, because the promise itself now varies:
         * 8.3ms while a finger is down and 67ms on an idle wall are both correct, and a
         * threshold that cannot tell them apart is the bug this replaced. Half again is past
         * any scheduling jitter and short of tolerating a dropped frame in two.
         */
        const val LATE_FACTOR = 1.5f

        /**
         * Drawn frames before the first verdict.
         *
         * Counted in *drawn* frames rather than vsyncs, so the window is about two seconds of
         * interaction and considerably longer on an idle wall — which is the right way round.
         * A device is judged on the states where it is actually being asked to work.
         */
        const val WINDOW_FRAMES = 240

        /** A third of frames missing the mark is a device struggling, not a hiccup. */
        const val SLOW_FRAME_RATIO = 0.33f
    }
}
