package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The limit of three skipped turns is stressed only once it is near (maintainer, 2026-11-25). */
class SkipOneTurnLimitNoticeTest {
    @Test
    fun theFirstTwoSkipsSayNothingAboutTheLimit() {
        assertNull(skipOneTurnLimitNotice("这份登记", 0))
        assertNull(skipOneTurnLimitNotice("这份登记", 1))
    }

    @Test
    fun theThirdSkipAndTheOneAfterAreStressed() {
        assertEquals(
            "“星星”已轮空 2 次，这次将是第 3 次。此后再轮到时，如果仍设置了轮空一次或暂时离开，登记会被自动移除。",
            skipOneTurnLimitNotice("“星星”", 2)
        )
        assertEquals(
            "这份登记已轮空 3 次。下一次轮到时，登记会被自动移除，而不会移至队尾。",
            skipOneTurnLimitNotice("这份登记", 3)
        )
    }
}
