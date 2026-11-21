package app.brokoli5191.khrechner.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the app knows about Google's scanner right now; shown on the about page. */
sealed interface ScannerStatus {
    /** No Google Play services on this device. */
    data object Unsupported : ScannerStatus
    data object Checking : ScannerStatus
    data object Ready : ScannerStatus
    data object NotInstalled : ScannerStatus

    /** @param percent download progress, or null while the download is pending. */
    data class Installing(val percent: Int?) : ScannerStatus
    data class InstallFailed(val reason: InstallFailure) : ScannerStatus
}

/**
 * Keeps track of Google's scanner module for the whole app, so that a download started at launch
 * or on the about page is visible everywhere and survives leaving either screen.
 */
class ScannerController(
    private val scanner: BarcodeScanner,
    private val scope: CoroutineScope,
) {

    val isSupported: Boolean get() = scanner.isSupported

    private val _status = MutableStateFlow(if (scanner.isSupported) ScannerStatus.Checking else ScannerStatus.Unsupported)
    val status: StateFlow<ScannerStatus> = _status.asStateFlow()

    private var installJob: Job? = null

    /** Asks again whether the module is on the device. Never starts a download. */
    fun refresh() {
        if (!scanner.isSupported || installJob?.isActive == true) return
        scope.launch {
            val availability = scanner.availability()
            if (installJob?.isActive == true) return@launch
            // A failure stays on show until the user retries; "not downloaded" would say less.
            if (availability == ScannerAvailability.NOT_INSTALLED && _status.value is ScannerStatus.InstallFailed) return@launch
            _status.value = when (availability) {
                ScannerAvailability.READY -> ScannerStatus.Ready
                ScannerAvailability.NOT_INSTALLED -> ScannerStatus.NotInstalled
                ScannerAvailability.UNSUPPORTED -> ScannerStatus.Unsupported
            }
        }
    }

    /** Starts the module download unless one is already running. */
    fun install() {
        if (!scanner.isSupported || installJob?.isActive == true) return
        _status.value = ScannerStatus.Installing(percent = null)
        installJob = scope.launch {
            scanner.install().collect { update ->
                _status.value = when (update) {
                    is InstallUpdate.Progress -> ScannerStatus.Installing(update.percent)
                    InstallUpdate.Done -> ScannerStatus.Ready
                    is InstallUpdate.Failed -> ScannerStatus.InstallFailed(update.reason)
                }
            }
        }
    }

    /**
     * Tries Google's scanner. [ScanResult.Unavailable] tells the caller to use the built-in one;
     * if the module is what is missing, its download is started on the side for next time.
     */
    suspend fun scan(activityContext: Context): ScanResult {
        if (!scanner.isSupported) return ScanResult.Unavailable(moduleMissing = false)
        val result = scanner.scan(activityContext)
        if (result is ScanResult.Unavailable && result.moduleMissing) {
            // Not after a failure: that would repeat a request Play services has just turned down.
            if (_status.value !is ScannerStatus.InstallFailed) install()
        } else if (result is ScanResult.Code) {
            _status.value = ScannerStatus.Ready
        }
        return result
    }
}
