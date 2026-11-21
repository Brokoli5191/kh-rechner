package app.brokoli5191.khrechner.data

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusCodes
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface ScanResult {
    data class Code(val barcode: String) : ScanResult

    /** The user closed the scanner, or one was already open. */
    data object Cancelled : ScanResult

    /**
     * Google's scanner did not open, so the scanner built into the app has to take over.
     *
     * @param moduleMissing true if the reason is the module that Play services downloads on demand.
     */
    data class Unavailable(val moduleMissing: Boolean) : ScanResult
}

enum class ScannerAvailability { READY, NOT_INSTALLED, UNSUPPORTED }

/** Progress of the one-off scanner module download. The flow ends after [Done] or [Failed]. */
sealed interface InstallUpdate {
    /** @param percent download progress, or null while it is pending or being installed. */
    data class Progress(val percent: Int?) : InstallUpdate
    data object Done : InstallUpdate
    data class Failed(val reason: InstallFailure) : InstallUpdate
}

/**
 * Why the module did not arrive, as far as it can be put in a sentence for the user. What Play
 * services said word for word goes to the log, not to the screen.
 */
sealed interface InstallFailure {
    /** Play services refuses to download the module over a metered connection. */
    data object MeteredNetwork : InstallFailure
    data object NoStorage : InstallFailure

    /** The download needs a connection and did not get one. */
    data object NoConnection : InstallFailure

    /** Play services does not hand this module to this device or this app. */
    data object NotOffered : InstallFailure

    /** Play services could not be reached or never answered. */
    data object NoAnswer : InstallFailure

    /** Play services failed on its side. [code] is its status code; it does not say more than that. */
    data class ServicesError(val code: Int) : InstallFailure
}

/** Google's barcode scanner as the app sees it. */
interface BarcodeScanner {

    /** False on devices without Google Play services. */
    val isSupported: Boolean

    suspend fun availability(): ScannerAvailability

    /** Downloads the scanner module and reports progress. Returns [InstallUpdate.Done] at once if it is already there. */
    fun install(): Flow<InstallUpdate>

    /** Opens the scanner on top of [activityContext] and suspends until it closes. */
    suspend fun scan(activityContext: Context): ScanResult
}

/**
 * Google code scanner. The camera UI runs inside Google Play services, so it needs no camera
 * permission of the app's own. Play services ships that UI as an optional module ("barcode_ui")
 * that is downloaded on demand; an APK installed from outside the Play Store never gets it at
 * install time, which is why [install] exists. Where the module cannot be had, the app falls back
 * to its own scanner.
 */
class GmsBarcodeScanner(private val context: Context) : BarcodeScanner {

    override val isSupported: Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (_: RuntimeException) {
        false
    }

    private val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
        .enableAutoZoom()
        .build()

    override suspend fun availability(): ScannerAvailability {
        if (!isSupported) return ScannerAvailability.UNSUPPORTED
        return try {
            val response = ModuleInstall.getClient(context).areModulesAvailable(client(context)).await()
            if (response.areModulesAvailable()) ScannerAvailability.READY else ScannerAvailability.NOT_INSTALLED
        } catch (e: Exception) {
            Log.w(TAG, "scanner availability check failed", e)
            ScannerAvailability.NOT_INSTALLED
        }
    }

    override fun install(): Flow<InstallUpdate> = callbackFlow {
        val modules = ModuleInstall.getClient(context)
        fun finish(update: InstallUpdate) {
            trySend(update)
            close()
        }
        val listener = InstallStatusListener { update ->
            when (update.installState) {
                InstallState.STATE_COMPLETED -> finish(InstallUpdate.Done)
                InstallState.STATE_FAILED, InstallState.STATE_CANCELED -> {
                    Log.w(TAG, "scanner module download failed with status ${update.errorCode}")
                    finish(InstallUpdate.Failed(failureFor(update.errorCode)))
                }
                else -> {
                    val progress = update.progressInfo?.takeIf { it.totalBytesToDownload > 0 }
                    trySend(InstallUpdate.Progress(progress?.let { (it.bytesDownloaded * 100 / it.totalBytesToDownload).toInt() }))
                }
            }
        }
        try {
            val request = ModuleInstallRequest.newBuilder().addApi(client(context)).setListener(listener).build()
            modules.installModules(request)
                .addOnSuccessListener { response ->
                    if (response.areModulesAlreadyInstalled()) finish(InstallUpdate.Done)
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "scanner module request failed", error)
                    // Play services answers with a status; anything else never reached it.
                    val reason = if (error is ApiException) failureFor(error.statusCode) else InstallFailure.NoAnswer
                    finish(InstallUpdate.Failed(reason))
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "scanner module request could not be made", e)
            finish(InstallUpdate.Failed(InstallFailure.NoAnswer))
        }
        awaitClose { modules.unregisterListener(listener) }
    }

    override suspend fun scan(activityContext: Context): ScanResult = try {
        val barcode = client(activityContext).startScan().await()
        val code = barcode.rawValue?.trim()
        if (code.isNullOrEmpty()) ScanResult.Cancelled else ScanResult.Code(code)
    } catch (e: TaskCancelled) {
        ScanResult.Cancelled
    } catch (e: MlKitException) {
        when (e.errorCode) {
            // "In progress" means a scanner is already on screen.
            MlKitException.CODE_SCANNER_CANCELLED, MlKitException.CODE_SCANNER_TASK_IN_PROGRESS -> ScanResult.Cancelled
            MlKitException.CODE_SCANNER_UNAVAILABLE -> ScanResult.Unavailable(moduleMissing = true)
            else -> {
                Log.w(TAG, "google scanner failed with ${e.errorCode}", e)
                ScanResult.Unavailable(moduleMissing = false)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "google scanner could not be started", e)
        ScanResult.Unavailable(moduleMissing = false)
    }

    private fun client(context: Context): GmsBarcodeScanner = GmsBarcodeScanning.getClient(context, options)

    private class TaskCancelled : Exception()

    /** Minimal bridge from a Play services [Task] to a coroutine; not worth a library for two calls. */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWith(Result.failure(it)) }
        addOnCanceledListener { if (continuation.isActive) continuation.resumeWith(Result.failure(TaskCancelled())) }
    }

    internal companion object {
        private const val TAG = "GmsBarcodeScanner"

        /** Sorts a Play services status code into what it means for the user. */
        internal fun failureFor(code: Int): InstallFailure = when (code) {
            ModuleInstallStatusCodes.METERED_NETWORK_NOT_ALLOWED -> InstallFailure.MeteredNetwork
            ModuleInstallStatusCodes.INSUFFICIENT_STORAGE -> InstallFailure.NoStorage
            ModuleInstallStatusCodes.UNKNOWN_MODULE,
            ModuleInstallStatusCodes.NOT_ALLOWED_MODULE,
            ModuleInstallStatusCodes.MODULE_NOT_FOUND,
            -> InstallFailure.NotOffered
            CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT -> InstallFailure.NoConnection
            CommonStatusCodes.API_NOT_CONNECTED,
            CommonStatusCodes.REMOTE_EXCEPTION,
            CommonStatusCodes.CONNECTION_SUSPENDED_DURING_CALL,
            CommonStatusCodes.RECONNECTION_TIMED_OUT,
            CommonStatusCodes.RECONNECTION_TIMED_OUT_DURING_UPDATE,
            -> InstallFailure.NoAnswer
            else -> InstallFailure.ServicesError(code)
        }
    }
}
