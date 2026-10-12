package com.jawon.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.jawon.app.data.playback.PlaybackRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Hearing a record while your hand is on it — and the two hand-overs either side.
 *
 * The sound itself is [ScratchEngine]: a record with weight ([Platter]), read by a needle. This
 * is everything that has to be true around it on a phone.
 *
 * ### Why none of this can be ExoPlayer
 *
 * A player will run at half speed or double on request. It will not run at **minus** one, and
 * backwards is half of scratching. So for as long as the record is in the hand's keeping, a
 * stretch of audio round the playhead is decoded into memory and read out by the engine.
 *
 * ### Taking the record
 *
 * A finger lands, and the engine starts where the player truly is, at full speed, and the two
 * cross over a fiftieth of a second; from there the record winds down under the finger, which is
 * the sound a touch should make.
 *
 * That needs audio in hand the instant the finger lands, and a decoder takes a tenth of a second
 * and more to start. So for as long as the disc can be touched ([arm]) a few seconds round the
 * playhead are **kept decoded** ([RollingDecoder]), and a touch is answered at once. Where they
 * are not — a stream, a record that has only just started — a small stretch is decoded there and
 * then, and until it is ready **the player goes on playing**: a record does not fall silent
 * because a hand is approaching it. (A tap too brief to get that far touches nothing at all.)
 * Either way a wide stretch follows, a dozen turns of the disc each way.
 *
 * ### Giving it back
 *
 * The finger lifts. The engine winds the record back up — or spins it back, if it was thrown —
 * and goes on playing it: for a moment the scratch *is* the music. Where it will be when it
 * reaches speed is known the moment it is let go ([Platter.settleFrom]), so the player is parked
 * a little past there, silent, and started as the record arrives; the two are crossed. No gap,
 * no jump: the wind-up is the music returning. See [handBackPlaying].
 *
 * ### What it must never do
 *
 * Stall the frame loop, or leave the player muted. Decoding is on IO, rendering is its own
 * thread, the gesture only ever leaves a number for it; and every way out of here — a finish, a
 * failure, a case closed under the finger — ends with the player's own level put back.
 */
@Singleton
class ScratchAudio @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playback: PlaybackRepository,
    private val link: ScratchLink
) {
    /**
     * **Nothing here may ever take the app down.** This is audio for a finger on a disc, reaching
     * into a player that belongs to a service; if any of it throws, the worst that may happen is
     * that the scratch ends and the music is as it was.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, failure ->
            Log.w(TAG, "the scratch gave up: $failure")
            runCatching {
                val restart = resume && phase != Phase.IDLE && phase != Phase.PRIMING
                finish()
                if (restart) link.setPlaying(true)
            }
        }
    )

    private enum class Phase {
        IDLE,

        /** A finger is down and the first stretch is still being decoded. The player is untouched. */
        PRIMING,

        /** The engine has the record. */
        HELD,

        /** Let go: the engine is bringing the record back to the player, or to rest. */
        RELEASING
    }

    private var phase = Phase.IDLE
    private var job: Job? = null

    /** Which track, how long it is, and whether it was playing when the finger landed. */
    private var trackIndex = 0
    private var trackSeconds = 0.0
    private var resume = false

    /** The file the record in hand was decoded from. */
    private var scratching: Uri? = null

    /** How fast the player was running when the finger landed — the deck's tempo; the speed "playing" means. */
    private var nominal = 1.0

    /** The first stretch being decoded, and what it has grown to if that finished before the record was taken. */
    private var decoding: Job? = null
    private var grown: ScratchWindow? = null

    /** The wide stretch on its way — see [widen]. */
    private var widening: Job? = null

    /**
     * How much longer this phone's player takes to be heard, once told to play, than the scratch's
     * own output does — in seconds. Learnt, a little more each time a record is handed back, and
     * kept: it is a fact about the phone, and the hand-back is only as tight as this is right.
     *
     * The *difference*, not the whole delay, because most of the delay is the way to whatever is
     * making the sound, and both go that way: a speaker is a few hundredths of a second off and
     * a Bluetooth headphone a few tenths, and a number learnt on one would be badly wrong on the
     * other. What is left once that is taken out is the player's own — its deeper buffer, its
     * getting going — and is the same wherever the sound ends up.
     */
    private var playExtra: Double
        get() {
            if (playExtraCached.isNaN()) {
                playExtraCached = prefs.getFloat(KEY_PLAY_EXTRA, PLAY_EXTRA_SECONDS.toFloat()).toDouble()
                    .coerceIn(PLAY_EXTRA_LEAST, PLAY_EXTRA_MOST)
            }
            return playExtraCached
        }
        set(value) {
            playExtraCached = value.coerceIn(PLAY_EXTRA_LEAST, PLAY_EXTRA_MOST)
            prefs.edit().putFloat(KEY_PLAY_EXTRA, playExtraCached.toFloat()).apply()
        }
    private var playExtraCached = Double.NaN

    /** The last few measurements of that, newest last; the middle one is what is used. */
    private val measured = ArrayDeque<Double>()

    private val prefs by lazy { context.getSharedPreferences(HeadphoneResume.PREFS_FILE, Context.MODE_PRIVATE) }

    /** Counts the times a record has been taken, so a measurement begun before one can tell it was interrupted. */
    private var taken = 0

    // ---- Kept ready --------------------------------------------------------------------------------

    /** A stretch round the playhead, decoded before anybody asked — see [arm]. */
    private var ready: ScratchWindow? = null
    private var readyUri: Uri? = null
    private var keeping: Job? = null

    /** The hand's place on its own scale, kept from the first touch so nothing moved before the engine started is lost. */
    @Volatile
    private var handSeconds = 0.0

    /**
     * One time the engine has the record: its own engine, its own output, its own thread. Never
     * reused — the thread of the last one may still be winding down when the next begins, and two
     * threads moving one record is a record in two places.
     */
    private class Session(val engine: ScratchEngine, val track: AudioTrack, val burst: Int) {
        @Volatile
        var alive = true

        /** Frames handed to the output so far, for knowing how far behind the write the ear is. */
        @Volatile
        var written = 0L

        var writer: Thread? = null
    }

    private var session: Session? = null
    private val engine: ScratchEngine? get() = session?.engine
    private val running: Boolean get() = session?.alive == true

    // ---- What the room asks ------------------------------------------------------------------

    /** The engine has the record under a finger: its position is where the music is. */
    val holding: Boolean get() = phase == Phase.HELD

    /** Nobody is touching the record and the engine is still turning it — winding up, or spinning back. */
    val turning: Boolean get() = phase == Phase.RELEASING

    /** How fast the record is turning, for drawing it. 1 is the speed it plays at. */
    val rate: Float get() = engine?.let { (it.rate / nominal).toFloat() } ?: 0f

    /** The vinyl room's, at the end of a side: see [ScratchLink.stopAtEndOfTrack]. Main thread. */
    fun stopAtEndOfTrack(stop: Boolean) = link.stopAtEndOfTrack(stop)

    /** The player has stopped by itself at the end of a track. Main thread. */
    fun endedTrack(): Boolean = link.endedTrack()

    /**
     * Where the player itself is, and on which track: its own clock, asked every frame, where
     * what the rooms are told arrives twice a second. An arm drawn from the second would cross a
     * record in steps. Null, or −1, with no player. Main thread.
     */
    fun playerPositionMs(): Long? = link.positionMs()
    fun playerTrack(): Int = link.trackIndex()

    /** Where the record is in its track, while the engine has it; null when it does not. */
    fun positionMs(): Long? =
        if (phase == Phase.HELD || phase == Phase.RELEASING) engine?.let { (it.positionSeconds * 1000).toLong() } else null

    /**
     * The disc can be touched ([armed]), or no longer can.
     *
     * While it can, a few seconds round the playhead are kept decoded, so that a finger finds the
     * record already in hand. Only for a file on the phone: keeping a stream decoded would be
     * fetching it a second time over the network, for a touch that may never come.
     *
     * **Said again and again, not once**: the room calls this every frame it draws. A room that
     * has stopped drawing — under a panel, behind another room, with the screen off and the music
     * playing on — has no way to say so, and a decoder following a record nobody can touch is
     * battery spent on nothing. So saying nothing for a second is the same as saying no.
     */
    fun arm(armed: Boolean) {
        if (!armed) {
            if (keeping == null) return
            keeping?.cancel()
            keeping = null
            ready = null
            readyUri = null
            return
        }
        armedAt = SystemClock.uptimeMillis()
        if (keeping != null) return
        keeping = scope.launch {
            var rolling: RollingDecoder? = null
            try {
                while (SystemClock.uptimeMillis() - armedAt < ARM_LAPSES_MS) {
                    val uri = link.uri()
                    val at = link.positionMs()?.let { it / 1000.0 }
                    if (headroom() < KEEP_HEADROOM_BYTES) {
                        // No room to hold audio on the chance of a touch: the touch will wait
                        // the tenth of a second it used to.
                        ready = null
                        readyUri = null
                    } else if (phase == Phase.IDLE && uri != null && at != null && onThePhone(uri)) {
                        if (rolling?.uri != uri) {
                            rolling?.let { old -> withContext(Dispatchers.IO) { old.close() } }
                            rolling = RollingDecoder(context, uri)
                        }
                        val decoder = rolling
                        if (!covers(ready, readyUri, uri, at, READY_TOP_UP_SECONDS) && decoder != null && !decoder.failed) {
                            val fresh = runInterruptible(Dispatchers.IO) {
                                decoder.cover(at - READY_BEHIND_SECONDS, at + READY_AHEAD_SECONDS)
                            }
                            if (fresh != null) {
                                ready = fresh
                                readyUri = uri
                            }
                        }
                    } else if (phase == Phase.HELD || phase == Phase.RELEASING) {
                        // **In the hand, the same decoder follows the record instead of the
                        // player** (0.7.8). A disc spun to find a place leaves the stretch that
                        // was decoded round where it was taken in well under a second, and the
                        // wide one after it is half a minute of audio from a decoder that has
                        // to start first. This one is already running: a few seconds round
                        // wherever the record has got to are a few hundredths of a second away,
                        // so there is sound under the finger on the way, and sound where it stops.
                        val taken = session
                        val held = scratching
                        if (taken != null && held != null && onThePhone(held)) {
                            if (rolling?.uri != held) {
                                rolling?.let { old -> withContext(Dispatchers.IO) { old.close() } }
                                rolling = RollingDecoder(context, held)
                            }
                            val decoder = rolling
                            val place = taken.engine.positionSeconds
                            if (decoder != null && !decoder.failed && !holds(taken.engine.window, place)) {
                                // More of it the way the record is going.
                                val ahead = if (taken.engine.rate < -0.5f) FOLLOW_SECONDS * 0.3 else FOLLOW_SECONDS * 0.7
                                val fresh = runInterruptible(Dispatchers.IO) {
                                    decoder.cover(place - (FOLLOW_SECONDS - ahead), place + ahead)
                                }
                                if (fresh != null && session === taken) adopt(taken.engine, fresh)
                            }
                        }
                    }
                    delay(if (phase == Phase.HELD || phase == Phase.RELEASING) FOLLOW_EVERY_MS else READY_EVERY_MS)
                }
                // Nobody has said the disc can be touched for a while: it cannot.
                keeping = null
                ready = null
                readyUri = null
            } finally {
                val old = rolling
                if (old != null) withContext(NonCancellable + Dispatchers.IO) { old.close() }
            }
        }
    }

    private var armedAt = 0L

    private fun onThePhone(uri: Uri): Boolean = uri.scheme == "content" || uri.scheme == "file"

    /**
     * Whether [window] has sound for a record at [at]: a little behind it and a little ahead —
     * or as much of either as the track itself has. A record turned off the end of its track is
     * held by anything: there is nothing there to decode.
     */
    private fun holds(window: ScratchWindow?, at: Double): Boolean {
        if (at < 0.0 || at > trackSeconds) return true
        window ?: return false
        val behind = min(HOLD_BEHIND_SECONDS, at)
        val ahead = min(HOLD_AHEAD_SECONDS, trackSeconds - at)
        return at - behind >= window.startSeconds - 0.06 && at + ahead <= window.endSeconds + 0.06
    }

    /**
     * Gives the engine [candidate] if that is better than what it has for where the record is:
     * one that has sound there over one that has not, and of two that have, the longer. A
     * stretch decoded for somewhere the record has since left is simply dropped.
     */
    private fun adopt(engine: ScratchEngine, candidate: ScratchWindow) {
        val at = engine.positionSeconds
        val now = engine.window
        if (!holds(candidate, at)) return
        when {
            // Out of silence: brought up, not switched on.
            now == null || at < now.startSeconds || at > now.endSeconds -> engine.arrive(candidate)
            !holds(now, at) || candidate.endSeconds - candidate.startSeconds > now.endSeconds - now.startSeconds ->
                engine.window = candidate
        }
    }

    /** How much more the app could still allocate, in bytes. */
    private fun headroom(): Long = Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) }

    /**
     * Whether [window], decoded from [of], has enough of [uri] round [at] — a little behind, and
     * [ahead] seconds in front.
     *
     * Asked with two different [ahead]s, and the gap between them is the point: the stretch is
     * topped up while it still has more in front of it than a touch needs, so that a touch never
     * arrives in the moment between it running short and the decoder catching it up.
     */
    private fun covers(window: ScratchWindow?, of: Uri?, uri: Uri, at: Double, ahead: Double): Boolean =
        window != null && of == uri &&
            // (A track's first sample is a few hundredths of a second in, not at nought: at
            // the very start there is nothing behind to ask for, and the little that is missing
            // is silence anyway.)
            at > window.startSeconds + min(READY_BEHIND_LEAST_SECONDS, at) - 0.06 &&
            at + ahead < window.endSeconds

    /**
     * A finger lands on the disc of [uri], track [trackIndex] of the record, [trackDurationMs]
     * long. [playing] is whether the music was going; [positionMs] is the room's idea of where,
     * used only if the player cannot say.
     */
    fun grab(uri: Uri, trackIndex: Int, trackDurationMs: Long, playing: Boolean, positionMs: Long) {
        val current = engine
        if (phase == Phase.RELEASING && current != null && running && this.trackIndex == trackIndex) {
            // Caught again while it was still winding up: the same record, straight back in the
            // hand. The phase changes *before* the hand-back is cancelled — cancelling runs its
            // tidying-up there and then, and that has to find a record that is held, not one
            // that is over, or it closes the output this is about to go on using.
            phase = Phase.HELD
            taken++
            job?.cancel()
            job = null
            handSeconds = 0.0
            current.grab(0.0, System.nanoTime())
            current.fade(1f, 0.01f)
            link.setLevel(0f)
            // Told to stop whether or not it has got going yet: a player still starting up after
            // the hand-back's seek would otherwise start, unheard, under the hand.
            if (resume) scope.launch { playerPlay(false) }
            Log.i(TAG, "caught again at ${"%.3f".format(current.positionSeconds)}s, turning at ${"%.2f".format(current.rate)}")
            return
        }
        // Caught mid-return on some other track, the music was still — as far as whoever is
        // holding it is concerned — playing, whatever the player happens to say this instant.
        val wasGoing = resume && (phase == Phase.HELD || phase == Phase.RELEASING)
        cancel()
        this.trackIndex = trackIndex
        trackSeconds = trackDurationMs / 1000.0
        resume = playing || wasGoing
        nominal = link.speed()
        scratching = uri
        taken++
        handSeconds = 0.0
        phase = Phase.PRIMING
        grown = null
        val kept = ready
        val live = link.positionMs()
        if (kept != null && live != null && covers(kept, readyUri, uri, live / 1000.0, READY_AHEAD_LEAST_SECONDS)) {
            // Already in hand: the record is taken now, in the same breath as the touch.
            job = scope.launch {
                takeOver(kept, live, live = true, primedMs = 0)
                widen(uri, live)
            }
            return
        }
        // Round where the player truly is. The room's idea of that is a third of a second old —
        // it learns the position a few times a second — and a stretch centred there is a stretch
        // with that much less of the record ahead of the finger.
        val around = link.positionMs()?.takeIf { playing } ?: positionMs
        val began = System.nanoTime()
        val early = CompletableDeferred<ScratchWindow?>()
        decoding = scope.launch {
            // **In two steps, from one pass.** What decides how soon a touch is answered is how
            // much has to be decoded before there is anything to play, so the first of it is
            // handed over as soon as it reaches a little past the playhead — a second of audio,
            // not three — and the same decoder carries on to the few seconds a first stroke can
            // reach. Measured on the emulator: 134ms from touch to sound before this, 60 after.
            val all = runInterruptible(Dispatchers.IO) {
                decode(uri, around, FIRST_BEFORE_MS, FIRST_AFTER_MS, EARLY_AFTER_MS) { early.complete(it) }
            }
            val first = if (early.isCompleted) early.getCompleted() else null
            early.complete(all)
            if (all == null || all === first) return@launch
            val taken = session
            if (taken == null) grown = all
            else if (taken.engine.window === first) taken.engine.window = all
        }
        job = scope.launch {
            // **A record does not go on playing under a hand for long.** A file on the phone is
            // ready in a few hundredths of a second and the music simply carries on until it is.
            // A stream, or a card that had gone to sleep, can take seconds — and music that plays
            // on regardless while a finger drags the disc round is a disc that is not connected
            // to anything. Past this, the hold begins in silence and the sound joins it when it can.
            val inTime = withTimeoutOrNull(PRIME_LONGEST_MS) { early.await(); true } == true
            if (!inTime) holdSilently()
            val first = early.await()
            if (first == null) {
                // Nothing to scratch with: a silent hold, which is what a scratch was before it had a sound.
                if (inTime) holdSilently()
                return@launch
            }
            takeOver(grown ?: first, around, live = inTime, primedMs = (System.nanoTime() - began) / 1_000_000)
            widen(uri, around)
        }
    }

    /** The hold with no sound of its own: the player stopped, and (by the room) walked along behind the disc. */
    private suspend fun holdSilently() {
        phase = Phase.HELD
        if (resume) playerPlay(false)
        Log.i(TAG, "holding in silence")
    }

    // The player is told directly where it can be (see ScratchLink), and through the repository —
    // which will start one — where there is none yet.

    private suspend fun playerPlay(playing: Boolean) {
        if (!link.setPlaying(playing)) playback.setPlaying(playing)
    }

    private suspend fun playerSeek(trackIndex: Int, positionMs: Long) {
        if (!link.seek(trackIndex, positionMs)) playback.seekTo(trackIndex, positionMs)
    }

    /**
     * **Then widen it, because the first stretch is not enough to scratch with.** It cannot
     * simply be decoded wide to begin with: the record has to be in the hand within a few
     * hundredths of a second, and most of a minute of audio takes longer than that. The swap is
     * one reference; the engine reads whichever it finds.
     *
     * Its own job, and one that outlives the finger: a record let go and caught again a moment
     * later is the same record, and still wants the wide stretch that was on its way.
     */
    private fun widen(uri: Uri, positionMs: Long) {
        val taken = session ?: return
        widening?.cancel()
        widening = scope.launch {
            val wide = runInterruptible(Dispatchers.IO) { decode(uri, positionMs, WIDE_MS, WIDE_MS) }
            if (wide != null && session === taken) {
                adopt(taken.engine, wide)
                Log.i(TAG, "a wide stretch, ${"%.1f".format(wide.startSeconds)}s to ${"%.1f".format(wide.endSeconds)}s")
            }
        }
    }

    /**
     * Walks the stopped player along behind the disc while it is held, to ([trackIndex],
     * [offsetMs]) — so the deck's own line follows the hand, and the player is already near
     * wherever the record is set down.
     *
     * **Straight to the player, and that is not a nicety.** Sent the long way round, through the
     * session, a seek lands a frame or two after it was asked for — and the last one asked for
     * before a finger lifts would land *after* the hand-back had put the player where the record
     * really is, and move it back to where the disc was a moment earlier. False if there is no
     * player here to tell; the room then asks the repository, which will start one.
     */
    fun walk(trackIndex: Int, offsetMs: Long): Boolean = phase == Phase.HELD && link.seek(trackIndex, offsetMs)

    /** The hand has turned the disc to [seconds] of music from where it landed, as of [reportNanos]. */
    fun hand(seconds: Double, reportNanos: Long) {
        handSeconds = seconds
        if (phase != Phase.HELD) return
        val engine = engine ?: return
        engine.hand.move(seconds, reportNanos, System.nanoTime())

        // Spun to find a place, a record soon leaves the stretch that was decoded round where
        // it was taken. As it nears either end of it — with more of the track beyond — a new
        // one is decoded round where it has got to.
        val window = engine.window ?: return
        val uri = scratching ?: return
        if (widening?.isActive == true) return
        val at = engine.positionSeconds
        // (Already centred about here: a stretch too short to have a middle and two ends, as a
        // high-resolution file's is, would otherwise be decoded over and over.)
        if (abs(at - (window.startSeconds + window.endSeconds) / 2) < REWIDEN_WITHIN_SECONDS / 4) return
        val nearEnd = at > window.endSeconds - REWIDEN_WITHIN_SECONDS && window.endSeconds < trackSeconds - 0.5
        val nearStart = at < window.startSeconds + REWIDEN_WITHIN_SECONDS && window.startSeconds > 0.5
        if (nearEnd || nearStart) widen(uri, (at * 1000).toLong().coerceAtLeast(0L))
    }

    /**
     * The finger lifts. If the engine has the record and it is still on its own track, it is
     * wound back up and handed to the player in step; otherwise the player is simply put at
     * ([fallbackTrackIndex], [fallbackOffsetMs]) — where the room reckons the disc was turned to.
     *
     * [placed] is the room saying the disc was being spun to find a place, not scratched: the
     * record is then set down exactly there, already at speed, instead of let go to run on.
     */
    fun release(fallbackTrackIndex: Int, fallbackOffsetMs: Long, placed: Boolean = false) {
        when (phase) {
            Phase.IDLE, Phase.RELEASING -> return
            Phase.PRIMING -> {
                // Lifted before the engine ever took the record: the music never stopped. If the
                // disc was turned in that moment, that is still a seek; a tap is nothing at all.
                job?.cancel()
                stopDecoding()
                phase = Phase.IDLE
                Log.i(TAG, "let go before the record was taken, ${"%.3f".format(handSeconds)}s turned")
                if (abs(handSeconds) > TAP_SECONDS) {
                    scope.launch { playerSeek(fallbackTrackIndex, fallbackOffsetMs) }
                }
                return
            }
            Phase.HELD -> Unit
        }
        // Whatever the grab was still doing — crossing the player out, waiting on a slow decode
        // — stops here: a sound that arrived after the finger had gone would be a record held
        // by nobody.
        job?.cancel()
        job = null
        val engine = engine
        if (engine == null || !running) {
            // The silent hold: put the player where the disc was turned to, and carry on.
            stopDecoding()
            phase = Phase.IDLE
            Log.i(TAG, "let go of a silent hold: track $fallbackTrackIndex, ${fallbackOffsetMs}ms")
            scope.launch {
                playerSeek(fallbackTrackIndex, fallbackOffsetMs)
                if (resume) playerPlay(true)
            }
            return
        }
        phase = Phase.RELEASING
        job = scope.launch {
            try {
                if (!resume) handBackPaused(engine, fallbackTrackIndex, fallbackOffsetMs, placed)
                else handBackPlaying(engine, fallbackTrackIndex, fallbackOffsetMs, placed)
            } finally {
                // However this ended — finished, failed, cancelled by a new grab that wants the
                // engine kept — the player is never left muted by a scratch that is over.
                if (phase == Phase.RELEASING) finish()
            }
        }
    }

    /** Stop now: a case closing under the finger, the room going away. The music is put back as it was. */
    fun cancel() {
        val was = phase
        job?.cancel()
        job = null
        val restart = resume && (was == Phase.HELD || was == Phase.RELEASING) && !link.isPlaying()
        if (was != Phase.IDLE) Log.i(TAG, "cancelled while $was" + if (restart) ", playing again" else "")
        finish()
        if (restart) scope.launch { playerPlay(true) }
    }

    // ---- Taking the record ---------------------------------------------------------------------

    /**
     * The engine takes the record. [live] is whether the player still has it as the finger found
     * it — false when the first stretch took so long that the hold has already begun in silence.
     */
    private suspend fun takeOver(first: ScratchWindow, grabPositionMs: Long, live: Boolean, primedMs: Long) {
        val engine = openOutput() ?: run {
            if (live) holdSilently()
            return
        }
        val now = System.nanoTime()
        val sounding = live && resume
        engine.window = first
        engine.master = link.levelling()
        val at: Double
        if (sounding) {
            // Where the player truly is — and by the time the first of this is heard, a little
            // on. It starts at the player's own speed, as the same sound, and the hand that is
            // already on it winds it down from there.
            at = (link.positionMs() ?: grabPositionMs) / 1000.0 + startLatencySeconds() * nominal
            engine.platter.place(at, nominal, playing = true, nominal = nominal)
            engine.grab(handSeconds, now)
            engine.fade(1f, CROSS_IN_SECONDS)
        } else {
            // Nothing is sounding: the music was stopped, or the hold began in silence. The
            // record starts still — where the player is, if it has not been moved since the
            // finger landed; where the hand has turned the disc to, if it has.
            at = if (live) (link.positionMs() ?: grabPositionMs) / 1000.0
            else grabPositionMs / 1000.0 + handSeconds
            engine.platter.place(at, 0.0, playing = false, nominal = nominal)
            engine.grab(handSeconds, now)
            engine.open()
        }
        startWriter(engine)
        phase = Phase.HELD
        Log.i(
            TAG,
            "took the record at ${"%.3f".format(at)}s after ${primedMs}ms" +
                (if (sounding) ", playing" else if (live) ", stopped" else ", late") +
                ", ${engine.outputRate}Hz in bursts of ${session?.burst}"
        )
        if (sounding) {
            fadeLink(1f, 0f, CROSS_IN_MS)
            playerPlay(false)
        } else {
            link.setLevel(0f)
        }
    }

    // ---- Giving it back ------------------------------------------------------------------------

    private suspend fun handBackPaused(engine: ScratchEngine, fallbackIndex: Int, fallbackOffsetMs: Long, travelling: Boolean) {
        val placed = Platter.setDown(engine.rate.toDouble(), travelling, nominal)
        if (placed) engine.place(playing = false) else engine.release(playing = false)
        var waited = 0L
        while (!engine.atRest && waited < COAST_LONGEST_MS) {
            delay(POLL_MS)
            waited += POLL_MS
        }
        val at = engine.positionSeconds
        if (at in 0.0..trackSeconds) playerSeek(trackIndex, (at * 1000).toLong())
        else playerSeek(fallbackIndex, fallbackOffsetMs)
        Log.i(TAG, "left stopped at ${"%.3f".format(at)}s after coasting ${waited}ms")
    }

    /**
     * The record goes back to the player without the music stopping.
     *
     * **In three steps, and the order is the point.** A player told to seek and play starts when
     * it starts — a decoder to flush, a file to read, a buffer to fill — and how long that takes
     * varies by more than an ear forgives. A player that is already *parked* at a place, stopped
     * and ready, starts after very nearly the same delay every time. So:
     *
     * 1. the record winds up, and goes on playing — it is the music now, and nobody can tell;
     * 2. meanwhile the player is parked, silent, at a place the record will pass a third of a
     *    second on, and is started just before the record gets there — by exactly as much as
     *    this phone's player has been found to need ([playExtra]);
     * 3. as it comes in, the two are crossed.
     *
     * **Nothing is steered by the player's clock while this happens**, which was the first design
     * and did not survive being watched: for its first second after starting, a player's idea of
     * where it is is a blend of two estimates sliding into each other, and a record leant toward
     * that is a record whose pitch wanders after a moving target. The clock is read once, a
     * second and a half later when it has settled, to learn how long the start really took —
     * and that is what makes the next one tighter ([learnFrom]).
     */
    private suspend fun handBackPlaying(engine: ScratchEngine, fallbackIndex: Int, fallbackOffsetMs: Long, travelling: Boolean) {
        // Set down, or thrown? Decided by what the record was doing as the finger left it.
        val placed = Platter.setDown(engine.rate.toDouble(), travelling, nominal)
        // A record spun somewhere faster than that place could be decoded has no sound there
        // yet. Two things can put some there, and both are sent for at once: the stretch round
        // it, a few hundredths of a second from arriving ([arm] follows the hand) — with which
        // the music comes back out of the record itself — and the player, sent to the place
        // and started the moment it is ready. Whichever is first is the one that is heard.
        var landing = 0L
        if (!holds(engine.window, engine.positionSeconds)) {
            val (landIndex, landMs) = landingPlace(engine, fallbackIndex, fallbackOffsetMs)
            playerPlay(false)
            playerSeek(landIndex, landMs)
            while (!holds(engine.window, engine.positionSeconds) && landing < LAND_WAIT_MS) {
                val parked = landing >= PLAYER_SETTLE_MS && link.isReady() && link.trackIndex() == landIndex &&
                    abs((link.positionMs() ?: -1_000_000L) - landMs) < NEAR_ENOUGH_MS
                if (parked) {
                    engine.fade(0f, 0.02f)
                    playerPlay(true)
                    fadeLink(0f, 1f, 40)
                    Log.i(TAG, "set down where there was no audio yet: the player had it after ${landing}ms, at track $landIndex, ${landMs}ms")
                    return
                }
                delay(TICK_MS)
                landing += TICK_MS
            }
        }
        // Set down, the record is where it was put and already at speed; thrown, it will settle
        // a little further on (or back), a moment from now.
        val settle = if (placed) Platter.Settled(0.0, 0.0)
        else Platter.settleFrom(Platter.letGoAt(engine.rate.toDouble()), nominal)
        val lockAt = engine.positionSeconds + settle.travelled
        val window = engine.window
        val onTheRecord = window != null && lockAt > 0.05 &&
            lockAt + (PARK_AHEAD_SECONDS + HAND_BACK_MARGIN_SECONDS) * nominal < trackSeconds &&
            // ...and on what has been decoded of it: spun a long way fast, a record can be let
            // go beyond the stretch in hand, and a wind-up there is a wind-up of nothing.
            engine.positionSeconds > window.startSeconds && lockAt + PARK_AHEAD_SECONDS * nominal < window.endSeconds
        if (!onTheRecord) {
            // Turned off the end of this track, or too near it: there is nothing to wind up
            // with. The player starts where the disc was left, under a quick fade.
            engine.fade(0f, 0.03f)
            val (toIndex, toMs) = landingPlace(engine, fallbackIndex, fallbackOffsetMs)
            // The player may already be standing at the place — it was sent there above, or
            // walked there behind the disc. Then it is not sent again: a seek is a decoder
            // emptied and a buffer refilled, and that is the wait this would otherwise be.
            val there = link.isReady() && link.trackIndex() == toIndex &&
                abs((link.positionMs() ?: -1_000_000L) - toMs) < NEAR_ENOUGH_MS
            if (!there) playerSeek(toIndex, toMs)
            playerPlay(true)
            fadeLink(0f, 1f, 60)
            Log.i(TAG, "let go off the record (${"%.3f".format(lockAt)}s of ${"%.1f".format(trackSeconds)}): started at track $toIndex, ${toMs}ms")
            return
        }

        if (placed) engine.place(playing = true) else engine.release(playing = true)
        link.setLevel(0f)
        // Stopped, whatever it was doing: a finger that came and went inside the take-over's own
        // crossfade leaves a player that was never told to stop.
        playerPlay(false)

        // 2. Park it where the record will be, and start it as the record arrives.
        var meetAt = lockAt + PARK_AHEAD_SECONDS * nominal
        var startedAt = 0L
        var outputBehind = 0.0
        var waited = 0L
        var parks = 0
        park@ while (parks < PARKS_MOST && meetAt < trackSeconds - HAND_BACK_MARGIN_SECONDS) {
            parks++
            playerSeek(trackIndex, (meetAt * 1000).toLong())
            while (waited < PARK_LONGEST_MS) {
                // How long until the record is *written* as far as the meeting place. What is
                // written is heard later, by however far behind the output is — and so is what
                // the player plays, by that and its own little more. The first of those cancels.
                val togo = (meetAt - engine.positionSeconds) / nominal
                if (engine.atSpeed && togo <= playExtra) {
                    // Parked, and the player agrees that it is — its own seek has landed.
                    val there = link.isReady() && abs((link.positionMs() ?: -1L) - meetAt * 1000) < 40
                    if (there && togo > playExtra - LATE_SECONDS) {
                        outputBehind = latencySeconds()
                        outputBehindLast = outputBehind
                        playerPlay(true)
                        startedAt = System.nanoTime()
                        break@park
                    }
                    // Not ready in time: park it again, further on. The record plays on meanwhile.
                    meetAt = engine.positionSeconds + (PARK_AHEAD_SECONDS + 0.25 * parks) * nominal
                    continue@park
                }
                delay(TICK_MS)
                waited += TICK_MS
            }
            break
        }
        val met = startedAt != 0L
        if (met) {
            // 3. It is heard about now: the output's delay and its own.
            delay(((outputBehind + playExtra) * 1000).toLong().coerceIn(0L, 500L) + CROSS_AFTER_MS)
        } else {
            // It could not be met in step: start it where the record is, and cross them as they are.
            playerSeek(trackIndex, (engine.positionSeconds * 1000).toLong())
            playerPlay(true)
        }
        var running = 0L
        while (!link.isPlaying() && running < START_LONGEST_MS) {
            delay(TICK_MS)
            running += TICK_MS
        }
        Log.i(
            TAG,
            "handed back: wind-up ${(settle.seconds * 1000).toInt()}ms, " +
                (if (placed) "set down, " else "") + (if (landing > 0) "waited ${landing}ms for audio, " else "") +
                (if (met) "met after ${waited}ms on park $parks" else "not met ($parks parks)") +
                ", output ${(outputBehind * 1000).toInt()}ms behind, player ${(playExtra * 1000).toInt()}ms more"
        )
        engine.fade(0f, CROSS_OUT_SECONDS)
        fadeLink(0f, 1f, CROSS_OUT_MS)
        delay(30)
        if (met) learnFrom(startedAt, meetAt, outputBehind)
    }

    /**
     * Where the player should be put for a record the engine cannot give back itself: where the
     * record is, if that is still on its own track — the engine has followed the hand to the
     * last hundredth of a second, where the room's own figure is as old as the last touch event,
     * and at a spin's gearing one event is most of a second of music. Off the end of the track,
     * only the room knows which track that is.
     */
    private fun landingPlace(engine: ScratchEngine, fallbackIndex: Int, fallbackOffsetMs: Long): Pair<Int, Long> {
        val at = engine.positionSeconds
        return if (at >= 0.0 && at < trackSeconds - 0.05) trackIndex to (at * 1000).toLong()
        else fallbackIndex to fallbackOffsetMs
    }

    /**
     * Once the player's clock has settled, ask it how long its start really took: it was told to
     * play at [startedAt] from [parkedAt], so wherever it says it is now, it has been heard for
     * that long and no longer. The difference from what was allowed goes, in part, into the next
     * guess. Skipped if anything has touched the record or the player since.
     */
    private fun learnFrom(startedAt: Long, parkedAt: Double, outputBehind: Double) {
        val then = taken
        val speed = nominal
        scope.launch {
            delay(CLOCK_SETTLE_MS)
            if (taken != then || !link.isPlaying()) return@launch
            val now = System.nanoTime()
            val played = ((link.positionMs() ?: return@launch) / 1000.0 - parkedAt) / speed
            val since = (now - startedAt) / 1e9
            // Not this start any more: a seek, another track, a pause and a play.
            if (played <= 0.0 || played > since) return@launch
            val took = since - played
            if (took > START_MOST_SECONDS) return@launch
            // The middle of the last few, not a step toward the newest: on a phone whose clocks
            // are good these agree to a few thousandths of a second and the first is believed at
            // once; on one whose clocks are not, a wild one moves nothing.
            val was = playExtra
            measured.addLast(took - outputBehind)
            while (measured.size > MEASURED_KEPT) measured.removeFirst()
            playExtra = measured.sorted()[measured.size / 2]
            Log.i(
                TAG,
                "the player took ${(took * 1000).toInt()}ms to start where ${((outputBehind + was) * 1000).toInt()}ms " +
                    "was allowed; ${(playExtra * 1000).toInt()}ms more than the output from now on"
            )
        }
    }

    /** The player's level from [from] to [to] in steps a mixer smooths between. */
    private suspend fun fadeLink(from: Float, to: Float, millis: Long) {
        val steps = max(1, (millis / FADE_STEP_MS).toInt())
        for (i in 1..steps) {
            link.setLevel(from + (to - from) * i / steps)
            delay(FADE_STEP_MS)
        }
        link.setLevel(to)
    }

    /** Over: the output stops, and the player has its own level back whatever state this was in. */
    private fun finish() {
        phase = Phase.IDLE
        link.setLevel(1f)
        // What this scratch decoded is the best thing to be ready with for the next: scratching
        // comes in bursts, and the second touch should not wait on a decoder any more than the
        // first did. Only while the disc can still be touched; it is let go with that.
        val had = session?.engine?.window
        val uri = link.uri()
        val at = link.positionMs()
        if (keeping != null && had != null && uri != null && at != null && uri == scratching &&
            covers(had, uri, uri, at / 1000.0, READY_TOP_UP_SECONDS)
        ) {
            ready = had
            readyUri = uri
        }
        stopDecoding()
        closeOutput()
    }

    // ---- Output --------------------------------------------------------------------------------

    /**
     * Opens the output at the phone's own rate, on its low-latency path.
     *
     * A scratch is played, not listened to: the sound has to follow the hand within a few
     * hundredths of a second or it is a recording of a scratch arriving late. The old output took
     * the platform's default buffer twice over and was a tenth of a second behind the finger.
     * Asking for the mixer's own rate and burst is what lets Android give this the fast path —
     * the engine reads the record at any rate, so the file's own costs nothing to give up.
     */
    private fun openOutput(): ScratchEngine? {
        closeOutput()
        val manager = context.getSystemService(AudioManager::class.java)
        val rate = manager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?.takeIf { it in 22_050..192_000 } ?: 48_000
        val burst = manager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
            ?.coerceIn(64, 1024) ?: 256
        val least = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(max(least, max(burst * 8, rate / 20) * BYTES_PER_FRAME))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } ?: return null

        // As little held in hand as the phone will take without running dry: a sixtieth of a
        // second or so to begin with — this is a thread in a garbage-collected runtime, and
        // less than that is a crackle waiting for a pause — and more, kept from one scratch to
        // the next, on a phone where that turned out not to be enough.
        runCatching { track.bufferSizeInFrames = max(heldFrames, max(burst * 4, rate / 60)) }
        val made = Session(ScratchEngine(rate), track, burst)
        session = made
        return made.engine
    }

    private fun startWriter(engine: ScratchEngine) {
        val session = session?.takeIf { it.engine === engine } ?: return
        val track = session.track
        runCatching { track.play() }
        session.writer = Thread({
            // **Nothing here may ever kill the app.** An uncaught exception on any thread takes
            // the whole process with it, and this is audio arithmetic on a background thread
            // while a finger is moving: the worst it may ever do is stop making noise.
            runCatching {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val frames = session.burst
                val buffer = ShortArray(frames * 2)
                var dry = runCatching { track.underrunCount }.getOrDefault(0)
                var blocks = 0
                while (session.alive) {
                    engine.render(buffer, frames, System.nanoTime())
                    val wrote = track.write(buffer, 0, buffer.size)
                    if (wrote < 0) break
                    session.written += wrote / 2
                    // Ran dry: a click. Hold more in hand from here on, and next time too.
                    if (++blocks % 16 == 0) {
                        val now = runCatching { track.underrunCount }.getOrDefault(dry)
                        if (now > dry) {
                            dry = now
                            runCatching {
                                track.bufferSizeInFrames =
                                    min(track.bufferCapacityInFrames, track.bufferSizeInFrames + frames * 2)
                                heldFrames = track.bufferSizeInFrames
                                Log.i(TAG, "the output ran dry; holding $heldFrames frames from now on")
                            }
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "scratch output stopped: $it") }
        }, "jawon-scratch").apply { start() }
    }

    /**
     * How long after its first sample is written an output just opened will be heard.
     *
     * It cannot be measured before it starts, so it is what the last one measured once it was
     * running, less what that one was holding in its buffer — a new one starts with nothing
     * queued. That matters most where it is largest: through a Bluetooth headphone the way to the
     * ear is a fifth of a second, and a record taken that much behind where the player was is a
     * record that repeats itself as it is taken. Before anything has been measured, a guess.
     */
    private fun startLatencySeconds(): Double {
        val track = session?.track
        if (outputBehindLast.isNaN() || track == null) return START_LATENCY_SECONDS
        val held = runCatching { track.bufferSizeInFrames.toDouble() / track.sampleRate }.getOrDefault(0.0)
        return (outputBehindLast - held).coerceIn(0.004, 0.4)
    }

    /** What [latencySeconds] last said of a running output; NaN before there has been one. */
    private var outputBehindLast = Double.NaN

    /** How much the output has had to hold in hand on this phone not to run dry; 0 until it has. */
    @Volatile
    private var heldFrames = 0

    /**
     * How far behind what is being written the ear is, in seconds: what is still in the buffer,
     * and the way from there to the speaker — read from the output's own clock when it has one.
     */
    private fun latencySeconds(): Double {
        val session = session ?: return DEFAULT_LATENCY_SECONDS
        val track = session.track
        val written = session.written
        val rate = track.sampleRate.toDouble()
        val stamp = AudioTimestamp()
        val heard = runCatching {
            if (track.getTimestamp(stamp) && stamp.framePosition > 0) {
                stamp.framePosition + (System.nanoTime() - stamp.nanoTime) / 1e9 * rate
            } else {
                null
            }
        }.getOrNull()
        val behind = if (heard != null) (written - heard) / rate
        else (written - runCatching { track.playbackHeadPosition.toLong() }.getOrDefault(written)) / rate + MIXER_SECONDS
        return if (written == 0L) START_LATENCY_SECONDS else behind.coerceIn(0.0, 0.5)
    }

    /** Nobody is waiting for audio any more. */
    private fun stopDecoding() {
        decoding?.cancel()
        decoding = null
        widening?.cancel()
        widening = null
        grown = null
    }

    private fun closeOutput() {
        val dying = session ?: return
        session = null
        dying.alive = false
        // Torn down off the calling thread: this is called from a touch handler, and joining a
        // thread and releasing a track are milliseconds it should not spend there.
        Thread({
            runCatching { dying.writer?.join(300) }
            runCatching {
                dying.track.pause()
                dying.track.flush()
                dying.track.release()
            }
        }, "jawon-scratch-release").start()
    }

    // ---- Decoding ------------------------------------------------------------------------------

    /**
     * Decode from [beforeMs] before [positionMs] to [afterMs] after it into interleaved 16-bit PCM.
     *
     * With [early] given, what has been decoded is handed to it once — as a stretch of its own,
     * to play from at once — when it reaches [earlyAfterMs] past [positionMs]; the decode carries
     * on and returns the whole of it, which begins at the same place.
     *
     * Deliberately a plain `MediaExtractor` + `MediaCodec` loop rather than anything shared
     * with the player: this needs raw samples in an array, which is exactly what a player is
     * built to avoid ever handing out.
     */
    private fun decode(
        uri: Uri,
        positionMs: Long,
        beforeMs: Long,
        afterMs: Long,
        earlyAfterMs: Long = 0L,
        early: ((ScratchWindow) -> Unit)? = null
    ): ScratchWindow? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)

            // A wide stretch of a high-resolution file is several times the memory of an ordinary
            // one. The memory is what is kept the same; the stretch gets shorter.
            var scale = if (beforeMs + afterMs > 10_000L) min(1.0, 48_000.0 / rate) else 1.0
            // And never more than a third of what memory there is left: this is audio held for a
            // finger, beside a player with tens of megabytes of its own, and an app that runs out
            // does not get to say which thread found out.
            val wanted = (beforeMs + afterMs) * scale / 1000.0 * rate * ch * 2
            val budget = headroom() / 3.0
            if (wanted > budget) {
                scale *= budget / wanted
                if ((beforeMs + afterMs) * scale < LEAST_WORTH_DECODING_MS) return null
            }
            val length = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000L else Long.MAX_VALUE
            val startMs = (positionMs - (beforeMs * scale).toLong()).coerceAtLeast(0L)
            val endMs = min(positionMs + (afterMs * scale).toLong(), length)
            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // Where the decoder really starts: the sync point at or before what was asked for.
            val firstUs = extractor.sampleTime.coerceAtLeast(0L)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            // What was asked for, and half a second more for a decoder that runs on past it.
            val out = ShortArray((((endMs - firstUs / 1000).coerceAtLeast(0L) / 1000.0 + 0.5) * rate).toInt() * ch)
            var written = 0
            var firstOutUs = -1L
            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false
            var handedOver = false

            while (!sawOutputEnd && written < out.size) {
                // Nobody is waiting for this any more: the finger lifted, or the case closed.
                if (Thread.currentThread().isInterrupted) return null

                // **Everything it will take, then everything it has.** One buffer in and then a
                // wait for one out — the usual loop — hands a decoder its work a frame at a
                // time, and the first of the sound arrives after a dozen of those waits. How soon
                // a touch is answered is exactly that, so nothing here waits while there is
                // anything to do.
                var busy = false
                while (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(0L)
                    if (inIndex < 0) break
                    busy = true
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0 || extractor.sampleTime > endMs * 1000L) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEnd = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                while (!sawOutputEnd && written < out.size) {
                    val outIndex = codec.dequeueOutputBuffer(info, if (busy) 0L else TIMEOUT_US)
                    if (outIndex < 0) break
                    busy = true
                    val outBuf: ByteBuffer? = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        if (firstOutUs < 0) firstOutUs = info.presentationTimeUs
                        val shorts = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val count = min(info.size / 2, out.size - written)
                        shorts.get(out, written, count)
                        written += count
                        if (early != null && !handedOver) {
                            val wanted = (((positionMs + earlyAfterMs) * 1000L - firstOutUs) / 1e6 * rate).toLong() * ch
                            if (written >= wanted) {
                                handedOver = true
                                early(ScratchWindow(out.copyOf(written - written % ch), rate, ch, firstOutUs / 1e6))
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                }
            }

            if (written == 0) return null
            // **Placed by the decoder's own stamp, not by where the seek was asked to land.** A
            // seek lands on the sync point before the place asked for, which in some files is a
            // good fraction of a second earlier; calling that "the place asked for" put every
            // sample that far out, and a record taken from the player has to be the same record.
            val startSeconds = (if (firstOutUs >= 0) firstOutUs else firstUs) / 1e6
            return ScratchWindow(out, rate, ch, startSeconds, frames = written / ch)
        } catch (t: Throwable) {
            // A scratch that cannot decode is a silent scratch, which is what it was before
            // this existed. Never a crash: this runs on a user's own files, of any format,
            // some of which will be broken.
            Log.w(TAG, "scratch decode failed: ${t.message}")
            return null
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val TAG = "Jawon.Scratch"

        /**
         * The first stretch: enough behind the playhead to pull back into and enough ahead for a
         * first stroke — and of that, how little past the playhead is enough to *start* on
         * ([EARLY_AFTER_MS]). For as long as the first of it takes to decode, the finger is on a
         * record that has not yet answered, so the first of it is small.
         */
        const val FIRST_BEFORE_MS = 450L
        const val EARLY_AFTER_MS = 550L
        const val FIRST_AFTER_MS = 4_000L

        /**
         * The stretch that replaces it: twenty-five seconds either side, a dozen turns of the
         * disc each way. Around 9MB of PCM for a stereo 44.1kHz track, held only while the
         * record is out of the player's hands.
         */
        const val WIDE_MS = 25_000L

        /** How long to wait on a decoder with nothing to give and no room to take. */
        const val TIMEOUT_US = 2_000L

        /** With less memory left than this, nothing is kept decoded on the chance of a touch. */
        const val KEEP_HEADROOM_BYTES = 24L * 1024 * 1024

        /** A stretch squeezed shorter than this by what memory is left is not worth having. */
        const val LEAST_WORTH_DECODING_MS = 1_500.0

        /** What following the hand keeps decoded round the record, and how often it looks. */
        const val FOLLOW_SECONDS = 10.0
        const val FOLLOW_EVERY_MS = 30L

        /** A stretch has sound for a record with this much of it behind and ahead. */
        const val HOLD_BEHIND_SECONDS = 0.4
        const val HOLD_AHEAD_SECONDS = 1.0

        /** How long a record set down where there is no sound yet waits for some before the player is sent for. */
        const val LAND_WAIT_MS = 400L

        /** A player standing this close to where it is wanted is not sent there again. */
        const val NEAR_ENOUGH_MS = 80L

        /** A player just sent somewhere says it is ready for a moment before it knows it is not. */
        const val PLAYER_SETTLE_MS = 16L

        /** This close to either end of what is decoded, a record that is travelling gets a new stretch. */
        const val REWIDEN_WITHIN_SECONDS = 8.0
        const val BYTES_PER_FRAME = 4

        /** The disc turned less than this before the engine was ready is a tap, not a turn. */
        const val TAP_SECONDS = 0.04

        const val CROSS_IN_SECONDS = 0.02f
        const val CROSS_IN_MS = 24L
        const val CROSS_OUT_SECONDS = 0.06f
        const val CROSS_OUT_MS = 60L
        const val FADE_STEP_MS = 6L
        const val POLL_MS = 10L

        /** The hand-back is timed more finely than the rest: a few thousandths of a second decide it. */
        const val TICK_MS = 4L

        /**
         * How far past where it comes up to speed the record meets the player: long enough for a
         * player to seek there and be ready, on a local file, with room to spare.
         */
        const val PARK_AHEAD_SECONDS = 0.32

        /** How many times the player is parked further on before it is simply started where the record is. */
        const val PARKS_MOST = 3
        const val PARK_LONGEST_MS = 2_500L

        /** Starting the player later than this past its moment is not starting it in step. */
        const val LATE_SECONDS = 0.03

        /**
         * A parked player told to play is heard about this much later than the scratch's own
         * output would be — the first guess, before this phone has been measured ([playExtra]).
         */
        const val PLAY_EXTRA_SECONDS = 0.035
        const val PLAY_EXTRA_LEAST = -0.05
        const val PLAY_EXTRA_MOST = 0.25
        const val KEY_PLAY_EXTRA = "scratch_player_extra"

        /** How many measurements of the player's start the guess is the middle of. */
        const val MEASURED_KEPT = 5

        /**
         * Kept ready: how far behind and ahead of the playhead it is decoded to; how little ahead
         * it is let fall to before it is topped up, and how little is still enough to take a
         * record with — see [covers] for why those are two numbers — and how often it is looked
         * at. Decoding goes ahead in steps of two and a half seconds of audio, every two and a half.
         */
        const val READY_BEHIND_SECONDS = 1.0
        const val READY_AHEAD_SECONDS = 5.5
        const val READY_TOP_UP_SECONDS = 3.0
        const val READY_BEHIND_LEAST_SECONDS = 0.35
        const val READY_AHEAD_LEAST_SECONDS = 1.2
        const val READY_EVERY_MS = 250L

        /** The room says the disc can be touched every frame; this long without, and it cannot. */
        const val ARM_LAPSES_MS = 1_000L

        /** A start that seems to have taken longer than this was not a start; something else happened. */
        const val START_MOST_SECONDS = 0.7

        /** How long a player told to play is waited on before going ahead regardless. */
        const val START_LONGEST_MS = 800L

        /** The player is given this long to be properly under way before the two are crossed. */
        const val CROSS_AFTER_MS = 30L

        /**
         * A player's idea of where it is cannot be trusted until this long after it starts: for
         * about a second it is two estimates blending into each other.
         */
        const val CLOCK_SETTLE_MS = 1_600L

        const val COAST_LONGEST_MS = 2_000L

        /** A record let go this close to its track's end is not wound up: there is not enough left. */
        const val HAND_BACK_MARGIN_SECONDS = 1.0

        const val DEFAULT_LATENCY_SECONDS = 0.04
        const val MIXER_SECONDS = 0.015

        /**
         * How long after its first sample is written an output just started is heard: the mixer
         * and the way to the speaker, with nothing yet queued in front. A guess, and the only one
         * here — everything after the start is measured from the output's own clock.
         */
        const val START_LATENCY_SECONDS = 0.015

        /**
         * How long the music may go on playing under a finger while the first stretch decodes.
         * A local file takes a fraction of this; anything that takes longer is not going to feel
         * like a record under a hand whatever is done, so the hold begins without it.
         */
        const val PRIME_LONGEST_MS = 220L
    }
}
