package app.brokoli5191.khrechner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarbCalculatorTest {

    private fun shown(value: Fraction?): String? = value?.let { DecimalText.format(it) }?.let {
        if (it.isApproximate) "≈ ${it.text}" else it.text
    }

    // --- the three source modes -------------------------------------------------------------

    @Test
    fun `grams as source derives percent and pieces`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "50", totalWeight = "200", pieceCount = "4", eatenSource = EatenSource.GRAMS, eatenValue = "75"),
        )
        assertEquals("50", shown(result.gramsPerPiece))
        assertEquals("75", shown(result.eatenGrams))
        assertEquals("37.5", shown(result.eatenPercent))
        assertEquals("1.5", shown(result.eatenPieces))
        assertEquals("37.5", shown(result.carbsEaten))
        assertEquals(EatenSource.GRAMS, result.eatenSource)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `percent as source derives grams and pieces`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "12", totalWeight = "250", pieceCount = "5", eatenSource = EatenSource.PERCENT, eatenValue = "40"),
        )
        assertEquals("100", shown(result.eatenGrams))
        assertEquals("40", shown(result.eatenPercent))
        assertEquals("2", shown(result.eatenPieces))
        assertEquals("12", shown(result.carbsEaten))
    }

    @Test
    fun `pieces as source derives grams and percent, decimals allowed`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "8", totalWeight = "80", pieceCount = "4", eatenSource = EatenSource.PIECES, eatenValue = "1,5"),
        )
        assertEquals("20", shown(result.gramsPerPiece))
        assertEquals("30", shown(result.eatenGrams))
        assertEquals("37.5", shown(result.eatenPercent))
        assertEquals("1.5", shown(result.eatenPieces))
        assertEquals("2.4", shown(result.carbsEaten))
    }

    @Test
    fun `the same portion gives the same result from every source`() {
        val base = CalcInput(carbsPer100 = "63.2", totalWeight = "150", pieceCount = "6")
        val fromGrams = CarbCalculator.calculate(base.copy(eatenSource = EatenSource.GRAMS, eatenValue = "50"))
        val fromPieces = CarbCalculator.calculate(base.copy(eatenSource = EatenSource.PIECES, eatenValue = "2"))
        val fromPercent = CarbCalculator.calculate(
            base.copy(eatenSource = EatenSource.PERCENT, eatenValue = "33.333333333333333333"),
        )
        assertEquals(fromGrams.carbsEaten, fromPieces.carbsEaten)
        assertEquals("31.6", shown(fromGrams.carbsEaten))
        // The truncated percentage is close but not identical, and says so.
        assertEquals("≈ 31.6", shown(fromPercent.carbsEaten))
    }

    // --- required vs optional fields --------------------------------------------------------

    @Test
    fun `grams entered directly need neither total weight nor piece count`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "5,3", eatenSource = EatenSource.GRAMS, eatenValue = "30"),
        )
        assertEquals("≈ 1.6", shown(result.carbsEaten))
        assertNull(result.gramsPerPiece)
        assertNull(result.eatenPercent)
        assertNull(result.eatenPieces)
        assertFalse(result.percentUsable)
        assertFalse(result.piecesUsable)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `total weight without piece count enables percent but not pieces`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "20", totalWeight = "500", eatenSource = EatenSource.GRAMS, eatenValue = "125"),
        )
        assertEquals("25", shown(result.eatenPercent))
        assertNull(result.eatenPieces)
        assertNull(result.gramsPerPiece)
        assertTrue(result.percentUsable)
        assertFalse(result.piecesUsable)
    }

    @Test
    fun `no carbs per 100 g means no result but derived fields still work`() {
        val result = CarbCalculator.calculate(
            CalcInput(totalWeight = "200", pieceCount = "4", eatenSource = EatenSource.PIECES, eatenValue = "1"),
        )
        assertNull(result.carbsEaten)
        assertEquals("50", shown(result.eatenGrams))
        assertEquals("25", shown(result.eatenPercent))
    }

    @Test
    fun `empty input computes nothing and reports nothing`() {
        val result = CarbCalculator.calculate(CalcInput())
        assertEquals(CalcResult(), result)
        assertTrue(CalcInput().isBlank)
    }

    @Test
    fun `a blank source field counts as no source`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "10", totalWeight = "100", eatenSource = EatenSource.PERCENT, eatenValue = "  "),
        )
        assertNull(result.eatenSource)
        assertNull(result.eatenGrams)
        assertNull(result.carbsEaten)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `percent without total weight is flagged on the percent field`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "10", eatenSource = EatenSource.PERCENT, eatenValue = "50"),
        )
        assertNull(result.carbsEaten)
        assertEquals(mapOf(CalcField.EATEN_PERCENT to CalcError.NEEDS_TOTAL_WEIGHT), result.errors)
    }

    @Test
    fun `pieces without grams per piece are flagged on the pieces field`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "10", totalWeight = "100", eatenSource = EatenSource.PIECES, eatenValue = "2"),
        )
        assertNull(result.carbsEaten)
        assertEquals(mapOf(CalcField.EATEN_PIECES to CalcError.NEEDS_PIECE_WEIGHT), result.errors)
    }

    // --- rounding and the approximation marker ----------------------------------------------

    @Test
    fun `exact values carry no approximation marker`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "10", totalWeight = "100", pieceCount = "8", eatenSource = EatenSource.PIECES, eatenValue = "2"),
        )
        assertEquals("12.5", shown(result.gramsPerPiece))
        assertEquals("25", shown(result.eatenGrams))
        assertEquals("2.5", shown(result.carbsEaten))
    }

    @Test
    fun `values that do not fit one decimal are marked as approximate`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "10", totalWeight = "100", pieceCount = "3", eatenSource = EatenSource.PIECES, eatenValue = "1"),
        )
        assertEquals("≈ 33.3", shown(result.gramsPerPiece))
        assertEquals("≈ 33.3", shown(result.eatenGrams))
        assertEquals("≈ 33.3", shown(result.eatenPercent))
        assertEquals("≈ 3.3", shown(result.carbsEaten))
    }

    @Test
    fun `thirds that add up to a whole are exact again`() {
        // 100 g / 3 pieces * 3 pieces is exactly 100 g, although a third of it is not representable.
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "10", totalWeight = "100", pieceCount = "3", eatenSource = EatenSource.PIECES, eatenValue = "3"),
        )
        assertEquals("100", shown(result.eatenGrams))
        assertEquals("100", shown(result.eatenPercent))
        assertEquals("10", shown(result.carbsEaten))
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `rounding is half up at one decimal`() {
        fun carbs(per100: String) = shown(
            CarbCalculator.calculate(CalcInput(carbsPer100 = per100, eatenSource = EatenSource.GRAMS, eatenValue = "1")).carbsEaten,
        )
        assertEquals("≈ 0", carbs("4.9"))    // 0.049
        assertEquals("≈ 0.1", carbs("5"))    // 0.05
        assertEquals("≈ 0.2", carbs("15"))   // 0.15
        assertEquals("0.3", carbs("30"))     // exactly 0.3
    }

    @Test
    fun `zero grams eaten is a valid result of zero`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "40", eatenSource = EatenSource.GRAMS, eatenValue = "0"),
        )
        assertEquals("0", shown(result.carbsEaten))
        assertTrue(result.errors.isEmpty())
    }

    // --- nonsense is rejected, never thrown -------------------------------------------------

    @Test
    fun `more than 100 percent is an inline error and gives no result`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "10", totalWeight = "100", eatenSource = EatenSource.PERCENT, eatenValue = "120"),
        )
        assertEquals(mapOf(CalcField.EATEN_PERCENT to CalcError.EXCEEDS_TOTAL), result.errors)
        assertNull(result.carbsEaten)
    }

    @Test
    fun `more grams than the total weight is flagged on the grams field`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "10", totalWeight = "100", eatenSource = EatenSource.GRAMS, eatenValue = "100.1"),
        )
        assertEquals(mapOf(CalcField.EATEN_GRAMS to CalcError.EXCEEDS_TOTAL), result.errors)
        assertNull(result.carbsEaten)
    }

    @Test
    fun `more pieces than exist is flagged on the pieces field`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "10", totalWeight = "100", pieceCount = "4", eatenSource = EatenSource.PIECES, eatenValue = "5"),
        )
        assertEquals(mapOf(CalcField.EATEN_PIECES to CalcError.EXCEEDS_TOTAL), result.errors)
        assertNull(result.carbsEaten)
    }

    @Test
    fun `exactly 100 percent is fine`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "10", totalWeight = "80", eatenSource = EatenSource.PERCENT, eatenValue = "100"),
        )
        assertEquals("8", shown(result.carbsEaten))
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `negative values are rejected per field`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "-5", totalWeight = "-1", pieceCount = "-2", eatenSource = EatenSource.GRAMS, eatenValue = "-3"),
        )
        assertEquals(
            mapOf(
                CalcField.CARBS_PER_100 to CalcError.NEGATIVE,
                CalcField.TOTAL_WEIGHT to CalcError.NEGATIVE,
                CalcField.PIECE_COUNT to CalcError.NEGATIVE,
                CalcField.EATEN_GRAMS to CalcError.NEGATIVE,
            ),
            result.errors,
        )
        assertNull(result.carbsEaten)
    }

    @Test
    fun `text that is not a number is rejected`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "abc", totalWeight = "1.2.3", eatenSource = EatenSource.GRAMS, eatenValue = "1e3"),
        )
        assertEquals(CalcError.INVALID_NUMBER, result.errors[CalcField.CARBS_PER_100])
        assertEquals(CalcError.INVALID_NUMBER, result.errors[CalcField.TOTAL_WEIGHT])
        assertEquals(CalcError.INVALID_NUMBER, result.errors[CalcField.EATEN_GRAMS])
    }

    @Test
    fun `zero total weight and zero pieces do not divide by zero`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "10", totalWeight = "0", pieceCount = "0", eatenSource = EatenSource.PERCENT, eatenValue = "50"),
        )
        assertEquals(CalcError.NOT_POSITIVE, result.errors[CalcField.TOTAL_WEIGHT])
        assertEquals(CalcError.NOT_POSITIVE, result.errors[CalcField.PIECE_COUNT])
        // The percentage itself is fine; the total weight is what needs fixing.
        assertNull(result.errors[CalcField.EATEN_PERCENT])
        assertNull(result.gramsPerPiece)
        assertNull(result.carbsEaten)
    }

    @Test
    fun `piece count must be a whole number`() {
        val result = CarbCalculator.calculate(CalcInput(totalWeight = "100", pieceCount = "2,5"))
        assertEquals(CalcError.NOT_WHOLE, result.errors[CalcField.PIECE_COUNT])
        assertNull(result.gramsPerPiece)
    }

    @Test
    fun `more than 100 g carbs per 100 g is rejected`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "100.5", eatenSource = EatenSource.GRAMS, eatenValue = "10"),
        )
        assertEquals(mapOf(CalcField.CARBS_PER_100 to CalcError.ABOVE_100), result.errors)
        assertNull(result.carbsEaten)
    }

    @Test
    fun `an invalid optional field does not block a result that does not need it`() {
        val result = CarbCalculator.calculate(
            CalcInput(carbsPer100 = "10", pieceCount = "0", eatenSource = EatenSource.GRAMS, eatenValue = "50"),
        )
        assertEquals("5", shown(result.carbsEaten))
        assertEquals(mapOf(CalcField.PIECE_COUNT to CalcError.NOT_POSITIVE), result.errors)
    }

    // --- normalisation for storage ----------------------------------------------------------

    @Test
    fun `normalize gives every number one spelling and drops an unused source`() {
        val normalized = CarbCalculator.normalize(
            CalcInput(carbsPer100 = " 5,30 ", totalWeight = "0100.", pieceCount = "04", eatenSource = EatenSource.PIECES, eatenValue = ",50"),
        )
        assertEquals(
            CalcInput(carbsPer100 = "5.3", totalWeight = "100", pieceCount = "4", eatenSource = EatenSource.PIECES, eatenValue = "0.5"),
            normalized,
        )
        assertEquals(
            CalcInput(carbsPer100 = "1"),
            CarbCalculator.normalize(CalcInput(carbsPer100 = "1", eatenSource = EatenSource.GRAMS, eatenValue = " ")),
        )
    }

    @Test
    fun `normalizing does not change the result`() {
        val input = CalcInput(carbsPer100 = "5,30", totalWeight = "250,0", pieceCount = "3", eatenSource = EatenSource.PIECES, eatenValue = "1,50")
        assertEquals(CarbCalculator.calculate(input), CarbCalculator.calculate(CarbCalculator.normalize(input)))
    }
}
