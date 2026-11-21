package app.brokoli5191.khrechner.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brokoli5191.khrechner.FakeScanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ScannerControllerTest {

    private val scanner = FakeScanner()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val rejected = InstallFailure.ServicesError(8)

    private fun TestScope.controller() = ScannerController(scanner, backgroundScope)

    private fun test(body: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = body)

    @Test
    fun `a failed download stays on show until the module is there or the user retries`() = test {
        scanner.installed = false
        scanner.installUpdates = listOf(InstallUpdate.Failed(rejected))
        val controller = controller()
        controller.install()
        assertEquals(ScannerStatus.InstallFailed(rejected), controller.status.value)

        // Looking again finds it still missing; that must not wipe the reason.
        controller.refresh()
        assertEquals(ScannerStatus.InstallFailed(rejected), controller.status.value)

        scanner.installUpdates = listOf(InstallUpdate.Progress(40), InstallUpdate.Done)
        controller.install()
        assertEquals(ScannerStatus.Ready, controller.status.value)
    }

    @Test
    fun `a module that turns up on its own is noticed`() = test {
        scanner.installed = false
        scanner.installUpdates = listOf(InstallUpdate.Failed(rejected))
        val controller = controller()
        controller.install()

        scanner.installed = true
        controller.refresh()
        assertEquals(ScannerStatus.Ready, controller.status.value)
    }

    @Test
    fun `a scan that finds the module missing starts the download for next time`() = test {
        scanner.installed = false
        val controller = controller()
        assertEquals(ScanResult.Unavailable(moduleMissing = true), controller.scan(context))
        assertEquals(1, scanner.installs)
        assertEquals(ScannerStatus.Ready, controller.status.value)

        scanner.next = ScanResult.Code("4006381333931")
        assertEquals(ScanResult.Code("4006381333931"), controller.scan(context))
        assertEquals(1, scanner.installs)
    }

    @Test
    fun `a request play services has turned down is not repeated by every scan`() = test {
        scanner.installed = false
        scanner.installUpdates = listOf(InstallUpdate.Failed(rejected))
        val controller = controller()
        controller.install()

        controller.scan(context)
        controller.scan(context)
        assertEquals(1, scanner.installs)
        assertEquals(ScannerStatus.InstallFailed(rejected), controller.status.value)
    }

    @Test
    fun `without play services nothing is asked of google's scanner`() = test {
        scanner.supported = false
        val controller = controller()
        assertEquals(ScannerStatus.Unsupported, controller.status.value)
        assertEquals(ScanResult.Unavailable(moduleMissing = false), controller.scan(context))
        controller.install()
        controller.refresh()
        assertEquals(0, scanner.scans)
        assertEquals(0, scanner.installs)
        assertEquals(ScannerStatus.Unsupported, controller.status.value)
    }

    @Test
    fun `status codes are sorted into what they mean for the user`() {
        assertEquals(InstallFailure.MeteredNetwork, GmsBarcodeScanner.failureFor(46004))
        assertEquals(InstallFailure.NoStorage, GmsBarcodeScanner.failureFor(46003))
        for (code in listOf(46000, 46001, 46002)) assertEquals(InstallFailure.NotOffered, GmsBarcodeScanner.failureFor(code))
        for (code in listOf(7, 15)) assertEquals(InstallFailure.NoConnection, GmsBarcodeScanner.failureFor(code))
        for (code in listOf(17, 19, 20, 21, 22)) assertEquals(InstallFailure.NoAnswer, GmsBarcodeScanner.failureFor(code))
        // Everything else is Play services' own trouble; the number is all there is to say.
        assertEquals(InstallFailure.ServicesError(8), GmsBarcodeScanner.failureFor(8))
        assertEquals(InstallFailure.ServicesError(13), GmsBarcodeScanner.failureFor(13))
        assertEquals(InstallFailure.ServicesError(31337), GmsBarcodeScanner.failureFor(31337))
    }
}
