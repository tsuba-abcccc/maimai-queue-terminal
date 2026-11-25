package com.abcccc.maimaiqueue

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    autoSize: TextAutoSize? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) {
    if ('·' in text) {
        // The separator needs a span to get its side room, so this text is drawn as styled text.
        Text(
            text = remember(text) { AnnotatedString(text) },
            modifier = modifier,
            color = color,
            autoSize = autoSize,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout ?: {},
            style = style
        )
        return
    }
    val displayText = remember(text) { panguSpacing(text) }
    MaterialText(
        text = displayText,
        modifier = modifier,
        color = color,
        autoSize = autoSize,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style
    )
}

@Composable
internal fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    autoSize: TextAutoSize? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current
) {
    val displayText = remember(text) { withAppMiddleDotSideSpace(panguSpacing(text)) }
    MaterialText(
        text = displayText,
        modifier = modifier,
        color = color,
        autoSize = autoSize,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        inlineContent = inlineContent,
        onTextLayout = onTextLayout,
        style = style
    )
}

internal fun panguSpacing(value: AnnotatedString): AnnotatedString {
    if (value.isEmpty()) return value
    val compactedText = compactAppMiddleDotSpacing(value.text)
    val compacted = if (compactedText == value.text) {
        value
    } else {
        val compactedBuilder = AnnotatedString.Builder()
        var sourceIndex = 0
        while (sourceIndex < value.length) {
            val middleDotIndex = value.text.indexOf('·', sourceIndex)
            if (middleDotIndex < 0) {
                compactedBuilder.append(value.subSequence(sourceIndex, value.length))
                break
            }
            var left = middleDotIndex
            while (left > sourceIndex && value.text[left - 1].isAppSpacingCharacter()) left--
            compactedBuilder.append(value.subSequence(sourceIndex, left))
            compactedBuilder.append(value.subSequence(middleDotIndex, middleDotIndex + 1))
            sourceIndex = middleDotIndex + 1
            while (sourceIndex < value.length && value.text[sourceIndex].isAppSpacingCharacter()) {
                sourceIndex++
            }
        }
        compactedBuilder.toAnnotatedString()
    }
    val insertionOffsets = panguSpaceInsertionOffsets(compacted.text)
    if (insertionOffsets.isEmpty()) return compacted

    val builder = AnnotatedString.Builder()
    var copiedUntil = 0
    insertionOffsets.forEach { offset ->
        builder.append(compacted.subSequence(copiedUntil, offset))
        builder.append(' ')
        copiedUntil = offset
    }
    builder.append(compacted.subSequence(copiedUntil, compacted.length))
    return builder.toAnnotatedString()
}

/**
 * Room added on each side of the `·` separator when it is drawn, as a share of the font size
 * (docs/ui-guide.md). The text itself stays compact (`甲·乙`), so stored, synced and logged
 * strings do not change. A half-width space in the system font is about 0.25 em, which reads
 * slightly too wide around the dot; this is a little narrower.
 */
internal const val APP_MIDDLE_DOT_SIDE_SPACE_EM = 0.15f

/**
 * Gives every `·` the same room on both sides. Android spreads letter spacing evenly before and
 * after a glyph, so spacing the dot alone by twice the side room adds that room on each side,
 * in proportion to the font size, whatever the surrounding text.
 */
internal fun withAppMiddleDotSideSpace(value: AnnotatedString): AnnotatedString {
    var dotIndex = value.text.indexOf('·')
    if (dotIndex < 0) return value
    val builder = AnnotatedString.Builder(value)
    val dotStyle = SpanStyle(letterSpacing = (APP_MIDDLE_DOT_SIDE_SPACE_EM * 2).em)
    while (dotIndex >= 0) {
        builder.addStyle(dotStyle, dotIndex, dotIndex + 1)
        dotIndex = value.text.indexOf('·', dotIndex + 1)
    }
    return builder.toAnnotatedString()
}

private fun Char.isAppSpacingCharacter(): Boolean =
    this != '\n' &&
        this != '\r' &&
        this != '\u2028' &&
        this != '\u2029' &&
        (this == '\t' || Character.getType(this) == Character.SPACE_SEPARATOR.toInt())
