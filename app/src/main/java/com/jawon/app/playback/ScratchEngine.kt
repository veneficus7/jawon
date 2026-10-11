package com.jawon.app.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

/** A stretch of a track, decoded: what there is to scratch. */
class ScratchWindow(
    /** Interleaved 16-bit samples. */
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int,
    /** Where in the track the first sample is, in seconds. */
    val startSeconds: Double,
    /**
     * How many frames of [samples] are audio. All of it, unless said: a decode fills an array
     * sized for what it was asked for and may be given less — the track ended — and copying nine
     * megabytes to trim the end off them is nine megabytes this can do without.
     */
    val frames: Int = samples.size / channels
) {
    val endSeconds: Double = startSeconds + frames.toDouble() / sampleRate
}

/**
 * **The sound of a record under a hand** (0.8): a [Platter] moved by a [HandTrack], read out as
 * audio.
 *
 * The record's motion is the instrument, and it is in `Platter`. This is the needle: it reads the
 * decoded audio at wherever the record is, and three things happen on the way out, each because
 * of how a real pickup behaves and each a reason the old engine did not sound like one.
 *
 * - **Pitch is speed, with nothing in the way.** The audio is read at the record's own rate, by a
 *   reader that stays clean whatever that rate is — smooth between samples when the record crawls,
 *   and averaged across them when it races, so a fast stroke is a bright chirp and not the fizz of
 *   samples being skipped. The old engine *dulled* the sound as the hand sped up, to model a
 *   stylus losing its grip; what that made was a muffled burst where a scratch has its bite.
 * - **Loudness is speed too.** A pickup answers to how fast the groove passes it, so a stroke
 *   swells as the hand gets going and dies as it turns round — every stroke has a shape, and the
 *   reversal between two strokes is a moment of nothing. That shape is the difference between
 *   *wicka-wicka* and a warble. The old engine held the level nearly flat and cut to silence
 *   under a threshold.
 * - **Grit only while it is being handled.** A little saturation, more with speed, and none at all
 *   once the record is back under the motor at normal speed — because at that point this has to
 *   be indistinguishable from the player it is about to hand back to.
 *
 * Pure: `ScratchAudio` owns the decoder and the output, and the tests and the offline renders
 * drive exactly this code with a scripted hand.
 */
class ScratchEngine(val outputRate: Int) {

    val platter = Platter()
    val hand = HandTrack()

    /** What is being read. Swapped whole — a first small stretch, then a wide one — and never edited. */
    @Volatile
    var window: ScratchWindow? = null

    /** Where the record is, in seconds of the track; for whoever is showing it. */
    @Volatile
    var positionSeconds = 0.0
        private set

    /** How fast the record is turning; for whoever is drawing it. */
    @Volatile
    var rate = 0f
        private set

    /** Under the motor and at speed: what comes out is the record playing, and can be handed back. */
    @Volatile
    var atSpeed = false
        private set

    /** Let go with the motor off, and come to a stop. */
    @Volatile
    var atRest = false
        private set

    // ---- What the gesture asks of the record ---------------------------------------------------
    //
    // Left here by the touch thread and acted on by the audio thread between two blocks, a few
    // thousandths of a second later: the record is only ever moved by the thread that is reading it.

    @Volatile
    private var landed = Double.NaN

    @Volatile
    private var letGo = 0

    /** A hand lands, at [handSeconds] on its own scale. */
    fun grab(handSeconds: Double, nowNanos: Long) {
        hand.land(handSeconds, nowNanos)
        letGo = 0
        landed = handSeconds
    }

    /** The hand lets go: the motor takes the record back up to speed, or with it off, the record coasts. */
    fun release(playing: Boolean) {
        landed = Double.NaN
        letGo = if (playing) MOTOR_ON else MOTOR_OFF
    }

    /** The hand sets the record down where it is, already playing or already still — see [Platter.place]. */
    fun place(playing: Boolean) {
        landed = Double.NaN
        letGo = if (playing) PLACED_ON else PLACED_OFF
    }

    /**
     * A new stretch of audio, for a record that has got to somewhere there was none. Brought up
     * over a hundredth of a second: sound that starts in the middle of a wave is a click.
     */
    fun arrive(stretch: ScratchWindow) {
        swell = true
        window = stretch
    }

    @Volatile
    private var swell = false

    /** Asked for from outside (a fade in at the start, out at the hand-over), reached smoothly in here. */
    @Volatile
    private var levelTarget = 0f
    private var levelStep = 0f
    private var level = 0f

    /**
     * The level the player itself is at — its ReplayGain levelling. A record taken from a player
     * turned down six decibels has to be six decibels down in the hand as well, or every touch is
     * a jump in loudness. Applied last, so the handled sound is the same sound at any level.
     */
    @Volatile
    var master = 1f

    /** How much of the handled sound is in the mix: 1 with a hand on, falling away as the motor takes over. */
    private var colour = 0f

    /** The loudness the record's speed asks for, as it has been reached — see [render]. */
    private var loud = 0f

    /** 1, except for the hundredth of a second after a stretch arrives in silence ([arrive]). */
    private var arriving = 1f
    private val arrivingStep = 1f / (0.010f * outputRate)

    private val handNow = DoubleArray(2)
    private val dt = 1.0 / outputRate

    /** Fade the output to [target] over [seconds]. */
    fun fade(target: Float, seconds: Float) {
        levelStep = 1f / (seconds * outputRate).coerceAtLeast(1f)
        levelTarget = target.coerceIn(0f, 1f)
    }

    /** At full level now, without a fade — for a record that was not playing and so has nothing to fade from. */
    fun open() {
        level = 1f
        levelTarget = 1f
    }

    val silent: Boolean get() = level <= 0f && levelTarget <= 0f

    /**
     * Writes [frames] frames of stereo into [out], moving the record through them. [startNanos] is
     * the time the first of them stands for, on the clock the hand's reports are stamped with.
     */
    fun render(out: ShortArray, frames: Int, startNanos: Long) {
        val source = window
        val nanosPerFrame = 1e9 / outputRate
        val master = master
        landed.let { if (!it.isNaN()) { landed = Double.NaN; platter.grab(it) } }
        letGo.let {
            if (it != 0) {
                letGo = 0
                if (it == MOTOR_ON || it == MOTOR_OFF) platter.release(playing = it == MOTOR_ON)
                else platter.place(playing = it == PLACED_ON)
            }
        }
        if (swell) {
            swell = false
            arriving = 0f
        }
        var i = 0
        while (i < frames) {
            // The hand is asked after every few frames rather than every one: it is a volatile
            // read, and a finger does not change its mind 48,000 times a second.
            if (platter.drive == Platter.Drive.HAND && i % HAND_EVERY == 0) {
                hand.at(startNanos + (i * nanosPerFrame).toLong(), handNow)
                platter.hand(handNow[0], handNow[1])
            }
            platter.step(dt)

            val speed = abs(platter.rate)
            val nominal = platter.nominal
            val handled = if (platter.drive == Platter.Drive.HAND) 1f else min(1f, (abs(platter.rate - nominal) * 1.6).toFloat())
            colour += (handled - colour) * COLOUR_SMOOTHING
            if (level != levelTarget) {
                level = if (level < levelTarget) min(levelTarget, level + levelStep) else maxOf(levelTarget, level - levelStep)
            }

            var left = 0f
            var right = 0f
            if (source != null && level > 0f && speed > SILENT_BELOW) {
                val at = (platter.position - source.startSeconds) * source.sampleRate
                val stride = speed * source.sampleRate / outputRate
                // Loudness is measured against the motor's speed, not against 1: with the deck's
                // tempo up or down, "playing normally" is that speed, and has to come out untouched.
                // The loudness of the speed, reached over a few thousandths of a second rather
                // than at once: a record set down out of a spin goes from many times its speed
                // to exactly its speed between two samples, and its level with it.
                loud += (loudness(speed / nominal) - loud) * LOUDNESS_SMOOTHING
                if (arriving < 1f) arriving = min(1f, arriving + arrivingStep)
                val gain = loud * edge(at, source.frames) * level * arriving
                if (gain > 0f) {
                    val bite = colour * bite(speed)
                    left = saturate(read(source, at, stride, 0) * gain, bite) * master
                    right = if (source.channels > 1) saturate(read(source, at, stride, 1) * gain, bite) * master else left
                }
            }
            out[2 * i] = Pcm16.toShort(left)
            out[2 * i + 1] = Pcm16.toShort(right)
            i++
        }
        positionSeconds = platter.position
        rate = platter.rate.toFloat()
        atSpeed = platter.atSpeed
        atRest = platter.atRest
    }

    companion object {
        private const val MOTOR_ON = 1
        private const val MOTOR_OFF = 2
        private const val PLACED_ON = 3
        private const val PLACED_OFF = 4

        /** A time constant of about four thousandths of a second at 48kHz: nothing a hand does is that quick. */
        private const val LOUDNESS_SMOOTHING = 0.005f

        private const val HAND_EVERY = 16

        /** Slower than this the record is, to the ear, not moving. A fade has already taken the level to nothing by here. */
        private const val SILENT_BELOW = 0.0005

        private const val COLOUR_SMOOTHING = 0.0009f

        /**
         * How loud a record is at [speed] — 1 at normal speed.
         *
         * A pickup's output rises with the speed of the groove under it, so in principle this is
         * simply proportional. In practice the playback curve every record is cut for gives back
         * much of that at the top and bottom of the spectrum, and what is left to the ear is
         * gentler: about half the change in decibels. So it rises as the 0.6th power — a stroke at
         * a fifth of normal speed is some eight decibels down, a growl and not a whisper — and
         * above normal speed it leans up only a little, because a hard shove is already brighter
         * and denser and does not need to be much louder as well.
         *
         * **And past [SEARCH_FROM] it falls away again.** Nobody scratches at five times speed;
         * a record going that fast is being spun to find a place in it, and what that should
         * sound like is a tape being cued — the music, tiny and high and going by — not the
         * loudest thing the app can make. A stylus does the same for its own reasons: it cannot
         * follow a groove at that speed and most of the signal is beyond anything it can trace.
         */
        fun loudness(speed: Double): Float = when {
            speed <= 1.0 -> speed.pow(0.6).toFloat()
            speed <= SEARCH_FROM -> min(LOUDEST, (1.0 + 0.2 * (speed - 1.0)).toFloat())
            else -> (LOUDEST * (SEARCH_FROM / speed).pow(1.2)).toFloat()
        }

        private const val LOUDEST = 1.45f

        /** Faster than this is travelling, not scratching. */
        const val SEARCH_FROM = 4.0

        /**
         * How hard the handled sound is driven at [speed]: a little always, more as the hand moves
         * faster. Kept modest: rendered over music, the first values put the scratching four to
         * six decibels above the record it was cut from, which is a jump in loudness every time a
         * finger lands. At these it sits a couple of decibels proud — enough to cut through.
         */
        fun bite(speed: Double): Float {
            val handled = 0.2 + 0.26 * min(speed, 3.2)
            // And less of it again once the record is being spun rather than scratched: the grit
            // is density, and density is level — at eight times speed it was putting back
            // everything the loudness had taken away, and a search was as loud as the song.
            return (if (speed <= SEARCH_FROM) handled else handled * (SEARCH_FROM / speed).pow(1.5)).toFloat()
        }

        /**
         * A soft clip that leaves quiet sound alone as [amount] goes to nothing, and at full scale
         * returns full scale whatever the amount — so it adds density, never level beyond the rails.
         * Odd-symmetric: the harmonics it adds are the warm ones.
         */
        fun saturate(sample: Float, amount: Float): Float {
            if (amount <= 0f) return sample.coerceIn(-1f, 1f)
            val shaped = sample * (1f + amount) / (1f + amount * abs(sample))
            return shaped.coerceIn(-1f, 1f)
        }

        /** A few milliseconds of taper at either end of what was decoded, so running off it is a fade and not a click. */
        private fun edge(at: Double, frames: Int): Float {
            val taper = 220.0
            val inside = min(at, frames - 1 - at)
            return (inside / taper).coerceIn(0.0, 1.0).toFloat()
        }

        /**
         * One channel of [source] at frame [at], for a record passing [stride] source frames per
         * output frame.
         *
         * At or below normal speed: a curve through the four nearest samples, which keeps slow
         * drags smooth where a straight line between two is audibly rough. Above it, several
         * samples are passing for each one written, and taking only the nearest would fold
         * everything above the new limit back down as hash — so it is the weighted average of the
         * stretch that went by, widest in the middle, which is a low-pass at about the right place
         * for whatever the speed is.
         */
        fun read(source: ScratchWindow, at: Double, stride: Double, channel: Int): Float {
            if (stride <= 1.0) return curve(source, at, channel)
            val taps = min(MOST_TAPS, 2 * ceil(stride).toInt() + 1)
            val half = (taps - 1) / 2
            val spacing = stride / (half + 1)
            var sum = 0f
            var weights = 0f
            for (k in -half..half) {
                val weight = 1f - abs(k) / (half + 1f)
                sum += weight * line(source, at + k * spacing, channel)
                weights += weight
            }
            return sum / weights
        }

        /** Enough to average a record going twelve times its speed, which is as fast as a hand spins one. */
        private const val MOST_TAPS = 25

        /** A Catmull-Rom curve through the four samples round [at]. */
        private fun curve(source: ScratchWindow, at: Double, channel: Int): Float {
            val base = floor(at).toInt()
            val t = (at - base).toFloat()
            val a = sample(source, base - 1, channel)
            val b = sample(source, base, channel)
            val c = sample(source, base + 1, channel)
            val d = sample(source, base + 2, channel)
            return b + 0.5f * t * (c - a + t * (2f * a - 5f * b + 4f * c - d + t * (3f * (b - c) + d - a)))
        }

        private fun line(source: ScratchWindow, at: Double, channel: Int): Float {
            val base = floor(at).toInt()
            val t = (at - base).toFloat()
            val a = sample(source, base, channel)
            return a + (sample(source, base + 1, channel) - a) * t
        }

        /** Off either end is silence, not the last sample held: a held sample is a thump when the record comes back. */
        private fun sample(source: ScratchWindow, frame: Int, channel: Int): Float {
            if (frame < 0 || frame >= source.frames) return 0f
            return source.samples[frame * source.channels + channel] / 32768f
        }
    }
}
