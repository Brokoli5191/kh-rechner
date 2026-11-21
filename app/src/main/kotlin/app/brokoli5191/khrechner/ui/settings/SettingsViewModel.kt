package app.brokoli5191.khrechner.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brokoli5191.khrechner.data.AppData
import app.brokoli5191.khrechner.data.BackupRepository
import app.brokoli5191.khrechner.data.ImportError
import app.brokoli5191.khrechner.data.ImportMode
import app.brokoli5191.khrechner.data.ImportOutcome
import app.brokoli5191.khrechner.data.LibraryCatalog
import app.brokoli5191.khrechner.data.LibraryGroup
import app.brokoli5191.khrechner.data.ScannerController
import app.brokoli5191.khrechner.data.ScannerStatus
import app.brokoli5191.khrechner.data.Settings
import app.brokoli5191.khrechner.data.SettingsRepository
import app.brokoli5191.khrechner.data.ThemeMode
import app.brokoli5191.khrechner.data.UpdateController
import app.brokoli5191.khrechner.data.UpdateStatus
import app.brokoli5191.khrechner.domain.CarbUnit
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-off outcomes the screen reports in a snackbar. */
sealed interface SettingsEvent {
    data class Exported(val entries: Int) : SettingsEvent
    data object ExportFailed : SettingsEvent
    data class Imported(val added: Int, val skipped: Int) : SettingsEvent
    data class ImportFailed(val error: ImportError, val fileVersion: Int?) : SettingsEvent
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val backup: BackupRepository,
    private val scanner: ScannerController,
    private val updates: UpdateController,
    libraries: LibraryCatalog,
    /** False on a device without a camera, where neither scanner can work. */
    val cameraAvailable: Boolean,
) : ViewModel() {

    val scannerStatus: StateFlow<ScannerStatus> = scanner.status

    val updateStatus: StateFlow<UpdateStatus> = updates.status

    fun checkForUpdate() = updates.checkNow()

    fun installUpdate() = updates.install()

    fun setCheckForUpdates(enabled: Boolean) = update { it.copy(checkForUpdates = enabled) }

    /** Read once from the bundled list; empty until then. */
    val libraries: StateFlow<List<LibraryGroup>> = flow { emit(libraries.load()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun installScanner() = scanner.install()

    fun refreshScanner() = scanner.refresh()

    /** A validated import waiting for the user to choose between merging and replacing. */
    private val _pendingImport = MutableStateFlow<AppData?>(null)
    val pendingImport: StateFlow<AppData?> = _pendingImport.asStateFlow()

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    fun setCarbUnit(unit: CarbUnit) = update { it.copy(carbUnit = unit) }

    private fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { settings.update(transform) }
    }

    fun export(uri: Uri) {
        viewModelScope.launch {
            val event = try {
                SettingsEvent.Exported(backup.exportTo(uri))
            } catch (_: IOException) {
                SettingsEvent.ExportFailed
            } catch (_: SecurityException) {
                SettingsEvent.ExportFailed
            }
            _events.send(event)
        }
    }

    fun readImport(uri: Uri) {
        viewModelScope.launch {
            when (val outcome = backup.read(uri)) {
                is ImportOutcome.Success -> _pendingImport.value = outcome.data
                is ImportOutcome.Failure -> _events.send(SettingsEvent.ImportFailed(outcome.error, outcome.fileVersion))
            }
        }
    }

    fun confirmImport(mode: ImportMode) {
        val data = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch {
            val result = backup.apply(data, mode)
            _events.send(SettingsEvent.Imported(result.addedEntries, result.skippedEntries))
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }
}
