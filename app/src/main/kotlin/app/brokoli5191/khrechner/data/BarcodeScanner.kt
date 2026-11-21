package app.brokoli5191.khrechner.data

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface ScanResult {
    data class Code(val barcode: String) : ScanResult
    data object Cancelled : ScanResult

    /** The scanner could not run, typically because its module is still downloading. */
    data object Unavailable : ScanResult
}

/** Barcode scanning as the app sees it; the calculator does not care who provides it. */
interface BarcodeScanner {

    /** False if this device cannot scan at all; the scan button is hidden then. */
    val isSupported: Boolean

    /** Prepares the scanner ahead of the first use, silently and in the background. */
    fun requestModule()

    /** Opens the scanner on top of [activityContext] and suspends until it closes. */
    suspend fun scan(activityContext: Context): ScanResult
}

/**
 * Google code scanner. The camera UI runs inside Google Play services, so the app needs neither a
 * camera permission nor a camera library.
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

    /**
     * Asks Play services to download the scanner module now, without any UI. An APK installed from
     * outside the Play Store does not get it at install time, and the first scan should not have to
     * wait for a download (or fail offline).
     */
    override fun requestModule() {
        if (!isSupported) return
        try {
            val request = ModuleInstallRequest.newBuilder().addApi(client(context)).build()
            ModuleInstall.getClient(context).installModules(request)
                .addOnFailureListener { Log.w(TAG, "scanner module install failed", it) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "scanner module request failed", e)
        }
    }

    override suspend fun scan(activityContext: Context): ScanResult = suspendCancellableCoroutine { continuation ->
        try {
            client(activityContext).startScan()
                .addOnSuccessListener { barcode ->
                    if (!continuation.isActive) return@addOnSuccessListener
                    val code = barcode.rawValue?.trim()
                    continuation.resume(if (code.isNullOrEmpty()) ScanResult.Cancelled else ScanResult.Code(code))
                }
                .addOnCanceledListener { if (continuation.isActive) continuation.resume(ScanResult.Cancelled) }
                .addOnFailureListener { error ->
                    if (!continuation.isActive) return@addOnFailureListener
                    val cancelled = (error as? MlKitException)?.errorCode == MlKitException.CODE_SCANNER_CANCELLED
                    if (!cancelled) Log.w(TAG, "scan failed", error)
                    continuation.resume(if (cancelled) ScanResult.Cancelled else ScanResult.Unavailable)
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "scanner could not be started", e)
            continuation.resume(ScanResult.Unavailable)
        }
    }

    private fun client(context: Context): GmsBarcodeScanner = GmsBarcodeScanning.getClient(context, options)

    private companion object {
        const val TAG = "GmsBarcodeScanner"
    }
}
