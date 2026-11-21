package app.brokoli5191.khrechner.data

import app.brokoli5191.khrechner.domain.DecimalText
import app.brokoli5191.khrechner.domain.ParsedNumber
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** On-disk layout of an export. Bump [BackupCodec.SCHEMA_VERSION] on any incompatible change. */
@Serializable
data class BackupFile(
    val schemaVersion: Int,
    val app: String = BackupCodec.APP_ID,
    /** ISO-8601 instant, informational only. */
    val exportedAt: String = "",
    val entries: List<SavedEntry> = emptyList(),
    val products: List<CachedProduct> = emptyList(),
)

sealed interface ImportOutcome {
    data class Success(val data: AppData) : ImportOutcome
    data class Failure(val error: ImportError, val fileVersion: Int? = null) : ImportOutcome
}

enum class ImportError {
    /** The document could not be opened or read. */
    UNREADABLE,
    /** Not JSON at all. */
    NOT_JSON,
    /** JSON, but without the fields that make it a KH Rechner export. */
    NOT_A_BACKUP,
    /** Written by a newer app version than this one understands. */
    NEWER_VERSION,
    /** A schema version this app never wrote (zero or negative). */
    UNKNOWN_VERSION,
    /** Right shape, but an entry or product holds values the app cannot use. */
    INVALID_CONTENT,
}

data class MergeResult(val data: AppData, val addedEntries: Int, val skippedEntries: Int)

/** Pure export/import logic: text in, text out. File access lives in [BackupRepository]. */
object BackupCodec {

    const val SCHEMA_VERSION: Int = 1
    const val APP_ID: String = "kh-rechner"
    const val MIME_TYPE: String = "application/json"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // Additive fields from later versions of the same schema are fine to drop.
        ignoreUnknownKeys = true
    }

    private val barcodePattern = Regex("""\d{6,14}""")

    private val pieceCountPattern = Regex("""[1-9]\d{0,3}""")

    fun fileName(date: LocalDate): String = "kh-rechner-${DateTimeFormatter.ISO_LOCAL_DATE.format(date)}.json"

    fun encode(data: AppData, exportedAt: Instant): String = json.encodeToString(
        BackupFile.serializer(),
        BackupFile(
            schemaVersion = SCHEMA_VERSION,
            exportedAt = exportedAt.toString(),
            entries = data.entries,
            products = data.products,
        ),
    )

    fun decode(text: String): ImportOutcome {
        val root = try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return ImportOutcome.Failure(ImportError.NOT_JSON)
        }
        val version = ((root as? JsonObject)?.get("schemaVersion") as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.intOrNull
            ?: return ImportOutcome.Failure(ImportError.NOT_A_BACKUP)
        if (version > SCHEMA_VERSION) return ImportOutcome.Failure(ImportError.NEWER_VERSION, version)
        if (version < 1) return ImportOutcome.Failure(ImportError.UNKNOWN_VERSION, version)

        val file = try {
            json.decodeFromJsonElement(BackupFile.serializer(), root)
        } catch (_: SerializationException) {
            return ImportOutcome.Failure(ImportError.INVALID_CONTENT, version)
        } catch (_: IllegalArgumentException) {
            return ImportOutcome.Failure(ImportError.INVALID_CONTENT, version)
        }
        if (file.entries.any { !it.isValid() } || file.products.any { !it.isValid() }) {
            return ImportOutcome.Failure(ImportError.INVALID_CONTENT, version)
        }
        return ImportOutcome.Success(
            AppData(
                entries = file.entries.distinctBy(SavedEntry::id),
                products = file.products.distinctBy(CachedProduct::barcode),
            ),
        )
    }

    /**
     * Adds [incoming] to [current]. Entries with the same name and the same inputs as an existing
     * one are skipped. Cached products are matched by barcode; the more recent lookup wins.
     */
    fun merge(current: AppData, incoming: AppData, newId: () -> String): MergeResult {
        val entries = current.entries.toMutableList()
        var skipped = 0
        for (entry in incoming.entries) {
            if (entries.any { it.hasSameContentAs(entry) }) {
                skipped++
            } else {
                // An id clash with different content means two unrelated entries; keep both.
                entries += if (entries.any { it.id == entry.id }) entry.copy(id = newId()) else entry
            }
        }
        val products = (current.products + incoming.products)
            .groupBy(CachedProduct::barcode)
            .map { (_, sameBarcode) -> sameBarcode.maxBy(CachedProduct::fetchedAt) }
        return MergeResult(
            data = AppData(entries, products),
            addedEntries = incoming.entries.size - skipped,
            skippedEntries = skipped,
        )
    }

    private fun SavedEntry.isValid(): Boolean =
        id.isNotBlank() &&
            name.isNotBlank() && name.length <= SavedEntry.MAX_NAME_LENGTH &&
            createdAt >= 0 &&
            listOf(carbsPer100, totalWeight, pieceCount, eatenValue).all(::isBlankOrNumber) &&
            // A source without a value (or the other way round) cannot have been written by the app.
            (eatenSource == null) == eatenValue.isBlank()

    private fun CachedProduct.isValid(): Boolean =
        barcodePattern.matches(barcode) &&
            DecimalText.parse(carbsPer100) is ParsedNumber.Value &&
            (quantityGrams == null || DecimalText.parse(quantityGrams) is ParsedNumber.Value) &&
            (pieceCount == null || pieceCountPattern.matches(pieceCount)) &&
            fetchedAt >= 0

    private fun isBlankOrNumber(text: String): Boolean = when (DecimalText.parse(text)) {
        ParsedNumber.Blank, is ParsedNumber.Value -> true
        ParsedNumber.Negative, ParsedNumber.Invalid -> false
    }
}
