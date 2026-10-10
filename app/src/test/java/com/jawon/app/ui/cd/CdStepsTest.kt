package com.jawon.app.ui.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Importing a CD as five steps: where each thing the drive does sits on the way. */
class CdStepsTest {

    @Test
    fun `the journey runs drive, disc, folder, copy, shelf`() {
        assertEquals(
            listOf(CdStep.DRIVE, CdStep.DISC, CdStep.FOLDER, CdStep.COPY, CdStep.SHELF),
            CdStep.entries.toList()
        )
    }

    @Test
    fun `each thing the drive does is a step on it`() {
        assertEquals(CdStep.DRIVE, CdSteps.stepOf(CdSteps.Stage.NO_DRIVE))
        // Found but not yet allowed is still getting the drive connected.
        assertEquals(CdStep.DRIVE, CdSteps.stepOf(CdSteps.Stage.NEEDS_PERMISSION))
        assertEquals(CdStep.DISC, CdSteps.stepOf(CdSteps.Stage.READING))
        assertEquals(CdStep.DISC, CdSteps.stepOf(CdSteps.Stage.NO_DISC))
        assertEquals(CdStep.FOLDER, CdSteps.stepOf(CdSteps.Stage.READY))
        assertEquals(CdStep.COPY, CdSteps.stepOf(CdSteps.Stage.RIPPING))
        assertEquals(CdStep.SHELF, CdSteps.stepOf(CdSteps.Stage.DONE))
    }

    @Test
    fun `a failure stays at the step it happened in - try again means that step again`() {
        for (step in CdStep.entries) {
            assertEquals(step, CdSteps.stepOf(CdSteps.Stage.FAILED, before = step))
        }
    }

    @Test
    fun `the steps behind are done, this one is now, the rest are to do`() {
        val now = CdStep.FOLDER
        assertEquals(CdSteps.Mark.DONE, CdSteps.mark(CdStep.DRIVE, now))
        assertEquals(CdSteps.Mark.DONE, CdSteps.mark(CdStep.DISC, now))
        assertEquals(CdSteps.Mark.NOW, CdSteps.mark(CdStep.FOLDER, now))
        assertEquals(CdSteps.Mark.TO_DO, CdSteps.mark(CdStep.COPY, now))
        assertEquals(CdSteps.Mark.TO_DO, CdSteps.mark(CdStep.SHELF, now))
        // Exactly one step is ever "now".
        for (at in CdStep.entries) {
            assertEquals(1, CdStep.entries.count { CdSteps.mark(it, at) == CdSteps.Mark.NOW })
        }
    }

    @Test
    fun `the drawing's cable is lit only while a drive is answering`() {
        assertFalse(CdSteps.connected(CdSteps.Stage.NO_DRIVE))
        assertFalse(CdSteps.connected(CdSteps.Stage.FAILED))
        for (stage in CdSteps.Stage.entries - setOf(CdSteps.Stage.NO_DRIVE, CdSteps.Stage.FAILED)) {
            assertTrue("$stage", CdSteps.connected(stage))
        }
    }

    @Test
    fun `an empty tray is drawn empty, and a disc only turns while it is read or copied`() {
        assertFalse(CdSteps.hasDisc(CdSteps.Stage.NO_DISC))
        assertFalse(CdSteps.hasDisc(CdSteps.Stage.NEEDS_PERMISSION))
        assertTrue(CdSteps.hasDisc(CdSteps.Stage.READY))
        // Still at every step that is waiting on a person.
        assertEquals(
            setOf(CdSteps.Stage.READING, CdSteps.Stage.RIPPING),
            CdSteps.Stage.entries.filter(CdSteps::spinning).toSet()
        )
        // Nothing turns that is not there.
        for (stage in CdSteps.Stage.entries) {
            if (CdSteps.spinning(stage)) assertTrue(CdSteps.hasDisc(stage))
        }
    }
}
