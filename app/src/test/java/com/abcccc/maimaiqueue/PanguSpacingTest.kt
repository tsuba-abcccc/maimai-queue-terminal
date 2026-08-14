package com.abcccc.maimaiqueue

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PanguSpacingTest {
    @Test
    fun spacesChineseAroundLettersAndNumbersButCompactsAppMiddleDot() {
        assertEquals(
            "位置 A1·机台 A，30 分钟后可游玩。",
            panguSpacing("位置A1·机台A，30分钟后可游玩。")
        )
        assertEquals("左侧·机台 A", panguSpacing("左侧 · 机台 A"))
        assertEquals("第 2 次轮空", panguSpacing("第2次轮空"))
        assertEquals("QQ 号 123456", panguSpacing("QQ号123456"))
    }

    @Test
    fun handlesVersionsUrlsAndAsciiBracketsWithoutBreakingAsciiRuns() {
        assertEquals(
            "版本 v0.6.3 支持 Android 10 和 QQ Bot。",
            panguSpacing("版本v0.6.3支持Android 10和QQ Bot。")
        )
        assertEquals(
            "访问 https://abcccc.top 查看",
            panguSpacing("访问https://abcccc.top查看")
        )
        assertEquals("功能 (beta) 版本", panguSpacing("功能(beta)版本"))
        assertEquals("中文 + English", panguSpacing("中文+English"))
        assertEquals("完成 100% 进度", panguSpacing("完成100%进度"))
    }

    @Test
    fun keepsExistingWhitespaceAndLineBreaksStable() {
        val formatted = "位置 A1·机台 A\n30 分钟后"
        assertSame(formatted, panguSpacing(formatted))
        assertEquals("中文\nA1", panguSpacing("中文\nA1"))
        assertEquals("左侧\n·机台 A", panguSpacing("左侧\n·机台A"))
    }

    @Test
    fun formattingIsIdempotent() {
        val once = panguSpacing("线上登记A1·30分钟")
        assertEquals(once, panguSpacing(once))
    }

    @Test
    fun mixedNicknameTruncationDoesNotLeaveSpaceBeforeEllipsis() {
        assertEquals("Rin 酱", queueDisplayId("Rin酱"))
        assertEquals("AB 酱…", queueDisplayId("AB酱CD"))
    }

    @Test
    fun annotatedTextRetainsItsSpanAfterSpacesAreInserted() {
        val source = buildAnnotatedString {
            append("使用")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append("QQ")
            }
            append("通知")
        }

        val formatted = panguSpacing(source)

        assertEquals("使用 QQ 通知", formatted.text)
        assertEquals(1, formatted.spanStyles.size)
        assertEquals(3, formatted.spanStyles.single().start)
        assertEquals(5, formatted.spanStyles.single().end)
        assertEquals(FontWeight.Bold, formatted.spanStyles.single().item.fontWeight)
    }

    @Test
    fun annotatedTextCompactsUnicodeSpacingWithoutLosingSpans() {
        val source = buildAnnotatedString {
            append("左侧\u00a0")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append("·")
            }
            append("\u3000机台A")
        }

        val formatted = panguSpacing(source)

        assertEquals("左侧·机台 A", formatted.text)
        assertEquals(1, formatted.spanStyles.size)
        assertEquals(2, formatted.spanStyles.single().start)
        assertEquals(3, formatted.spanStyles.single().end)
        assertEquals(FontWeight.Bold, formatted.spanStyles.single().item.fontWeight)
    }
}
