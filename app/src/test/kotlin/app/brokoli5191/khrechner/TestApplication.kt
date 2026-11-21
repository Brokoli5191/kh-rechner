package app.brokoli5191.khrechner

import android.content.Context
import app.brokoli5191.khrechner.data.BarcodeScanner
import app.brokoli5191.khrechner.data.ProductSource
import app.brokoli5191.khrechner.data.RemoteProduct
import app.brokoli5191.khrechner.data.ScanResult

/** The real app with its two outside dependencies (scanner, network) replaced by scripted fakes. */
class TestApplication : KhRechnerApplication() {

    val scanner = FakeScanner()
    val productSource = FakeProductSource()

    override fun createContainer(): AppContainer = AppContainer(this, scanner, productSource)
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
