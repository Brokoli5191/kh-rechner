package app.brokoli5191.khrechner

import android.content.Context
import app.brokoli5191.khrechner.data.AapsHandoff
import app.brokoli5191.khrechner.data.BarcodeScanner
import app.brokoli5191.khrechner.data.InstallUpdate
import app.brokoli5191.khrechner.data.ProductSource
import app.brokoli5191.khrechner.data.RemoteProduct
import app.brokoli5191.khrechner.data.ScanFailure
import app.brokoli5191.khrechner.data.ScanResult
import app.brokoli5191.khrechner.data.ScannerAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The real app with its outside dependencies (scanner, network, AAPS) replaced by scripted fakes. */
class TestApplication : KhRechnerApplication() {

    val scanner = FakeScanner()
    val productSource = FakeProductSource()
    val aaps = FakeAaps()

    override fun createContainer(): AppContainer = AppContainer(this, scanner, productSource, aaps)
}

class FakeAaps : AapsHandoff {
    var installed = false
    val handedOver = mutableListOf<Int>()
    override fun isInstalled(): Boolean = installed
    override fun copyAndOpen(grams: Int): Boolean {
        handedOver += grams
        return true
    }
}

/**
 * Scripted scanner. While [installed] is false every scan fails the way the real one does without
 * its module; [install] then plays [installUpdates] and, if [installGate] is set, waits for it
 * before the last one so a test can look at the progress in between.
 */
class FakeScanner : BarcodeScanner {
    var next: ScanResult = ScanResult.Cancelled
    var installed = true
    var installUpdates: List<InstallUpdate> = listOf(InstallUpdate.Done)
    var installGate: CompletableDeferred<Unit>? = null
    var scans = 0

    override val isSupported: Boolean = true

    override suspend fun availability(): ScannerAvailability =
        if (installed) ScannerAvailability.READY else ScannerAvailability.NOT_INSTALLED

    override fun install(): Flow<InstallUpdate> = flow {
        installUpdates.forEachIndexed { index, update ->
            if (index == installUpdates.lastIndex) installGate?.await()
            if (update == InstallUpdate.Done) installed = true
            emit(update)
        }
    }

    override suspend fun scan(activityContext: Context): ScanResult {
        scans++
        return if (installed) next else ScanResult.Failed(ScanFailure.MODULE_NOT_READY, 200)
    }
}

class FakeProductSource : ProductSource {
    var answer: RemoteProduct = RemoteProduct.Unavailable
    val requested = mutableListOf<String>()
    override suspend fun fetch(barcode: String): RemoteProduct {
        requested += barcode
        return answer
    }
}
