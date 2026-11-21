package app.brokoli5191.khrechner.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Exact rational number.
 *
 * The calculator divides (weight / pieces, grams / total) and multiplies the results again.
 * With binary or fixed-precision decimals, `100 / 3 * 3` is no longer 100 and the question
 * "was this value rounded for display?" needs a tolerance. With fractions it is exact.
 */
class Fraction private constructor(
    private val numerator: BigInteger,
    private val denominator: BigInteger,
) : Comparable<Fraction> {

    val isZero: Boolean get() = numerator.signum() == 0
    val isWhole: Boolean get() = denominator == BigInteger.ONE

    operator fun times(other: Fraction): Fraction =
        of(numerator * other.numerator, denominator * other.denominator)

    /** @throws ArithmeticException if [other] is zero. */
    operator fun div(other: Fraction): Fraction {
        if (other.isZero) throw ArithmeticException("division by zero")
        return of(numerator * other.denominator, denominator * other.numerator)
    }

    override fun compareTo(other: Fraction): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    /** Rounds half up to [scale] decimal places. */
    fun toBigDecimal(scale: Int): BigDecimal =
        BigDecimal(numerator).divide(BigDecimal(denominator), scale, RoundingMode.HALF_UP)

    /** Exact decimal expansion, or null if it does not terminate (e.g. 1/3). */
    fun toExactBigDecimalOrNull(): BigDecimal? = try {
        BigDecimal(numerator).divide(BigDecimal(denominator))
    } catch (_: ArithmeticException) {
        null
    }

    override fun equals(other: Any?): Boolean =
        other is Fraction && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    override fun toString(): String = if (isWhole) numerator.toString() else "$numerator/$denominator"

    companion object {
        val ZERO: Fraction = Fraction(BigInteger.ZERO, BigInteger.ONE)
        val HUNDRED: Fraction = of(100)

        fun of(value: Long): Fraction = Fraction(BigInteger.valueOf(value), BigInteger.ONE)

        fun of(value: BigDecimal): Fraction {
            val unscaled = value.unscaledValue()
            return if (value.scale() >= 0) {
                of(unscaled, BigInteger.TEN.pow(value.scale()))
            } else {
                of(unscaled * BigInteger.TEN.pow(-value.scale()), BigInteger.ONE)
            }
        }

        private fun of(numerator: BigInteger, denominator: BigInteger): Fraction {
            require(denominator.signum() != 0) { "denominator must not be zero" }
            val gcd = numerator.gcd(denominator).let { if (it.signum() == 0) BigInteger.ONE else it }
            val sign = if (denominator.signum() < 0) BigInteger.ONE.negate() else BigInteger.ONE
            return Fraction(numerator / gcd * sign, denominator / gcd * sign)
        }
    }
}
