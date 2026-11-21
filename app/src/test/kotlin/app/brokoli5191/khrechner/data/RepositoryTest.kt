package app.brokoli5191.khrechner.data

import app.brokoli5191.khrechner.domain.CalcInput
import app.brokoli5191.khrechner.domain.EatenSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepositoryTest {

    private var clock = 1_000L
    private var nextId = 0
    private val store = InMemoryDataStore(AppData())
    private val entries = EntryRepository(store, now = { clock }, newId = { "id${nextId++}" })

    // --- saved entries ----------------------------------------------------------------------

    @Test
    fun `saving stores normalised inputs and the source, loading gives them back`() = runTest {
        val input = CalcInput(carbsPer100 = "5,30", totalWeight = "250", pieceCount = "3", eatenSource = EatenSource.PIECES, eatenValue = "1,50")
        val saved = entries.save("  apfel  ", input, productName = "bio apfel")
        assertEquals("apfel", saved.name)
        assertEquals(
            CalcInput(carbsPer100 = "5.3", totalWeight = "250", pieceCount = "3", eatenSource = EatenSource.PIECES, eatenValue = "1.5"),
            saved.input,
        )
        assertEquals("bio apfel", saved.productName)
        assertEquals(listOf(saved), entries.entries.first())
    }

    @Test
    fun `entries are listed newest first`() = runTest {
        val first = entries.save("eins", CalcInput(carbsPer100 = "1"), null)
        clock = 2_000
        val second = entries.save("zwei", CalcInput(carbsPer100 = "2"), null)
        assertEquals(listOf(second, first), entries.entries.first())
    }

    @Test
    fun `names are capped at 60 characters`() = runTest {
        assertEquals(60, entries.save("x".repeat(80), CalcInput(), null).name.length)
    }

    @Test
    fun `rename, duplicate, delete and undo`() = runTest {
        val original = entries.save("apfel", CalcInput(carbsPer100 = "11"), null)
        entries.rename(original.id, "birne")
        clock = 5_000
        entries.duplicate(original.id, "birne (kopie)")

        val (copy, renamed) = entries.entries.first()
        assertEquals("birne", renamed.name)
        assertEquals(SavedEntry(id = "id1", name = "birne (kopie)", createdAt = 5_000, carbsPer100 = "11"), copy)

        val removed = entries.delete(renamed.id)
        assertEquals(renamed, removed)
        assertEquals(listOf(copy), entries.entries.first())
        assertNull(entries.delete(renamed.id))

        entries.restore(renamed)
        entries.restore(renamed)
        assertEquals(listOf(copy, renamed), entries.entries.first())
    }

    // --- product cache ----------------------------------------------------------------------

    private class FakeSource(var answer: RemoteProduct) : ProductSource {
        var calls = 0
        override suspend fun fetch(barcode: String): RemoteProduct = answer.also { calls++ }
    }

    private val found = RemoteProduct.Found("Nutella", "57.5", "400")
    private val barcode = "3017624010701"

    @Test
    fun `a hit is cached and the repeat scan needs no network`() = runTest {
        val source = FakeSource(found)
        val products = ProductRepository(store, source, now = { clock })

        val first = products.lookup(barcode) as ProductLookup.Found
        assertEquals(CachedProduct(barcode, "Nutella", "57.5", "400", fetchedAt = 1_000), first.product)
        assertEquals(false, first.fromCache)

        source.answer = RemoteProduct.Unavailable
        val second = products.lookup(barcode) as ProductLookup.Found
        assertEquals(first.product, second.product)
        assertEquals(true, second.fromCache)
        assertEquals(1, source.calls)
    }

    @Test
    fun `a stale cache entry is refreshed, and still used when offline`() = runTest {
        val source = FakeSource(found)
        val products = ProductRepository(store, source, now = { clock })
        products.lookup(barcode)

        clock += ProductRepository.FRESH_FOR_MILLIS
        source.answer = RemoteProduct.Unavailable
        assertEquals(true, (products.lookup(barcode) as ProductLookup.Found).fromCache)

        source.answer = found.copy(carbsPer100 = "56")
        val refreshed = products.lookup(barcode) as ProductLookup.Found
        assertEquals("56", refreshed.product.carbsPer100)
        assertEquals(false, refreshed.fromCache)
        assertEquals(1, store.value.products.size)
    }

    @Test
    fun `misses are reported and not cached`() = runTest {
        val source = FakeSource(RemoteProduct.NotFound)
        val products = ProductRepository(store, source, now = { clock })
        assertEquals(ProductLookup.NotFound, products.lookup(barcode))
        source.answer = RemoteProduct.NoCarbs("Wasser")
        assertEquals(ProductLookup.NoCarbs("Wasser"), products.lookup(barcode))
        source.answer = RemoteProduct.Unavailable
        assertEquals(ProductLookup.Offline, products.lookup(barcode))
        assertEquals(emptyList<CachedProduct>(), store.value.products)
    }

    @Test
    fun `the cache does not touch saved entries and drops its oldest products when full`() = runTest {
        val saved = entries.save("apfel", CalcInput(carbsPer100 = "11"), null)
        val source = FakeSource(found)
        val products = ProductRepository(store, source, now = { clock })
        repeat(ProductRepository.MAX_CACHED + 2) { index ->
            clock++
            products.lookup((10_000_000 + index).toString())
        }
        assertEquals(listOf(saved), store.value.entries)
        assertEquals(ProductRepository.MAX_CACHED, store.value.products.size)
        assertEquals("10000002", store.value.products.first().barcode)
    }
}
