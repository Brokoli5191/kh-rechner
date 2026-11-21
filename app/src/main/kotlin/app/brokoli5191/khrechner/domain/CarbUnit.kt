package app.brokoli5191.khrechner.domain

import kotlinx.serialization.Serializable

/** Optional second way to read the result, as used in German-speaking diabetes training. */
@Serializable
enum class CarbUnit(val gramsPerUnit: Int) {
    NONE(0),

    /** Kohlenhydrateinheit: 10 g of carbohydrates. */
    KE(10),

    /** Broteinheit: 12 g of carbohydrates. */
    BE(12),
    ;

    /** [carbGrams] expressed in this unit, or null for [NONE]. */
    fun convert(carbGrams: Fraction): Fraction? =
        if (this == NONE) null else carbGrams / Fraction.of(gramsPerUnit.toLong())
}
