package app.brokoli5191.khrechner.data

import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a product database said about one barcode. */
sealed interface RemoteProduct {
    data class Found(val name: String, val carbsPer100: String, val quantityGrams: String?) : RemoteProduct

    /** The product exists but has no usable carbohydrate value. */
    data class NoCarbs(val name: String) : RemoteProduct
    data object NotFound : RemoteProduct

    /** No connection, timeout, server error or an unreadable answer. */
    data object Unavailable : RemoteProduct
}

fun interface ProductSource {
    suspend fun fetch(barcode: String): RemoteProduct
}

/**
 * Open Food Facts product lookup, API v2.
 *
 * `GET /api/v2/product/{barcode}.json?fields=...` answers with `status` 1 and a `product` object,
 * or `status` 0 if the code is unknown. Carbohydrates per 100 g are in
 * `product.nutriments.carbohydrates_100g`. The package size comes pre-parsed as
 * `product_quantity` + `product_quantity_unit`, with the free-text `quantity` as fallback.
 * Docs: https://openfoodfacts.github.io/openfoodfacts-server/api/
 */
class OpenFoodFactsClient(
    private val userAgent: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ProductSource {

    override suspend fun fetch(barcode: String): RemoteProduct = withContext(io) {
        // The barcode goes into the URL path; anything but digits never leaves the device.
        if (!BARCODE.matches(barcode)) return@withContext RemoteProduct.NotFound
        val connection = try {
            URL("$BASE_URL/api/v2/product/$barcode.json?fields=$FIELDS").openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return@withContext RemoteProduct.Unavailable
        }
        try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    OpenFoodFactsParser.parse(connection.inputStream.use { it.readNBytes(MAX_BODY_BYTES).decodeToString() })
                HttpURLConnection.HTTP_NOT_FOUND -> RemoteProduct.NotFound
                else -> RemoteProduct.Unavailable
            }
        } catch (_: IOException) {
            RemoteProduct.Unavailable
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        val BARCODE: Regex = Regex("""\d{6,14}""")
        private const val BASE_URL = "https://world.openfoodfacts.org"
        private const val FIELDS = "product_name,nutriments,quantity,product_quantity,product_quantity_unit"
        private const val TIMEOUT_MILLIS = 5_000
        private const val MAX_BODY_BYTES = 1024 * 1024
    }
}

/** Turns an Open Food Facts answer into a [RemoteProduct]. Pure, so it is tested without a network. */
object OpenFoodFactsParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val hundred = BigDecimal(100)
    private val thousand = BigDecimal(1000)

    private const val NUMBER = """(\d+(?:[.,]\d+)?)"""
    private const val WEIGHT_UNIT = """(kg|g|gr|gramm|grammes?|grams?)"""
    private val multipack = Regex("""(\d+)\s*[x×*]\s*$NUMBER\s*$WEIGHT_UNIT\b""", RegexOption.IGNORE_CASE)
    private val single = Regex("""$NUMBER\s*$WEIGHT_UNIT\b""", RegexOption.IGNORE_CASE)

    fun parse(body: String): RemoteProduct {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return RemoteProduct.Unavailable

        val product = root["product"] as? JsonObject
        if (root["status"].text() != "1" || product == null) return RemoteProduct.NotFound

        val name = product["product_name"].text().orEmpty().trim()
        val carbs = (product["nutriments"] as? JsonObject)?.get("carbohydrates_100g").number()
            ?.takeIf { it.signum() >= 0 && it <= hundred }
            ?: return RemoteProduct.NoCarbs(name)

        return RemoteProduct.Found(
            name = name,
            carbsPer100 = plain(carbs.setScale(2, RoundingMode.HALF_UP)),
            quantityGrams = quantityGrams(product)?.let(::plain),
        )
    }

    private fun quantityGrams(product: JsonObject): BigDecimal? {
        val amount = product["product_quantity"].number()?.takeIf { it.signum() > 0 }
        return when (product["product_quantity_unit"].text()?.trim()?.lowercase()) {
            "g" -> amount
            "kg" -> amount?.multiply(thousand)
            // No pre-parsed unit: fall back to the label text. Any other unit (ml, l, ...) is not a weight.
            null, "" -> parseGrams(product["quantity"].text())
            else -> null
        }
    }

    /** Grams from label text such as "400 g", "1,5 kg" or "6 x 25 g"; null if it is not a weight. */
    fun parseGrams(quantity: String?): BigDecimal? {
        if (quantity.isNullOrBlank()) return null
        multipack.find(quantity)?.let { match ->
            val (count, amount, unit) = match.destructured
            return grams(amount, unit)?.multiply(BigDecimal(count))?.takeIf { it.signum() > 0 }
        }
        return single.find(quantity)?.let { match ->
            val (amount, unit) = match.destructured
            grams(amount, unit)?.takeIf { it.signum() > 0 }
        }
    }

    private fun grams(amount: String, unit: String): BigDecimal? {
        val value = amount.replace(',', '.').toBigDecimalOrNull() ?: return null
        return if (unit.equals("kg", ignoreCase = true)) value.multiply(thousand) else value
    }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeUnless { it.content == "null" && !it.isString }?.content

    // Open Food Facts stores some numbers as strings ("57.5"); accept both spellings.
    private fun JsonElement?.number(): BigDecimal? = text()?.trim()?.replace(',', '.')?.toBigDecimalOrNull()

    private fun plain(value: BigDecimal): String =
        if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()
}
