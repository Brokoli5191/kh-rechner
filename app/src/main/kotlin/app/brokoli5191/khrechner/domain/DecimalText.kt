package app.brokoli5191.khrechner.domain

import java.math.BigDecimal

/** Outcome of reading a number the user typed. */
sealed interface ParsedNumber {
    data object Blank : ParsedNumber
    data class Value(val value: Fraction) : ParsedNumber
    data object Negative : ParsedNumber
    data object Invalid : ParsedNumber
}

/** A value rounded for display. [text] always uses `.`; the UI swaps in the locale separator. */
data class DisplayNumber(val text: String, val isApproximate: Boolean)

/**
 * Text <-> number conversion for everything the user types or reads.
 *
 * Inside the app numbers travel as "canonical" text: digits with an optional `.`. Both `,` and `.`
 * are accepted on input so that saved entries and exports do not depend on the device locale.
 */
object DecimalText {

    /** Decimal places shown for derived values and results. */
    const val DISPLAY_SCALE: Int = 1

    private val unsigned = Regex("""\d+([.,]\d*)?|[.,]\d+""")

    fun parse(text: String): ParsedNumber {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ParsedNumber.Blank
        val negative = trimmed.startsWith('-')
        val body = if (negative) trimmed.substring(1).trimStart() else trimmed
        if (!unsigned.matches(body)) return ParsedNumber.Invalid
        val value = Fraction.of(BigDecimal(body.replace(',', '.').let { if (it.startsWith('.')) "0$it" else it }.trimEnd('.')))
        // "-0" is not a negative amount.
        return if (negative && !value.isZero) ParsedNumber.Negative else ParsedNumber.Value(value)
    }

    /** Rounds half up to [scale] places and reports whether that changed the value. */
    fun format(value: Fraction, scale: Int = DISPLAY_SCALE): DisplayNumber {
        val rounded = value.toBigDecimal(scale)
        return DisplayNumber(plain(rounded), isApproximate = Fraction.of(rounded) != value)
    }

    /** [value] rounded half up to a whole number, e.g. grams for apps that take no decimals. */
    fun roundToWhole(value: Fraction): Int = value.toBigDecimal(0).intValueExact()

    /**
     * Canonical form of user input ("1,50" -> "1.5"). Text that is not a valid non-negative number
     * is returned trimmed and otherwise untouched, so nothing the user typed is silently lost.
     */
    fun normalize(text: String): String = when (val parsed = parse(text)) {
        is ParsedNumber.Value -> parsed.value.toExactBigDecimalOrNull()?.let(::plain) ?: text.trim()
        else -> text.trim()
    }

    private fun plain(value: BigDecimal): String =
        if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()
}
