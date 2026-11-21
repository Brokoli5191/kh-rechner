package app.brokoli5191.khrechner.data

import app.brokoli5191.khrechner.domain.EatenSource
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private val apple = SavedEntry(
        id = "a", name = "apfel", createdAt = 1_700_000_000_000,
        carbsPer100 = "11.4", totalWeight = "180", pieceCount = "4", eatenSource = EatenSource.PIECES, eatenValue = "1.5",
        productName = "bio apfel",
    )
    private val bread = SavedEntry(
        id = "b", name = "brot", createdAt = 1_700_000_100_000,
        carbsPer100 = "45", eatenSource = EatenSource.GRAMS, eatenValue = "60",
    )
    private val nutella = CachedProduct("3017624010701", "Nutella", "57.5", "400", fetchedAt = 1_700_000_200_000)
    private val data = AppData(entries = listOf(apple, bread), products = listOf(nutella))

    private fun decoded(text: String): AppData = (BackupCodec.decode(text) as ImportOutcome.Success).data
    private fun failure(text: String): ImportOutcome.Failure = BackupCodec.decode(text) as ImportOutcome.Failure

    @Test
    fun `export and import round-trip every field`() {
        val text = BackupCodec.encode(data, Instant.parse("2026-11-21T10:00:00Z"))
        assertEquals(data, decoded(text))
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertTrue(text.contains("\"exportedAt\": \"2026-11-21T10:00:00Z\""))
    }

    @Test
    fun `an empty export round-trips`() {
        assertEquals(AppData(), decoded(BackupCodec.encode(AppData(), Instant.EPOCH)))
    }

    @Test
    fun `default file name carries the date`() {
        assertEquals("kh-rechner-2026-03-07.json", BackupCodec.fileName(LocalDate.of(2026, 3, 7)))
    }

    @Test
    fun `unknown fields from a later minor version are ignored`() {
        val text = """
            {"schemaVersion":1,"somethingNew":true,
             "entries":[{"id":"a","name":"apfel","createdAt":5,"carbsPer100":"11.4","eatenSource":"GRAMS","eatenValue":"100","flavour":"sour"}]}
        """.trimIndent()
        assertEquals(
            listOf(SavedEntry(id = "a", name = "apfel", createdAt = 5, carbsPer100 = "11.4", eatenSource = EatenSource.GRAMS, eatenValue = "100")),
            decoded(text).entries,
        )
    }

    @Test
    fun `a newer schema version is refused and reported`() {
        assertEquals(ImportOutcome.Failure(ImportError.NEWER_VERSION, 2), failure("""{"schemaVersion":2,"entries":[]}"""))
        assertEquals(ImportOutcome.Failure(ImportError.NEWER_VERSION, 99), failure("""{"schemaVersion":99}"""))
    }

    @Test
    fun `a version this app never wrote is refused`() {
        assertEquals(ImportOutcome.Failure(ImportError.UNKNOWN_VERSION, 0), failure("""{"schemaVersion":0}"""))
        assertEquals(ImportOutcome.Failure(ImportError.UNKNOWN_VERSION, -3), failure("""{"schemaVersion":-3}"""))
    }

    @Test
    fun `files that are not an export are told apart from broken json`() {
        assertEquals(ImportError.NOT_JSON, failure("definitely not json {").error)
        assertEquals(ImportError.NOT_JSON, failure("").error)
        assertEquals(ImportError.NOT_A_BACKUP, failure("[1,2,3]").error)
        assertEquals(ImportError.NOT_A_BACKUP, failure("""{"entries":[]}""").error)
        assertEquals(ImportError.NOT_A_BACKUP, failure("""{"schemaVersion":"1"}""").error)
        assertEquals(ImportError.NOT_A_BACKUP, failure("""{"schemaVersion":1.5}""").error)
        assertEquals(ImportError.NOT_A_BACKUP, failure("""{"schemaVersion":null}""").error)
    }

    @Test
    fun `structurally wrong content is refused`() {
        assertEquals(ImportError.INVALID_CONTENT, failure("""{"schemaVersion":1,"entries":"nope"}""").error)
        assertEquals(ImportError.INVALID_CONTENT, failure("""{"schemaVersion":1,"entries":[{"name":"no id"}]}""").error)
        assertEquals(
            ImportError.INVALID_CONTENT,
            failure("""{"schemaVersion":1,"entries":[{"id":"a","name":"x","createdAt":1,"eatenSource":"BITES","eatenValue":"1"}]}""").error,
        )
    }

    @Test
    fun `entries with unusable values are refused`() {
        fun entry(fields: String) = """{"schemaVersion":1,"entries":[{"id":"a","createdAt":1,$fields}]}"""
        assertEquals(ImportError.INVALID_CONTENT, failure(entry(""""name":"  """")).error)
        assertEquals(ImportError.INVALID_CONTENT, failure(entry(""""name":"${"x".repeat(61)}"""")).error)
        assertEquals(ImportError.INVALID_CONTENT, failure(entry(""""name":"x","carbsPer100":"abc"""")).error)
        assertEquals(ImportError.INVALID_CONTENT, failure(entry(""""name":"x","totalWeight":"-5"""")).error)
        assertEquals(ImportError.INVALID_CONTENT, failure(entry(""""name":"x","eatenSource":"GRAMS"""")).error)
        assertEquals(ImportError.INVALID_CONTENT, failure(entry(""""name":"x","eatenValue":"5"""")).error)
        assertEquals(
            ImportError.INVALID_CONTENT,
            failure("""{"schemaVersion":1,"products":[{"barcode":"12ab","carbsPer100":"5","fetchedAt":1}]}""").error,
        )
    }

    @Test
    fun `merge skips entries with the same name and inputs and keeps the rest`() {
        val sameAppleOtherId = apple.copy(id = "z", createdAt = 1, productName = null)
        val renamedApple = apple.copy(id = "y", name = "apfel groß")
        val otherInputs = apple.copy(id = "x", eatenValue = "2")
        val result = BackupCodec.merge(
            current = data,
            incoming = AppData(entries = listOf(sameAppleOtherId, renamedApple, otherInputs)),
            newId = { error("no id clash expected") },
        )
        assertEquals(2, result.addedEntries)
        assertEquals(1, result.skippedEntries)
        assertEquals(listOf(apple, bread, renamedApple, otherInputs), result.data.entries)
    }

    @Test
    fun `merge gives a clashing id to a different entry a new id instead of dropping it`() {
        val clash = bread.copy(id = apple.id)
        val result = BackupCodec.merge(AppData(entries = listOf(apple)), AppData(entries = listOf(clash))) { "fresh" }
        assertEquals(listOf(apple, clash.copy(id = "fresh")), result.data.entries)
        assertEquals(1, result.addedEntries)
        assertEquals(0, result.skippedEntries)
    }

    @Test
    fun `merge keeps the more recent lookup per barcode`() {
        val older = nutella.copy(carbsPer100 = "56", fetchedAt = nutella.fetchedAt - 1)
        val newer = nutella.copy(carbsPer100 = "58", fetchedAt = nutella.fetchedAt + 1)
        val other = CachedProduct("4000000000001", "anderes", "12", fetchedAt = 7)
        assertEquals(listOf(nutella), BackupCodec.merge(data, AppData(products = listOf(older))) { "" }.data.products)
        assertEquals(listOf(newer, other), BackupCodec.merge(data, AppData(products = listOf(newer, other))) { "" }.data.products)
    }

    @Test
    fun `importing the same export twice adds nothing`() {
        val incoming = decoded(BackupCodec.encode(data, Instant.EPOCH))
        val result = BackupCodec.merge(data, incoming) { error("no id clash expected") }
        assertEquals(data, result.data)
        assertEquals(0, result.addedEntries)
        assertEquals(2, result.skippedEntries)
    }
}
