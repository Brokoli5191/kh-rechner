package app.brokoli5191.khrechner.domain

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecimalTextTest {

    private fun value(text: String): Fraction = (DecimalText.parse(text) as ParsedNumber.Value).value

    @Test
    fun `comma and dot are both decimal separators`() {
        assertEquals(value("1.5"), value("1,5"))
        assertEquals(Fraction.of(BigDecimal("0.5")), value(",5"))
        assertEquals(Fraction.of(5), value("5,"))
        assertEquals(Fraction.of(5), value(" 5. "))
        assertEquals(Fraction.of(7), value("007"))
    }

    @Test
    fun `blank, negative and malformed text are told apart`() {
        assertEquals(ParsedNumber.Blank, DecimalText.parse(""))
        assertEquals(ParsedNumber.Blank, DecimalText.parse("   "))
        assertEquals(ParsedNumber.Negative, DecimalText.parse("-1"))
        assertEquals(ParsedNumber.Negative, DecimalText.parse("- 0,5"))
        assertEquals(ParsedNumber.Value(Fraction.ZERO), DecimalText.parse("-0"))
        for (bad in listOf(".", ",", "1.2.3", "1,2,3", "1 000", "1e3", "abc", "+1", "--1", "1-")) {
            assertEquals(bad, ParsedNumber.Invalid, DecimalText.parse(bad))
        }
    }

    @Test
    fun `format rounds half up and flags only real rounding`() {
        assertEquals(DisplayNumber("1.6", isApproximate = true), DecimalText.format(value("1.59")))
        assertEquals(DisplayNumber("1.6", isApproximate = true), DecimalText.format(value("1.55")))
        assertEquals(DisplayNumber("1.5", isApproximate = true), DecimalText.format(value("1.549")))
        assertEquals(DisplayNumber("1.6", isApproximate = false), DecimalText.format(value("1.60")))
        assertEquals(DisplayNumber("100", isApproximate = false), DecimalText.format(value("100")))
        assertEquals(DisplayNumber("0", isApproximate = false), DecimalText.format(Fraction.ZERO))
        assertEquals(DisplayNumber("0", isApproximate = true), DecimalText.format(value("0.04")))
        assertEquals(DisplayNumber("10", isApproximate = true), DecimalText.format(value("9.96")))
        assertEquals(DisplayNumber("0.33", isApproximate = true), DecimalText.format(Fraction.of(1) / Fraction.of(3), scale = 2))
    }

    @Test
    fun `normalize keeps unparseable text instead of dropping it`() {
        assertEquals("1.5", DecimalText.normalize("1,50"))
        assertEquals("0", DecimalText.normalize("0,0"))
        assertEquals("1200", DecimalText.normalize("1200"))
        assertEquals("abc", DecimalText.normalize(" abc "))
        assertEquals("-3", DecimalText.normalize("-3"))
        assertEquals("", DecimalText.normalize("  "))
    }

    @Test
    fun `fractions are exact`() {
        val third = Fraction.of(1) / Fraction.of(3)
        assertEquals(Fraction.of(1), third * Fraction.of(3))
        assertNull(third.toExactBigDecimalOrNull())
        assertEquals(BigDecimal("0.25"), (Fraction.of(1) / Fraction.of(4)).toExactBigDecimalOrNull())
        assertEquals(0, Fraction.of(BigDecimal("1.50")).compareTo(Fraction.of(BigDecimal("1.5"))))
        assertEquals(Fraction.of(BigDecimal("1.50")), Fraction.of(BigDecimal("1.5")))
        assertEquals(Fraction.of(1200), Fraction.of(BigDecimal("1.2E+3")))
    }

    @Test
    fun `carb units convert grams`() {
        assertNull(CarbUnit.NONE.convert(Fraction.of(24)))
        assertEquals(DisplayNumber("2.4", isApproximate = false), DecimalText.format(CarbUnit.KE.convert(Fraction.of(24))!!))
        assertEquals(DisplayNumber("2", isApproximate = false), DecimalText.format(CarbUnit.BE.convert(Fraction.of(24))!!))
        assertEquals(DisplayNumber("0.8", isApproximate = true), DecimalText.format(CarbUnit.BE.convert(Fraction.of(10))!!))
    }

    @Test
    fun `whole grams round half up`() {
        assertEquals(13, DecimalText.roundToWhole(value("12.72")))
        assertEquals(13, DecimalText.roundToWhole(value("12.5")))
        assertEquals(12, DecimalText.roundToWhole(value("12.49")))
        assertEquals(0, DecimalText.roundToWhole(value("0.499")))
        assertEquals(1, DecimalText.roundToWhole(value("0.5")))
        assertEquals(0, DecimalText.roundToWhole(Fraction.ZERO))
        assertEquals(33, DecimalText.roundToWhole(Fraction.of(100) / Fraction.of(3)))
    }
}
