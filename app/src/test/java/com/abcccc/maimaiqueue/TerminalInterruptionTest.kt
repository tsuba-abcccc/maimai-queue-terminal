package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What may take the screen away from the operator: an arriving registration, and inactivity. */
class TerminalInterruptionTest {
    private fun handling(
        forceImmediateHome: Boolean = false,
        formOpen: Boolean = false,
        homeScreenShown: Boolean = true,
        terminalIdle: Boolean = false,
        raisesConfirmation: Boolean = false
    ) = newRegistrationArrivalHandling(
        forceImmediateHome = forceImmediateHome,
        formOpen = formOpen,
        homeScreenShown = homeScreenShown,
        terminalIdle = terminalIdle,
        raisesConfirmation = raisesConfirmation
    )

    @Test
    fun anArrivalReturnsHomeOnlyWhenNothingIsOpen() {
        assertEquals(NewRegistrationArrivalHandling.RETURN_HOME, handling(terminalIdle = true))
        assertEquals(
            NewRegistrationArrivalHandling.RETURN_HOME,
            handling(terminalIdle = true, raisesConfirmation = true)
        )
    }

    @Test
    fun aDialogMenuOrAdjustmentOnHomeStaysAndOnlyTheHighlightIsSet() {
        assertEquals(NewRegistrationArrivalHandling.HIGHLIGHT_ONLY, handling())
        // Its question would cover what is open: it waits until that is closed.
        assertEquals(NewRegistrationArrivalHandling.HOLD, handling(raisesConfirmation = true))
    }

    @Test
    fun aFormOrAnotherScreenHoldsTheArrival() {
        assertEquals(NewRegistrationArrivalHandling.HOLD, handling(formOpen = true))
        assertEquals(
            NewRegistrationArrivalHandling.HOLD,
            handling(formOpen = true, homeScreenShown = false)
        )
        assertEquals(NewRegistrationArrivalHandling.HOLD, handling(homeScreenShown = false))
    }

    @Test
    fun thePhoneThatScannedThisTerminalStillReturnsHomeAtOnce() {
        assertEquals(
            NewRegistrationArrivalHandling.RETURN_HOME,
            handling(forceImmediateHome = true, formOpen = true, homeScreenShown = false)
        )
    }

    @Test
    fun whatTheClosingActionProducedIsKeptOverAHeldArrival() {
        val held = HeldNewRegistrationArrivals()
        // Nothing was held: an arrival at an idle home replaces what is shown, as before.
        assertFalse(held.localResultShown("local-1", 4L, null, confirmationOpen = false))

        held.hold(highlightRequestId = "remote-0", feedbackId = 4L, undoId = null)
        assertTrue(held.held)
        // What was already there when the form opened is not a result of closing it.
        assertFalse(held.localResultShown("remote-0", 4L, null, confirmationOpen = false))
        assertFalse(held.localResultShown(null, null, null, confirmationOpen = false))
        // The player's own new registration, a banner such as “本次调整未执行”, 撤销, a question.
        assertTrue(held.localResultShown("local-1", 4L, null, confirmationOpen = false))
        assertTrue(held.localResultShown("remote-0", 5L, null, confirmationOpen = false))
        assertTrue(held.localResultShown("remote-0", 4L, 1L, confirmationOpen = false))
        assertTrue(held.localResultShown("remote-0", 4L, null, confirmationOpen = true))

        // A later hold does not move the mark while arrivals are still waiting.
        held.hold(highlightRequestId = "local-1", feedbackId = 5L, undoId = 1L)
        assertTrue(held.localResultShown("local-1", 4L, null, confirmationOpen = false))

        held.clear()
        assertFalse(held.held)
        assertFalse(held.localResultShown("local-1", 5L, 1L, confirmationOpen = true))
    }

    @Test
    fun formsAndQrCodesGetNinetySecondsEverythingElseThirty() {
        assertEquals(30_000L, inactivityTimeoutFor(fillingForm = false, scanningQrCode = false))
        assertEquals(90_000L, inactivityTimeoutFor(fillingForm = true, scanningQrCode = false))
        assertEquals(90_000L, inactivityTimeoutFor(fillingForm = false, scanningQrCode = true))
        assertEquals(90_000L, inactivityTimeoutFor(fillingForm = true, scanningQrCode = true))
    }

    @Test
    fun theWarningCountsTheLastFiveSecondsOfAForm() {
        assertNull(inactivityWarningSecondsLeft(84_999L, 90_000L, fillingForm = true))
        assertEquals(5, inactivityWarningSecondsLeft(85_000L, 90_000L, fillingForm = true))
        assertEquals(3, inactivityWarningSecondsLeft(87_500L, 90_000L, fillingForm = true))
        assertEquals(1, inactivityWarningSecondsLeft(89_999L, 90_000L, fillingForm = true))
        // A QR code on the home screen has the longer time but no countdown, as before.
        assertNull(inactivityWarningSecondsLeft(89_000L, 90_000L, fillingForm = false))
        assertNull(inactivityWarningSecondsLeft(29_000L, 30_000L, fillingForm = false))
    }

    @Test
    fun releasingAFixedPairSaysWhatEachRegistrationBecomes() {
        // From the position menu: the sentence as it was.
        assertEquals(
            "确认后，“小明”和“小红”的固定组合会解除。两份登记的游玩偏好都会改为允许他人加入，" +
                "系统会按照等待顺序重新组成等待位置。",
            releaseFixedPairConfirmationText("小明", "小红")
        )
        assertEquals(
            "确认后，“小明”和“小红”的固定组合会解除。“小明”的游玩偏好会改为单人游玩，" +
                "“小红”的游玩偏好会改为允许他人加入，系统会按照等待顺序重新组成等待位置。",
            releaseFixedPairConfirmationText("小明", "小红", PlayPreference.SOLO)
        )
        assertEquals(
            "确认后，“小明”和“小红”的固定组合会解除。两份登记的游玩偏好都会改为允许他人加入，" +
                "本轮游玩结束后生效。",
            releaseFixedPairConfirmationText("小明", "小红", inPlayingPosition = true)
        )
        assertEquals(
            "确认后，“小明”和“小红”的固定组合会解除。“小明”的游玩偏好会改为单人游玩，" +
                "“小红”的游玩偏好会改为允许他人加入，本轮游玩结束后生效。",
            releaseFixedPairConfirmationText("小明", "小红", PlayPreference.SOLO, inPlayingPosition = true)
        )
    }
}
