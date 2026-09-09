package com.jawon.app.ui.wallscene

import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.jawon.app.ui.theme.RoomScheme

/**
 * The grade — what happens to the room's colour between the renderer and the screen.
 *
 * ## Why this did not exist, and what that cost
 *
 * Nothing in this project had ever called `View.setColorGrading`, which means the room has been
 * shipping on Filament's default: a plain ACES-legacy tone map with no exposure, contrast,
 * white balance or curve of its own. That is a defensible default for a bright scene and close
 * to the worst case for this one. Jawon's room is a dark box whose entire appeal is thin bright
 * things against black — spine lettering, the lamp's underlight, the rim of a case, single dust
 * motes — and the tone mapper is precisely the stage that decides whether those read as *glowing*
 * or as *clipped*. The owner's verdict was "it looks so boring, it doesn't have atmosphere", and
 * an ungraded image is a large part of why: every one of the passes in [RoomAtmosphere] was
 * running into a final transform that had no opinion.
 *
 * ## AgX rather than ACES
 *
 * Filament 1.72 ships [ToneMapper.Agx], and it is the right one here for a specific reason.
 * ACES desaturates bright colour aggressively on its way to white, which is the behaviour you
 * want for a sunlit exterior and exactly wrong for a room where a saturated underlight *is* the
 * brand. AgX rolls highlights off while holding hue, and its `GOLDEN` look is almost a
 * description of the default room: one warm lamp in a dark shop.
 *
 * [ColorGrading.Builder.luminanceScaling] is switched on for the same reason and is the single
 * most important line in this file: without it, a bright saturated underlight is clipped
 * channel-by-channel and arrives at the screen as a white blob. With it, the luminance is
 * scaled and the *hue survives* — so the thing DESIGN.md §1 calls "the app's colour" stays
 * coloured at the brightness where it matters most.
 *
 * ## One grade per mode, not one grade
 *
 * A single grade cannot serve all four modes, and trying would break three of them. Vesper is a
 * warm tungsten room; Officina is a monitor in the dark with a true-black void; Vigilia is
 * cyan-and-magenta and wants to look graded; Glacies is cold slate whose *only* warmth is one
 * orange rim, which a global warming curve would destroy. So the grade is part of the mode, the
 * way the key light and the palette already are — see [RoomScheme].
 *
 * The shadow gamma is per-channel on purpose. Crushing the three channels unequally is the
 * oldest move in colour grading and the cheapest source of depth available to us: shadows that
 * lean away from the key light's hue read as *air between surfaces* rather than as absence of
 * light. It is also why each mode crushes a different channel hardest.
 *
 * ## Ownership
 *
 * A `ColorGrading` is a native Filament object and the room rebuilds its post chain more than
 * once — `RoomAtmosphere.apply` is re-run whenever SceneView overwrites it, every frame that
 * check fails. Building one per call would leak a LUT per frame. [RoomGrading] is the holder
 * that makes exactly one alive at a time, and it destroys the old one **only after** the new one
 * is bound, because destroying something still bound to a live view is the trap this project has
 * already paid for three times (ENGINEERING.md trap 1).
 */
object RoomGrade {

    /**
     * One mode's grade, as plain numbers so it can be reviewed and tested without a device.
     *
     * Every field maps to one [ColorGrading.Builder] call. Nothing is derived or clever: this is
     * a colourist's notes, and the point of keeping it as data is that the four modes can be
     * compared by reading them side by side.
     */
    data class Spec(
        /** AgX's built-in look. `NONE` is neutral; `GOLDEN` warms; `PUNCHY` lifts contrast. */
        val look: ToneMapper.Agx.AgxLook,
        /** Stops. Zero means the render's own exposure stands. */
        val exposure: Float,
        /** 0..2, 1 is untouched. AgX already carries contrast, so these stay gentle. */
        val contrast: Float,
        /** 0..2. Below 1 on the warm modes, so the underlights carry the colour alone. */
        val saturation: Float,
        /** 0..2. Lifts only the *less* saturated colours — the browns and slates. */
        val vibrance: Float,
        /** −1 cold … +1 warm. */
        val temperature: Float,
        /** −1 green … +1 magenta. */
        val tint: Float,
        /**
         * Per-channel gamma on the shadows. Above 1 darkens.
         *
         * Unequal by design — see the class KDoc. This is the field to read first if the room
         * ever looks muddy: it is what puts the black back in a dark room on an OLED panel.
         */
        val shadowGamma: FloatArray,
        /** Where the curve's shadows hand over to its highlights. 1 is Filament's default. */
        val midPoint: Float,
        /** Above 1 lets the brightest things run a little hotter before they roll off. */
        val highlightScale: Float
    ) {
        // A data class holding a FloatArray needs these written out: the generated versions
        // compare and hash the array by identity, so two identical specs would differ. Nothing
        // depends on that today, and a test that compares specs would be quietly meaningless
        // without it.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Spec) return false
            return look == other.look &&
                exposure == other.exposure &&
                contrast == other.contrast &&
                saturation == other.saturation &&
                vibrance == other.vibrance &&
                temperature == other.temperature &&
                tint == other.tint &&
                shadowGamma.contentEquals(other.shadowGamma) &&
                midPoint == other.midPoint &&
                highlightScale == other.highlightScale
        }

        override fun hashCode(): Int {
            var result = look.hashCode()
            result = 31 * result + exposure.hashCode()
            result = 31 * result + contrast.hashCode()
            result = 31 * result + saturation.hashCode()
            result = 31 * result + vibrance.hashCode()
            result = 31 * result + temperature.hashCode()
            result = 31 * result + tint.hashCode()
            result = 31 * result + shadowGamma.contentHashCode()
            result = 31 * result + midPoint.hashCode()
            result = 31 * result + highlightScale.hashCode()
            return result
        }
    }

    /**
     * The grade for one room mode.
     *
     * Pure, so the four can be argued about on the JVM. The values themselves have to be judged
     * on the device against real artwork — that is true of every number in [RoomLighting] too —
     * but the *relationships* between them are the design, and those are testable: every mode
     * crushes its blacks, no mode is allowed to wash out, and the two cold modes must not be
     * warmed globally.
     */
    fun specFor(scheme: RoomScheme): Spec = when (scheme) {

        // A record shop after closing. The only mode that gets AgX's warm look, because it is
        // the only one lit by a tungsten bulb.
        RoomScheme.MIDNIGHT_SHOP -> Spec(
            look = ToneMapper.Agx.AgxLook.GOLDEN,
            exposure = 0f,
            contrast = 1.08f,
            // Under 1 deliberately. DESIGN.md §1: the underlights are where the saturated colour
            // lives and "everything else stays warm, dark and disciplined" — so the global
            // saturation comes down a touch and the pools of colour stand out more, not less.
            saturation = 0.97f,
            // And back up through vibrance, which only touches what is *already* dull. The brown
            // shelving gets its wood back without the amber lamp going radioactive.
            vibrance = 1.12f,
            temperature = 0.05f,
            tint = 0f,
            // Red crushed hardest, blue least — so the shadows fall away COOL under a warm key.
            //
            // The opposition is the whole point, and checking RoomScheme's own numbers is what
            // makes it worth doing: this room's ambient fill is (0.251, 0.192, 0.165), which is
            // warm, the same hue family as the key. So the renderer lights its shadows with a
            // dimmer version of the same colour that lights its highlights, and nothing in the
            // scene separates lit from unlit by hue at all. The grade is the only stage that
            // can, and that separation is most of what reads as air between surfaces.
            shadowGamma = floatArrayOf(1.18f, 1.16f, 1.10f),
            midPoint = 1f,
            highlightScale = 1f
        )

        // Carbonite: cold slate with one hot process running in it.
        // `NONE`, and that is the whole point — the scheme's entire idea is that the only warmth
        // in the room is one orange rim light. AgX's golden look would warm the slate too and
        // collapse the contrast the mode is built on.
        RoomScheme.COLD_STORAGE -> Spec(
            look = ToneMapper.Agx.AgxLook.NONE,
            exposure = 0f,
            contrast = 1.16f,
            saturation = 1.0f,
            vibrance = 1.05f,
            temperature = -0.10f,
            tint = 0f,
            // Blue crushed hardest, red least — shadows lean WARM under this mode's cold key.
            //
            // This was the other way round, with a comment claiming it made the shadows cool.
            // It did, and that was the mistake: a cold key over cold shadows is one hue at two
            // brightnesses, which is the definition of muddy. A test now asserts the
            // relationship against RoomScheme.key rather than trusting the comment.
            shadowGamma = floatArrayOf(1.18f, 1.24f, 1.28f),
            midPoint = 1f,
            // The rim is allowed to run slightly hot before rolling off — it is the one thing
            // here that should feel like it is emitting rather than reflecting.
            highlightScale = 1.02f
        )

        // A late-night bar back room. The one mode that should look obviously graded.
        RoomScheme.AFTER_HOURS -> Spec(
            look = ToneMapper.Agx.AgxLook.PUNCHY,
            exposure = 0f,
            contrast = 1.14f,
            saturation = 1.12f,
            vibrance = 1.15f,
            temperature = -0.06f,
            // Toward magenta, which is the axis the whole mode sits on: cyan key, magenta rim.
            tint = 0.06f,
            // Blue crushed hardest, so the shadows lean toward the magenta the rim light is
            // already throwing — the cyan reads as the lit side and the magenta as the unlit
            // one, which is the entire cyberpunk contrast the mode exists for.
            shadowGamma = floatArrayOf(1.16f, 1.28f, 1.34f),
            midPoint = 1f,
            highlightScale = 1.04f
        )

        // A terminal room, lit only by the equipment.
        // The hardest crush of the four, and the only one where that is unambiguously right:
        // this is the one scheme whose void is a true black rather than a very dark brown, so
        // there is no warm floor for the shadows to sit on and nothing to protect.
        RoomScheme.WORKSHOP -> Spec(
            look = ToneMapper.Agx.AgxLook.NONE,
            exposure = 0f,
            // A monitor in a dark room is a high-contrast object. Phosphor against black is the
            // most contrasty thing in the app.
            contrast = 1.22f,
            saturation = 1.06f,
            vibrance = 1.12f,
            temperature = -0.04f,
            tint = -0.05f,
            // Green crushed hardest of the three, because green is what the phosphor key is
            // made of: the lit surfaces are emerald and the shadows must not be, or the whole
            // screen is one hue. Blue crushed least, so what is left in the dark goes slightly
            // cold — the colour of a dark room with a monitor in it.
            shadowGamma = floatArrayOf(1.38f, 1.44f, 1.34f),
            midPoint = 1f,
            highlightScale = 1f
        )
    }

     // Builds the Filament object for one mode.
     // Thin on purpose — every decision is in [specFor], which needs no Engine and is therefore
     // reviewable and testable. This is the part that cannot be tested without a device, so there
     // is as little of it as possible.
    fun build(engine: Engine, scheme: RoomScheme): ColorGrading {
        val spec = specFor(scheme)
        return ColorGrading.Builder()
            .toneMapper(ToneMapper.Agx(spec.look))
            // **The line that keeps the underlights coloured.** Without it a bright saturated
            // light is clipped per channel and reaches the screen white; with it the luminance
            // is scaled and the hue survives. See the class KDoc.
            .luminanceScaling(true)
            .exposure(spec.exposure)
            .contrast(spec.contrast)
            .saturation(spec.saturation)
            .vibrance(spec.vibrance)
            .whiteBalance(spec.temperature, spec.tint)
            .curves(
                spec.shadowGamma,
                floatArrayOf(spec.midPoint, spec.midPoint, spec.midPoint),
                floatArrayOf(spec.highlightScale, spec.highlightScale, spec.highlightScale)
            )
            .build(engine)
    }

    /**
     * How finely the image is dithered on its way out.
     *
     * `TEMPORAL`, explicitly, rather than by default — this room needs it more than most. A dark
     * scene spends almost all of its tonal range in the bottom few percent, where 8-bit output
     * has the fewest codes to spend, and a grading LUT quantises on the way through. Banding
     * across a large dark surface is the result, and this room is mostly large dark surfaces.
     */
    val DITHERING: View.Dithering = View.Dithering.TEMPORAL
}

/**
 * Owns the room's grade, so exactly one is alive and the dead one is never the bound one.
 *
 * `RoomAtmosphere.apply` is idempotent and gets re-run — on a tier change, and every frame that
 * SceneView's own `RenderQuality` preset has overwritten the room's settings. Building a
 * `ColorGrading` in there would allocate a native LUT per call. So the grade is held here
 * instead, rebuilt only when the mode actually changes, with the replacement **bound before** the
 * old one is released.
 *
 * That order is not fussiness. ENGINEERING.md trap 1 is this project's record of what happens when a
 * Filament resource is destroyed while something still references it: the process aborts on the
 * next commit, with a message about the *texture*, nowhere near the code that caused it.
 */
class RoomGrading {

    private var current: ColorGrading? = null
    private var currentScheme: RoomScheme? = null

    /** `true` when [view] is showing the grade this holder built — see [bind]. */
    fun isBound(view: View): Boolean {
        val grading = current ?: return false
        return view.colorGrading === grading
    }

    /**
     * Makes [view] show [scheme]'s grade, building it if the mode has changed.
     *
     * Returns without touching anything when the right grade is already bound, which is the
     * common case — this is called from the same place the atmosphere is applied, and that runs
     * again whenever SceneView overwrites the view's settings.
     */
    fun bind(view: View, engine: Engine, scheme: RoomScheme) {
        if (currentScheme == scheme && isBound(view)) return

        val next = RoomGrade.build(engine, scheme)
        val previous = current

        // Bound first. Between these two statements the old object is unreferenced by the view
        // and safe to release; the other order is the abort described above.
        view.colorGrading = next
        view.dithering = RoomGrade.DITHERING
        current = next
        currentScheme = scheme

        if (previous != null && engine.isValidColorGrading(previous)) {
            engine.destroyColorGrading(previous)
        }
    }
}
