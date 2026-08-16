package com.abcccc.maimaiqueue

internal fun panguSpacing(value: String): String {
    val compacted = compactAppMiddleDotSpacing(value)
    if (compacted.length < 2) return compacted
    val insertionOffsets = panguSpaceInsertionOffsets(compacted)
    if (insertionOffsets.isEmpty()) return compacted

    val result = StringBuilder(compacted.length + 8)
    var copiedUntil = 0
    insertionOffsets.forEach { offset ->
        result.append(compacted, copiedUntil, offset)
        result.append(' ')
        copiedUntil = offset
    }
    result.append(compacted, copiedUntil, compacted.length)
    return result.toString()
}

internal fun compactAppMiddleDotSpacing(value: String): String =
    value.replace(APP_MIDDLE_DOT_SPACING_REGEX, "")

internal fun textBeforeAppMiddleDot(value: String): String =
    compactAppMiddleDotSpacing(value).substringBefore('·').trimEnd()

internal fun removeAppMiddleDotPrefix(value: String, prefix: String): String =
    compactAppMiddleDotSpacing(value).removePrefix("${compactAppMiddleDotSpacing(prefix)}·")

internal fun panguSpaceInsertionOffsets(value: String): List<Int> {
    if (value.length < 2) return emptyList()

    val offsets = mutableListOf<Int>()
    var previousPreviousCodePoint: Int? = null
    var previousCodePoint: Int? = null
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val nextIndex = index + Character.charCount(codePoint)
        val nextCodePoint = if (nextIndex < value.length) value.codePointAt(nextIndex) else null
        val needsOperatorSpacing = previousCodePoint != null && (
            needsSpaceAfterMixedOperator(
                previousPreviousCodePoint,
                previousCodePoint,
                codePoint
            ) || needsSpaceBeforeMixedOperator(
                previousCodePoint,
                codePoint,
                nextCodePoint
            )
        )
        val needsMiddleDotSpacing = previousCodePoint != null && (
            needsSpaceAroundHanMiddleDot(
                previousPreviousCodePoint,
                previousCodePoint,
                codePoint,
                nextCodePoint
            )
        )
        if (
            previousCodePoint != null &&
            (
                needsPanguSpace(previousCodePoint, codePoint) ||
                    needsOperatorSpacing ||
                    needsMiddleDotSpacing
            )
        ) {
            offsets += index
        }
        previousPreviousCodePoint = previousCodePoint
        previousCodePoint = codePoint
        index = nextIndex
    }
    return offsets
}

private fun needsSpaceAroundHanMiddleDot(
    previousPreviousCodePoint: Int?,
    previousCodePoint: Int,
    currentCodePoint: Int,
    nextCodePoint: Int?
): Boolean =
    (
        currentCodePoint == APP_MIDDLE_DOT &&
            nextCodePoint != null &&
            isHanCodePoint(previousCodePoint) &&
            isHanCodePoint(nextCodePoint)
    ) || (
        previousCodePoint == APP_MIDDLE_DOT &&
            previousPreviousCodePoint != null &&
            isHanCodePoint(previousPreviousCodePoint) &&
            isHanCodePoint(currentCodePoint)
    )

private fun needsSpaceAfterMixedOperator(
    previousPreviousCodePoint: Int?,
    operatorCodePoint: Int,
    currentCodePoint: Int
): Boolean {
    if (previousPreviousCodePoint == null || !isAsciiOperator(operatorCodePoint)) return false
    return (isCjkCodePoint(previousPreviousCodePoint) && isAsciiLetterOrDigit(currentCodePoint)) ||
        (isAsciiLetterOrDigit(previousPreviousCodePoint) && isCjkCodePoint(currentCodePoint))
}

private fun needsSpaceBeforeMixedOperator(
    previousCodePoint: Int,
    operatorCodePoint: Int,
    nextCodePoint: Int?
): Boolean {
    if (nextCodePoint == null || !isAsciiOperator(operatorCodePoint)) return false
    return (isCjkCodePoint(previousCodePoint) && isAsciiLetterOrDigit(nextCodePoint)) ||
        (isAsciiLetterOrDigit(previousCodePoint) && isCjkCodePoint(nextCodePoint))
}

internal fun needsPanguSpace(previousCodePoint: Int, currentCodePoint: Int): Boolean {
    if (isSpacingCharacter(previousCodePoint) || isSpacingCharacter(currentCodePoint)) return false
    if (previousCodePoint == APP_MIDDLE_DOT || currentCodePoint == APP_MIDDLE_DOT) return false
    return (isCjkCodePoint(previousCodePoint) && isAsciiOpeningToken(currentCodePoint)) ||
        (isAsciiClosingToken(previousCodePoint) && isCjkCodePoint(currentCodePoint))
}

private fun isSpacingCharacter(codePoint: Int): Boolean =
    Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

private fun isAsciiOpeningToken(codePoint: Int): Boolean =
    isAsciiLetterOrDigit(codePoint) || codePoint in ASCII_OPENING_TOKENS

private fun isAsciiClosingToken(codePoint: Int): Boolean =
    isAsciiLetterOrDigit(codePoint) || codePoint in ASCII_CLOSING_TOKENS

private fun isAsciiLetterOrDigit(codePoint: Int): Boolean =
    codePoint in 'A'.code..'Z'.code ||
        codePoint in 'a'.code..'z'.code ||
        codePoint in '0'.code..'9'.code

private fun isAsciiOperator(codePoint: Int): Boolean = codePoint in ASCII_OPERATORS

private fun isHanCodePoint(codePoint: Int): Boolean =
    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN

private fun isCjkCodePoint(codePoint: Int): Boolean = when (codePoint) {
    in 0x2E80..0x2FFF,
    in 0x3040..0x30FF,
    in 0x3100..0x312F,
    in 0x31A0..0x31BF,
    in 0x31F0..0x31FF,
    in 0x3400..0x4DBF,
    in 0x4E00..0x9FFF,
    in 0xAC00..0xD7AF,
    in 0xF900..0xFAFF,
    in 0x20000..0x2FA1F -> true
    else -> false
}

private const val APP_MIDDLE_DOT = '·'.code
private val APP_MIDDLE_DOT_SPACING_REGEX =
    Regex("[\\p{Zs}\\t]+(?=·)|(?<=·)[\\p{Zs}\\t]+")
private val ASCII_OPERATORS = intArrayOf(
    '+'.code, '-'.code, '*'.code, '/'.code, '='.code,
    '&'.code, '|'.code, '<'.code, '>'.code
)
private val ASCII_OPENING_TOKENS = intArrayOf(
    '('.code, '['.code, '{'.code, '<'.code, '"'.code, '\''.code,
    '#'.code, '$'.code, '@'.code, '+'.code, '-'.code, '*'.code,
    '/'.code, '='.code, '&'.code, '|'.code
)
private val ASCII_CLOSING_TOKENS = intArrayOf(
    ')'.code, ']'.code, '}'.code, '>'.code, '"'.code, '\''.code,
    '#'.code, '%'.code, '+'.code, '-'.code, '*'.code, '/'.code,
    '='.code, '&'.code, '|'.code
)
