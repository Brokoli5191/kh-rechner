package app.brokoli5191.khrechner

import android.content.Context
import app.brokoli5191.khrechner.data.AapsHandoff
import app.brokoli5191.khrechner.data.BarcodeScanner
import app.brokoli5191.khrechner.data.ProductSource
import app.brokoli5191.khrechner.data.RemoteProduct
import app.brokoli5191.khrechner.data.ScanResult

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

class FakeScanner : BarcodeScanner {
    var next: ScanResult = ScanResult.Cancelled
    override val isSupported: Boolean = true
    override fun requestModule() = Unit
    override suspend fun scan(activityContext: Context): ScanResult = next
}

class FakeProductSource : ProductSource {
    var answer: RemoteProduct = RemoteProduct.Unavailable
    val requested = mutableListOf<String>()
    override suspend fun fetch(barcode: String): RemoteProduct {
        requested += barcode
        return answer
    }
}
