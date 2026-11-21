package app.brokoli5191.khrechner.domain

import kotlinx.serialization.Serializable

/** Which of the three linked "eaten" fields the user edited last. */
@Serializable
enum class EatenSource { PERCENT, PIECES, GRAMS }

/** The fields of the calculator form. [GRAMS_PER_PIECE] is display-only and never carries an error. */
enum class CalcField { CARBS_PER_100, TOTAL_WEIGHT, PIECE_COUNT, GRAMS_PER_PIECE, EATEN_PERCENT, EATEN_PIECES, EATEN_GRAMS }

enum class CalcError {
    /** Not a number at all. */
    INVALID_NUMBER,
    NEGATIVE,
    /** Zero where a division needs a positive value (total weight, piece count). */
    NOT_POSITIVE,
    /** Piece count with a fractional part. */
    NOT_WHOLE,
    /** More than 100 g of carbohydrates in 100 g of food. */
    ABOVE_100,
    /** Percent was entered but there is no total weight to take the percentage of. */
    NEEDS_TOTAL_WEIGHT,
    /** Pieces were entered but grams per piece is unknown. */
    NEEDS_PIECE_WEIGHT,
}

/** Raw field contents exactly as typed. Blank means "not entered". */
data class CalcInput(
    val carbsPer100: String = "",
    val totalWeight: String = "",
    val pieceCount: String = "",
    val eatenSource: EatenSource? = null,
    /** Content of the field named by [eatenSource]; ignored when that is null. */
    val eatenValue: String = "",
) {
    val isBlank: Boolean
        get() = carbsPer100.isBlank() && totalWeight.isBlank() && pieceCount.isBlank() &&
            (eatenSource == null || eatenValue.isBlank())
}

/**
 * Everything the calculator screen shows. A null value means "cannot be computed from what was
 * entered" and is rendered as an empty field, never as 0.
 */
data class CalcResult(
    val gramsPerPiece: Fraction? = null,
    val eatenPercent: Fraction? = null,
    val eatenPieces: Fraction? = null,
    val eatenGrams: Fraction? = null,
    val carbsEaten: Fraction? = null,
    /** The field that currently drives the eaten amount, or null if none holds a value. */
    val eatenSource: EatenSource? = null,
    /** True if a percentage can be turned into grams (a valid total weight exists). */
    val percentUsable: Boolean = false,
    /** True if a piece count can be turned into grams (grams per piece is known). */
    val piecesUsable: Boolean = false,
    val errors: Map<CalcField, CalcError> = emptyMap(),
)

/**
 * Carbohydrate maths for a portion of food.
 *
 * - grams per piece = total weight / piece count
 * - eaten grams come from whichever "eaten" field the user edited last; the other two are derived
 * - carbs eaten = carbs per 100 g * eaten grams / 100
 *
 * Only carbs per 100 g and the eaten grams are required for a result. Entering grams directly
 * works without total weight or piece count.
 */
object CarbCalculator {

    fun calculate(input: CalcInput): CalcResult {
        val errors = mutableMapOf<CalcField, CalcError>()

        fun read(field: CalcField, text: String): Fraction? = when (val parsed = DecimalText.parse(text)) {
            is ParsedNumber.Value -> parsed.value
            ParsedNumber.Blank -> null
            ParsedNumber.Negative -> null.also { errors[field] = CalcError.NEGATIVE }
            ParsedNumber.Invalid -> null.also { errors[field] = CalcError.INVALID_NUMBER }
        }

        val carbsPer100 = read(CalcField.CARBS_PER_100, input.carbsPer100)?.let { value ->
            if (value > Fraction.HUNDRED) null.also { errors[CalcField.CARBS_PER_100] = CalcError.ABOVE_100 } else value
        }

        val totalWeight = read(CalcField.TOTAL_WEIGHT, input.totalWeight)?.let { value ->
            if (value.isZero) null.also { errors[CalcField.TOTAL_WEIGHT] = CalcError.NOT_POSITIVE } else value
        }

        val pieceCount = read(CalcField.PIECE_COUNT, input.pieceCount)?.let { value ->
            when {
                !value.isWhole -> null.also { errors[CalcField.PIECE_COUNT] = CalcError.NOT_WHOLE }
                value.isZero -> null.also { errors[CalcField.PIECE_COUNT] = CalcError.NOT_POSITIVE }
                else -> value
            }
        }

        val gramsPerPiece = if (totalWeight != null && pieceCount != null) totalWeight / pieceCount else null

        val source = input.eatenSource?.takeIf { input.eatenValue.isNotBlank() }
        var eatenGrams: Fraction? = null
        if (source != null) {
            val eaten = read(source.field, input.eatenValue)
            if (eaten != null) {
                when (source) {
                    EatenSource.PERCENT ->
                        if (totalWeight != null) {
                            eatenGrams = totalWeight * eaten / Fraction.HUNDRED
                        } else if (CalcField.TOTAL_WEIGHT !in errors) {
                            // Blame the percentage only if the total weight is missing, not if it is itself invalid.
                            errors[source.field] = CalcError.NEEDS_TOTAL_WEIGHT
                        }

                    EatenSource.PIECES ->
                        if (gramsPerPiece != null) {
                            eatenGrams = eaten * gramsPerPiece
                        } else if (CalcField.TOTAL_WEIGHT !in errors && CalcField.PIECE_COUNT !in errors) {
                            errors[source.field] = CalcError.NEEDS_PIECE_WEIGHT
                        }

                    EatenSource.GRAMS -> eatenGrams = eaten
                }
            }
        }

        // More than the package (150 %, 30 of 20 pieces) is allowed: someone may have eaten one and a half
        // packages and scanned only one. The screen points it out instead of refusing it.
        val carbsEaten = if (carbsPer100 != null && eatenGrams != null) {
            carbsPer100 * eatenGrams / Fraction.HUNDRED
        } else {
            null
        }

        return CalcResult(
            gramsPerPiece = gramsPerPiece,
            eatenPercent = eatenGrams?.let { grams -> totalWeight?.let { grams * Fraction.HUNDRED / it } },
            eatenPieces = eatenGrams?.let { grams -> gramsPerPiece?.let { grams / it } },
            eatenGrams = eatenGrams,
            carbsEaten = carbsEaten,
            eatenSource = source,
            percentUsable = totalWeight != null,
            piecesUsable = gramsPerPiece != null,
            errors = errors.toMap(),
        )
    }

    /** Canonical copy of [input] for storing: same numbers, one spelling ("1,50" -> "1.5"). */
    fun normalize(input: CalcInput): CalcInput {
        val source = input.eatenSource?.takeIf { input.eatenValue.isNotBlank() }
        return CalcInput(
            carbsPer100 = DecimalText.normalize(input.carbsPer100),
            totalWeight = DecimalText.normalize(input.totalWeight),
            pieceCount = DecimalText.normalize(input.pieceCount),
            eatenSource = source,
            eatenValue = if (source == null) "" else DecimalText.normalize(input.eatenValue),
        )
    }
}

val EatenSource.field: CalcField
    get() = when (this) {
        EatenSource.PERCENT -> CalcField.EATEN_PERCENT
        EatenSource.PIECES -> CalcField.EATEN_PIECES
        EatenSource.GRAMS -> CalcField.EATEN_GRAMS
    }
