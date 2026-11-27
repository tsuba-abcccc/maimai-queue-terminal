package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalScaleTest {
    @Test
    fun designScreenAndCommonTabletsAreNotScaled() {
        assertEquals(1f, terminalScaleFactor(1280, 720), 0f)
        // A 10-inch 1920 × 1200 tablet at density 240.
        assertEquals(1f, terminalScaleFactor(1280, 800), 0f)
    }

    @Test
    fun lowDensityLargePanelsAreEnlargedInProportion() {
        assertEquals(1.5f, terminalScaleFactor(1920, 1080), 0.001f)
        assertEquals(2f, terminalScaleFactor(2560, 1440), 0.001f)
        // The shorter proportion decides, so nothing is cut off.
        assertEquals(1.5f, terminalScaleFactor(1920, 1200), 0.001f)
    }

    @Test
    fun smallerScreensAreReducedInProportionAndTheFactorIsBounded() {
        // 2026-11-28: a screen with less room than 1280 × 720 dp shows the same layout, smaller,
        // instead of a crowded one. The maintainer's terminal, 1920 × 1168 px at density 320:
        assertEquals(0.75f, terminalScaleFactor(960, 584), 0.001f)
        assertEquals(0.8f, terminalScaleFactor(1024, 600), 0.001f)
        assertEquals(0.5f, terminalScaleFactor(400, 200), 0f)
        assertEquals(3f, terminalScaleFactor(7680, 4320), 0f)
        assertEquals(1f, terminalScaleFactor(0, 0), 0f)
    }
}
