package app.brokoli5191.khrechner.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import app.brokoli5191.khrechner.domain.DisplayNumber

/** Decimal separator of the locale the UI is shown in. Numbers are stored with `.` and shown with this. */
val LocalDecimalSeparator = staticCompositionLocalOf { '.' }

const val APPROXIMATELY: String = "≈"

fun String.withSeparator(separator: Char): String = if (separator == '.') this else replace('.', separator)

/** "≈ 1,6" for rounded values, "1,6" for exact ones. */
fun DisplayNumber.shown(separator: Char): String {
    val number = text.withSeparator(separator)
    return if (isApproximate) "$APPROXIMATELY $number" else number
}
