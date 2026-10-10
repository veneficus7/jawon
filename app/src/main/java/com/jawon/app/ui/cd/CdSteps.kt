package com.jawon.app.ui.cd

/**
 * Importing a CD as the five things someone actually does (0.7.6).
 *
 * The screen used to say only where it had got to — "NO DRIVE", "READING" — which is true and
 * tells a person holding a drive and a disc nothing about what comes next or how much is left.
 * So it is a journey now, shown whole: plug the drive in, put a disc in, choose where the music
 * goes, copy it, and it is on the shelf.
 *
 * Pure: the mapping from what the drive is doing to where on the journey that is.
 */
enum class CdStep(val title: String) {
    DRIVE("Plug in the drive"),
    DISC("Put a disc in"),
    FOLDER("Choose where it goes"),
    COPY("Copy the disc"),
    SHELF("On the shelf")
}

object CdSteps {

    /** What the drive is doing, without the Android objects the screen's own state carries. */
    enum class Stage { NO_DRIVE, NEEDS_PERMISSION, READING, NO_DISC, READY, RIPPING, DONE, FAILED }

    /**
     * The step [stage] belongs to. A failure is not a step of its own: it is shown at the step it
     * happened in ([before]), because "try again" means that step again.
     */
    fun stepOf(stage: Stage, before: CdStep = CdStep.DRIVE): CdStep = when (stage) {
        // Found, but Android has not been asked yet: still getting the drive connected.
        Stage.NO_DRIVE, Stage.NEEDS_PERMISSION -> CdStep.DRIVE
        Stage.READING, Stage.NO_DISC -> CdStep.DISC
        Stage.READY -> CdStep.FOLDER
        Stage.RIPPING -> CdStep.COPY
        Stage.DONE -> CdStep.SHELF
        Stage.FAILED -> before
    }

    enum class Mark { DONE, NOW, TO_DO }

    fun mark(step: CdStep, now: CdStep): Mark = when {
        step.ordinal < now.ordinal -> Mark.DONE
        step == now -> Mark.NOW
        else -> Mark.TO_DO
    }

    /** Whether the drawing's cable is live: a drive is there and answering. */
    fun connected(stage: Stage): Boolean = stage != Stage.NO_DRIVE && stage != Stage.FAILED

    /** Whether there is a disc to draw in the drive. */
    fun hasDisc(stage: Stage): Boolean =
        stage == Stage.READING || stage == Stage.READY || stage == Stage.RIPPING || stage == Stage.DONE

    /** Whether the disc is turning: being read, or being copied. */
    fun spinning(stage: Stage): Boolean = stage == Stage.READING || stage == Stage.RIPPING
}
