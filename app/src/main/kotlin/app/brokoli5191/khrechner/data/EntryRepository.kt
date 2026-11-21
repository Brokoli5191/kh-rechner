package app.brokoli5191.khrechner.data

import androidx.datastore.core.DataStore
import app.brokoli5191.khrechner.domain.CalcInput
import app.brokoli5191.khrechner.domain.CarbCalculator
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class EntryRepository(
    private val store: DataStore<AppData>,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /** Newest first. */
    val entries: Flow<List<SavedEntry>> = store.data.map { data ->
        data.entries.sortedByDescending(SavedEntry::createdAt)
    }

    suspend fun save(name: String, input: CalcInput, productName: String?): SavedEntry {
        val normalized = CarbCalculator.normalize(input)
        val entry = SavedEntry(
            id = newId(),
            name = cleanName(name),
            createdAt = now(),
            carbsPer100 = normalized.carbsPer100,
            totalWeight = normalized.totalWeight,
            pieceCount = normalized.pieceCount,
            eatenSource = normalized.eatenSource,
            eatenValue = normalized.eatenValue,
            productName = productName,
        )
        store.updateData { it.copy(entries = it.entries + entry) }
        return entry
    }

    suspend fun rename(id: String, name: String) {
        store.updateData { data ->
            data.copy(entries = data.entries.map { if (it.id == id) it.copy(name = cleanName(name)) else it })
        }
    }

    /** Copies the entry under [name] with a fresh id and date. */
    suspend fun duplicate(id: String, name: String) {
        store.updateData { data ->
            val original = data.entries.firstOrNull { it.id == id } ?: return@updateData data
            data.copy(entries = data.entries + original.copy(id = newId(), name = cleanName(name), createdAt = now()))
        }
    }

    /** @return the removed entry so the deletion can be undone, or null if it was already gone. */
    suspend fun delete(id: String): SavedEntry? {
        var removed: SavedEntry? = null
        store.updateData { data ->
            removed = data.entries.firstOrNull { it.id == id }
            data.copy(entries = data.entries.filterNot { it.id == id })
        }
        return removed
    }

    /** Puts a deleted entry back unchanged (same id and date, so it returns to its old position). */
    suspend fun restore(entry: SavedEntry) {
        store.updateData { data ->
            if (data.entries.any { it.id == entry.id }) data else data.copy(entries = data.entries + entry)
        }
    }

    private fun cleanName(name: String): String = name.trim().take(SavedEntry.MAX_NAME_LENGTH)
}
