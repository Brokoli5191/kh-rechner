package app.brokoli5191.khrechner.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.first

sealed interface ProductLookup {
    data class Found(val product: CachedProduct, val fromCache: Boolean) : ProductLookup
    data object NotFound : ProductLookup
    data class NoCarbs(val name: String) : ProductLookup
    data object Offline : ProductLookup
}

/** Barcode -> product, answered from the local cache where possible. */
class ProductRepository(
    private val store: DataStore<AppData>,
    private val source: ProductSource,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun lookup(barcode: String): ProductLookup {
        val cached = store.data.first().products.firstOrNull { it.barcode == barcode }
        if (cached != null && now() - cached.fetchedAt < FRESH_FOR_MILLIS) {
            return ProductLookup.Found(cached, fromCache = true)
        }
        return when (val remote = source.fetch(barcode)) {
            is RemoteProduct.Found -> {
                val product = CachedProduct(barcode, remote.name, remote.carbsPer100, remote.quantityGrams, fetchedAt = now())
                store.updateData { data ->
                    val others = data.products.filterNot { it.barcode == barcode }
                    // Oldest lookups fall out first once the cache is full.
                    data.copy(products = (others + product).sortedBy(CachedProduct::fetchedAt).takeLast(MAX_CACHED))
                }
                ProductLookup.Found(product, fromCache = false)
            }
            // A stale cache entry still beats no answer.
            is RemoteProduct.NoCarbs -> cached?.let { ProductLookup.Found(it, fromCache = true) } ?: ProductLookup.NoCarbs(remote.name)
            RemoteProduct.NotFound -> cached?.let { ProductLookup.Found(it, fromCache = true) } ?: ProductLookup.NotFound
            RemoteProduct.Unavailable -> cached?.let { ProductLookup.Found(it, fromCache = true) } ?: ProductLookup.Offline
        }
    }

    companion object {
        const val FRESH_FOR_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
        const val MAX_CACHED: Int = 500
    }
}
