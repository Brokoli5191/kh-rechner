package app.brokoli5191.khrechner.data

import android.content.Intent
import android.util.Log
import androidx.datastore.core.DataStore
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** What the last look at GitHub found, kept so a known update survives a restart. */
@Serializable
data class UpdateRecord(
    /** Epoch milliseconds of the last successful look; 0 if there was none. */
    val checkedAt: Long = 0,
    /** The release found then, if it was newer than the app that looked. */
    val latest: ReleaseInfo? = null,
)

sealed interface UpdateStatus {
    /** Never looked. */
    data object Unknown : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val checkedAt: Long) : UpdateStatus
    data class Available(val release: ReleaseInfo) : UpdateStatus

    /** @param percent null while the size is unknown. */
    data class Downloading(val release: ReleaseInfo, val percent: Int?) : UpdateStatus

    /** Handed to Android; its answer, or the end of the process, comes next. */
    data class Installing(val release: ReleaseInfo) : UpdateStatus

    /** @param release what the failed step was about; null if the failure was in looking for one. */
    data class Failed(val reason: UpdateFailure, val release: ReleaseInfo?) : UpdateStatus
}

/**
 * Looks for a newer release on GitHub when the app is opened, at most once a calendar day and only
 * if the user leaves that switched on, and installs it when the user asks. Nothing runs in the
 * background beyond that: no scheduled work, no notifications.
 */
class UpdateController(
    private val source: ReleaseSource,
    private val updater: AppUpdater,
    private val store: DataStore<UpdateRecord>,
    private val settings: Flow<Settings>,
    private val installedVersion: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _confirmations = Channel<Intent>(Channel.CONFLATED)

    /** Android's confirmation dialogs; the activity shows each one. */
    val confirmations: Flow<Intent> = _confirmations.receiveAsFlow()

    private var job: Job? = null

    init {
        job = scope.launch {
            val record = store.data.first()
            if (_status.value == UpdateStatus.Unknown) _status.value = statusFor(record)
        }
    }

    /** Called whenever the app comes to the front. Looks at most once a calendar day. */
    fun checkIfDue() {
        scope.launch {
            job?.join()
            if (!settings.first().checkForUpdates) return@launch
            if (isDue(store.data.first().checkedAt)) check(manual = false)
        }
    }

    /** "suchen" on the settings page. */
    fun checkNow() {
        if (job?.isActive == true) return
        check(manual = true)
    }

    private fun check(manual: Boolean) {
        if (job?.isActive == true) return
        val before = _status.value
        _status.value = UpdateStatus.Checking
        job = scope.launch {
            // GitHub usually answers in a fraction of a second; shown that briefly, the indicator
            // only flickers.
            val shown = launch { delay(MINIMUM_CHECKING) }
            // GitHub caches this answer for a minute, so asking again sooner learns nothing and
            // only uses up the 60 requests an hour it allows without an account.
            val recent = store.data.first().takeIf { now() - it.checkedAt in 0 until SAME_ANSWER_MILLIS }
            if (recent != null) {
                shown.join()
                _status.value = statusFor(recent)
                return@launch
            }
            val lookup = source.latest()
            shown.join()
            when (lookup) {
                is ReleaseLookup.Found -> {
                    val newer = lookup.release.takeIf { AppVersion.isNewer(it.version, installedVersion) }
                    val record = UpdateRecord(checkedAt = now(), latest = newer)
                    store.updateData { record }
                    _status.value = statusFor(record)
                }
                ReleaseLookup.NoRelease -> {
                    val record = UpdateRecord(checkedAt = now())
                    store.updateData { record }
                    _status.value = statusFor(record)
                }
                // A failed look on opening is not worth a message; the next opening tries again.
                is ReleaseLookup.Failed -> _status.value = if (manual) UpdateStatus.Failed(lookup.reason, null) else before
            }
        }
    }

    /** "installieren": loads the release that was found and hands it to Android. */
    fun install() {
        if (job?.isActive == true) return
        val release = when (val current = _status.value) {
            is UpdateStatus.Available -> current.release
            is UpdateStatus.Failed -> current.release
            else -> null
        } ?: return
        _status.value = UpdateStatus.Downloading(release, percent = null)
        job = scope.launch {
            val download = updater.download(release) { percent -> _status.value = UpdateStatus.Downloading(release, percent) }
            when (download) {
                is DownloadResult.Failed -> _status.value = UpdateStatus.Failed(download.reason, release)
                is DownloadResult.Ready -> {
                    _status.value = UpdateStatus.Installing(release)
                    try {
                        updater.install(download.apk)
                    } catch (e: IOException) {
                        Log.w(TAG, "update could not be handed to the installer", e)
                        _status.value = UpdateStatus.Failed(UpdateFailure.INSTALL_FAILED, release)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "update could not be handed to the installer", e)
                        _status.value = UpdateStatus.Failed(UpdateFailure.BLOCKED, release)
                    }
                }
            }
        }
    }

    /** Android's answer to [AppUpdater.install], through [UpdateInstallReceiver]. */
    fun onInstallResult(result: InstallResult) {
        val release = (_status.value as? UpdateStatus.Installing)?.release
        when (result) {
            is InstallResult.NeedsConfirmation -> _confirmations.trySend(result.intent)
            // The update ends this process; should it not, the next look finds nothing newer.
            InstallResult.Done -> Unit
            InstallResult.Cancelled -> if (release != null) _status.value = UpdateStatus.Available(release)
            is InstallResult.Failed -> _status.value = UpdateStatus.Failed(result.reason, release)
        }
    }

    private fun statusFor(record: UpdateRecord): UpdateStatus {
        val latest = record.latest?.takeIf { AppVersion.isNewer(it.version, installedVersion) }
        return when {
            latest != null -> UpdateStatus.Available(latest)
            record.checkedAt > 0 -> UpdateStatus.UpToDate(record.checkedAt)
            else -> UpdateStatus.Unknown
        }
    }

    private fun isDue(checkedAt: Long): Boolean {
        val time = now()
        if (checkedAt <= 0 || checkedAt > time) return true
        val zone = zone()
        return Instant.ofEpochMilli(checkedAt).atZone(zone).toLocalDate() != Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    }

    private companion object {
        const val TAG = "UpdateController"
        val MINIMUM_CHECKING = 1.seconds
        const val SAME_ANSWER_MILLIS = 60_000L
    }
}
