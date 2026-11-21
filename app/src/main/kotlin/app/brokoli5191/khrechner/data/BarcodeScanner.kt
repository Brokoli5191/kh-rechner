package app.brokoli5191.khrechner.data

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
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

/** Why the module did not arrive. The three with a text carry it verbatim, for bug reports. */
sealed interface InstallFailure {
    /** Play services refuses to download the module over a metered connection. */
    data object MeteredNetwork : InstallFailure
    data object NoStorage : InstallFailure

    /** Play services answered the request with an error. [status] is its code and name, e.g. "8 INTERNAL_ERROR". */
    data class Rejected(val status: String) : InstallFailure

    /** Play services accepted the request and the download failed afterwards. */
    data class DownloadFailed(val status: String) : InstallFailure

    /** The request never got an answer from Play services. [error] is the exception that stopped it. */
    data class NotSent(val error: String) : InstallFailure
}

/** Google's barcode scanner as the app sees it. */
interface BarcodeScanner {

    /** False on devices without Google Play services. */
    val isSupported: Boolean

    /** Version of Google Play services on this device, for the about page; null if there is none. */
    val servicesVersion: String?

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

    override val servicesVersion: String?
        get() = try {
            context.packageManager.getPackageInfo(GoogleApiAvailability.GOOGLE_PLAY_SERVICES_PACKAGE, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
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
                    Log.w(TAG, "scanner module download failed: ${statusText(update.errorCode)}")
                    finish(InstallUpdate.Failed(failure(update.errorCode, InstallFailure::DownloadFailed)))
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
                    val reason = if (error is ApiException) {
                        failure(error.statusCode, InstallFailure::Rejected, error.status.statusMessage)
                    } else {
                        InstallFailure.NotSent(errorText(error))
                    }
                    finish(InstallUpdate.Failed(reason))
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "scanner module request could not be made", e)
            finish(InstallUpdate.Failed(InstallFailure.NotSent(errorText(e))))
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

    private fun failure(code: Int, other: (String) -> InstallFailure, message: String? = null): InstallFailure = when (code) {
        ModuleInstallStatusCodes.METERED_NETWORK_NOT_ALLOWED -> InstallFailure.MeteredNetwork
        ModuleInstallStatusCodes.INSUFFICIENT_STORAGE -> InstallFailure.NoStorage
        else -> other(statusText(code, message))
    }

    private class TaskCancelled : Exception()

    /** Minimal bridge from a Play services [Task] to a coroutine; not worth a library for two calls. */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWith(Result.failure(it)) }
        addOnCanceledListener { if (continuation.isActive) continuation.resumeWith(Result.failure(TaskCancelled())) }
    }

    internal companion object {
        private const val TAG = "GmsBarcodeScanner"

        /** "8 INTERNAL_ERROR", plus whatever Play services added in words. */
        internal fun statusText(code: Int, message: String? = null): String {
            val name = ModuleInstallStatusCodes.getStatusCodeString(code)
            // Codes without a name come back as "unknown status code: 123"; the number alone says the same.
            val status = if (name.startsWith("unknown")) "$code" else "$code $name"
            return if (message.isNullOrBlank() || message == name) status else "$status: ${message.trim()}"
        }

        internal fun errorText(error: Throwable): String {
            val name = error.javaClass.simpleName.ifEmpty { error.javaClass.name }
            return error.message?.takeIf(String::isNotBlank)?.let { "$name: ${it.trim()}" } ?: name
        }
    }
}
