package app.brokoli5191.khrechner.ui.saved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brokoli5191.khrechner.data.EntryRepository
import app.brokoli5191.khrechner.data.SavedEntry
import app.brokoli5191.khrechner.domain.CarbCalculator
import app.brokoli5191.khrechner.domain.DecimalText
import app.brokoli5191.khrechner.domain.DisplayNumber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A saved entry with everything its card shows already worked out. */
data class SavedItem(
    val entry: SavedEntry,
    val date: String,
    /** Recomputed from the stored inputs; null if they no longer give a result. */
    val carbsEaten: DisplayNumber?,
)

data class SavedUiState(
    /** False until the first read from disk, so the empty state does not flash on start. */
    val loaded: Boolean = false,
    val items: List<SavedItem> = emptyList(),
)

class SavedViewModel(
    private val entries: EntryRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    val uiState: StateFlow<SavedUiState> = entries.entries
        .map { saved -> SavedUiState(loaded = true, items = saved.map(::toItem)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SavedUiState())

    private fun toItem(entry: SavedEntry) = SavedItem(
        entry = entry,
        date = DATE.format(Instant.ofEpochMilli(entry.createdAt).atZone(zone)),
        carbsEaten = CarbCalculator.calculate(entry.input).carbsEaten?.let(DecimalText::format),
    )

    fun rename(entry: SavedEntry, name: String): Job = viewModelScope.launch { entries.rename(entry.id, name) }

    fun duplicate(entry: SavedEntry, name: String): Job = viewModelScope.launch { entries.duplicate(entry.id, name) }

    /** Completes with the removed entry (for undo), or null if it was already gone. */
    fun delete(entry: SavedEntry): Deferred<SavedEntry?> = viewModelScope.async { entries.delete(entry.id) }

    fun restore(entry: SavedEntry): Job = viewModelScope.launch { entries.restore(entry) }

    private companion object {
        // Austrian date format, independent of the UI language.
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
