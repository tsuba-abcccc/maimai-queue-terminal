package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Test

/** The layout fills the height of 720 on every screen (maintainer, 2026-11-27). */
class TerminalScaleTest {
    @Test
    fun theDesignScreenIsNotScaled() {
        assertEquals(1f, terminalScaleFactor(1280, 720), 0f)
    }

    @Test
    fun everyScreenFillsTheHeightOf720() {
        // The maintainer's terminal, 1920 × 1168 px at density 320: 81 %, the layout 1184 × 720.
        assertEquals(0.811f, terminalScaleFactor(960, 584), 0.001f)
        // A 10-inch 1920 × 1200 tablet at density 240.
        assertEquals(1.111f, terminalScaleFactor(1280, 800), 0.001f)
        assertEquals(1.5f, terminalScaleFactor(1920, 1080), 0.001f)
        assertEquals(1.667f, terminalScaleFactor(1920, 1200), 0.001f)
        // Wide but short: the height decides.
        assertEquals(0.903f, terminalScaleFactor(1280, 650), 0.001f)
    }

    @Test
    fun aNarrowScreenIsScaledByItsWidthNotBelow1100() {
        assertEquals(0.909f, terminalScaleFactor(1000, 700), 0.001f)
        // 4:3: 1024 × 768 keeps a layout 1100 wide.
        assertEquals(0.931f, terminalScaleFactor(1024, 768), 0.001f)
    }

    @Test
    fun theSizeLevelsEnlargeTheHeightFit() {
        // On the maintainer's terminal: 100 % is 81 %, the largest about 90 %.
        assertEquals(0.811f, terminalScaleFactor(960, 584, 0), 0.001f)
        assertEquals(0.900f, terminalScaleFactor(960, 584, 3), 0.002f)
        assertEquals(4, TERMINAL_SIZE_LEVELS.size)
        // An unknown level counts as 100 %.
        assertEquals(0.811f, terminalScaleFactor(960, 584, 9), 0.001f)
    }

    @Test
    fun theFactorIsBounded() {
        assertEquals(0.5f, terminalScaleFactor(400, 200), 0f)
        assertEquals(3f, terminalScaleFactor(7680, 4320), 0f)
        assertEquals(1f, terminalScaleFactor(0, 0), 0f)
    }
}
