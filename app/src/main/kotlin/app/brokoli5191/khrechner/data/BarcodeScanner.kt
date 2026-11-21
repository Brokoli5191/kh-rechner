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
    data object Cancelled : ScanResult

    /** The scanner did not run. [code] is the library's own error code, shown to the user for [ScanFailure.OTHER]. */
    data class Failed(val reason: ScanFailure, val code: Int) : ScanResult
}

enum class ScanFailure {
    /** The scanner UI module is not on the device yet. It has to be downloaded once. */
    MODULE_NOT_READY,
    PLAY_SERVICES_OUTDATED,

    /** Google Play services itself has no camera permission. */
    CAMERA_PERMISSION,

    /** A scan is already open. */
    BUSY,
    OTHER,
}

enum class ScannerAvailability { READY, NOT_INSTALLED, UNSUPPORTED }

/** Progress of the one-off scanner module download. The flow ends after [Done] or [Failed]. */
sealed interface InstallUpdate {
    /** @param percent download progress, or null while it is pending or being installed. */
    data class Progress(val percent: Int?) : InstallUpdate
    data object Done : InstallUpdate
    data class Failed(val reason: InstallFailure, val code: Int) : InstallUpdate
}

enum class InstallFailure {
    /** Play services refuses to download the module over a metered connection. */
    METERED_NETWORK,
    NO_STORAGE,
    OTHER,
}

/** Barcode scanning as the app sees it; the calculator does not care who provides it. */
interface BarcodeScanner {

    /** False if this device cannot scan at all; the scan button is hidden then. */
    val isSupported: Boolean

    suspend fun availability(): ScannerAvailability

    /** Downloads the scanner module and reports progress. Returns [InstallUpdate.Done] at once if it is already there. */
    fun install(): Flow<InstallUpdate>

    /** Opens the scanner on top of [activityContext] and suspends until it closes. */
    suspend fun scan(activityContext: Context): ScanResult
}

/**
 * Google code scanner. The camera UI runs inside Google Play services, so the app needs neither a
 * camera permission nor a camera library. Play services ships that UI as an optional module
 * ("barcode_ui") that is downloaded on demand; an APK installed from outside the Play Store never
 * gets it at install time, which is why [install] exists.
 */
class GmsBarcodeScanner(private val context: Context) : BarcodeScanner {

    /** False on devices without Google Play services. */
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
        val listener = InstallStatusListener { update ->
            when (update.installState) {
                InstallState.STATE_COMPLETED -> {
                    trySend(InstallUpdate.Done)
                    close()
                }
                InstallState.STATE_FAILED, InstallState.STATE_CANCELED -> {
                    trySend(installFailure(update.errorCode))
                    close()
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
                    if (response.areModulesAlreadyInstalled()) {
                        trySend(InstallUpdate.Done)
                        close()
                    }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "scanner module install failed", error)
                    trySend(installFailure((error as? ApiException)?.statusCode ?: CommonStatusCodes.INTERNAL_ERROR))
                    close()
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "scanner module request failed", e)
            trySend(installFailure(CommonStatusCodes.INTERNAL_ERROR))
            close()
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
            MlKitException.CODE_SCANNER_CANCELLED -> ScanResult.Cancelled
            MlKitException.CODE_SCANNER_UNAVAILABLE -> ScanResult.Failed(ScanFailure.MODULE_NOT_READY, e.errorCode)
            MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD ->
                ScanResult.Failed(ScanFailure.PLAY_SERVICES_OUTDATED, e.errorCode)
            MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED ->
                ScanResult.Failed(ScanFailure.CAMERA_PERMISSION, e.errorCode)
            MlKitException.CODE_SCANNER_TASK_IN_PROGRESS -> ScanResult.Failed(ScanFailure.BUSY, e.errorCode)
            else -> {
                Log.w(TAG, "scan failed", e)
                ScanResult.Failed(ScanFailure.OTHER, e.errorCode)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "scanner could not be started", e)
        ScanResult.Failed(ScanFailure.OTHER, MlKitException.UNKNOWN)
    }

    private fun client(context: Context): GmsBarcodeScanner = GmsBarcodeScanning.getClient(context, options)

    private fun installFailure(code: Int): InstallUpdate.Failed = InstallUpdate.Failed(
        reason = when (code) {
            ModuleInstallStatusCodes.METERED_NETWORK_NOT_ALLOWED -> InstallFailure.METERED_NETWORK
            ModuleInstallStatusCodes.INSUFFICIENT_STORAGE -> InstallFailure.NO_STORAGE
            else -> InstallFailure.OTHER
        },
        code = code,
    )

    private class TaskCancelled : Exception()

    /** Minimal bridge from a Play services [Task] to a coroutine; not worth a library for two calls. */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWith(Result.failure(it)) }
        addOnCanceledListener { if (continuation.isActive) continuation.resumeWith(Result.failure(TaskCancelled())) }
    }

    private companion object {
        const val TAG = "GmsBarcodeScanner"
    }
}
