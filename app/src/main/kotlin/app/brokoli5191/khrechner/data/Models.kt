package app.brokoli5191.khrechner.data

import app.brokoli5191.khrechner.domain.CalcInput
import app.brokoli5191.khrechner.domain.CarbUnit
import app.brokoli5191.khrechner.domain.EatenSource
import kotlinx.serialization.Serializable

/**
 * A saved calculation. It stores the inputs and which "eaten" field drove them, not the result,
 * so loading it back reproduces the calculator exactly. Numbers are canonical text (`.` separator).
 */
@Serializable
data class SavedEntry(
    val id: String,
    val name: String,
    /** Epoch milliseconds. */
    val createdAt: Long,
    val carbsPer100: String = "",
    val totalWeight: String = "",
    val pieceCount: String = "",
    val eatenSource: EatenSource? = null,
    val eatenValue: String = "",
    /** Name of the scanned product the carb value came from, if any. */
    val productName: String? = null,
) {
    val input: CalcInput
        get() = CalcInput(carbsPer100, totalWeight, pieceCount, eatenSource, eatenValue)

    /** Same name and same inputs: what an import treats as a duplicate. */
    fun hasSameContentAs(other: SavedEntry): Boolean = name == other.name && input == other.input

    companion object {
        const val MAX_NAME_LENGTH: Int = 60
    }
}

/** A product looked up by barcode, kept so repeat scans work offline. */
@Serializable
data class CachedProduct(
    val barcode: String,
    val name: String = "",
    /** Carbohydrates per 100 g, canonical text. */
    val carbsPer100: String,
    /** Package weight in grams if the source stated one in grams, canonical text. */
    val quantityGrams: String? = null,
    /** Epoch milliseconds. */
    val fetchedAt: Long,
)

@Serializable
data class AppData(
    val entries: List<SavedEntry> = emptyList(),
    val products: List<CachedProduct> = emptyList(),
)

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val carbUnit: CarbUnit = CarbUnit.NONE,
)
