package com.jawon.app.playback

import androidx.media3.common.Player

/**
 * The one thing allowed to write a player's volume.
 *
 * **Two features want that field and neither can have it.** The crossfade drives it as an
 * envelope, from silence up to full across a track boundary, and ReplayGain wants to hold it
 * at whatever level a record's tags ask for. Written independently they overwrite each other:
 * the crossfade's next step would wipe the levelling, and the levelling would land mid-fade
 * and step on the envelope. This is `ENGINEERING.md` trap 10 in the audio layer rather than the
 * scene — two sources driving one value, with no guaranteed ordering between them.
 *
 * So neither writes it. Each sets its own factor here and this multiplies them, which also
 * makes the relationship correct rather than merely safe: a record levelled to −6dB should
 * still fade from silence to −6dB, not from silence to full.
 *
 * **A note on what cannot be done.** `Player.volume` is a 0..1 attenuation, so a positive
 * ReplayGain — a quiet recording asking to be turned *up* — cannot be honoured; it clamps at
 * full. In practice almost every value in the wild is negative, because the tags exist to pull
 * loud masters down, so this costs very little. Doing better would mean a gain stage in an
 * audio processor, which is a real amount of DSP for the few files that ask for it.
 */
class PlayerVolume(private val player: Player) {

    /** Levelling from the record's own tags. */
    private var gain = 1f

    /** The crossfade envelope, 0..1. */
    private var fade = 1f

    private var scratch = 1f

    /** The levelling alone, as the player can actually apply it — what a scratch has to match. */
    val levelling: Float get() = gain.coerceIn(0f, 1f)

    fun setGain(value: Float) {
        gain = value.coerceAtLeast(0f)
        apply()
    }

    fun setFade(value: Float) {
        fade = value.coerceIn(0f, 1f)
        apply()
    }

    /**
     * How much of the player is heard while a hand has the record, or is giving it back (0.8).
     *
     * A third owner of the same field, for the same reason there was already a second: the
     * scratch crosses the player out as it takes the record and back in as it returns it, and
     * written straight to the player that would fight the crossfade's envelope and ReplayGain's
     * levelling. Multiplied in here with them, none of the three can undo another.
     */
    fun setScratch(value: Float) {
        scratch = value.coerceIn(0f, 1f)
        apply()
    }

    /** Back to a plain, unlevelled, un-faded player. */
    fun reset() {
        gain = 1f
        fade = 1f
        scratch = 1f
        apply()
    }

    /** Drop the fade only, leaving the record's levelling in place. */
    fun clearFade() = setFade(1f)

    private fun apply() {
        // runCatching because a released player throws, and a volume write is never worth
        // taking the service down for.
        runCatching { player.volume = (gain * fade * scratch).coerceIn(0f, 1f) }
    }
}
