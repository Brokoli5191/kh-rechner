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
    private val rejected = InstallFailure.Rejected("8 INTERNAL_ERROR")

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
    fun `play services errors are quoted with their number and name`() {
        assertEquals("8 INTERNAL_ERROR", GmsBarcodeScanner.statusText(8))
        assertEquals("46000 UNKNOWN_MODULE", GmsBarcodeScanner.statusText(46000))
        assertEquals("8 INTERNAL_ERROR: module broker died", GmsBarcodeScanner.statusText(8, " module broker died "))
        assertEquals("8 INTERNAL_ERROR", GmsBarcodeScanner.statusText(8, ""))
        // A code Play services has no name for.
        assertEquals("31337", GmsBarcodeScanner.statusText(31337))
    }

    @Test
    fun `errors that never reached play services are quoted by class and message`() {
        assertEquals("IllegalStateException: no binder", GmsBarcodeScanner.errorText(IllegalStateException("no binder")))
        assertEquals("UnsupportedOperationException", GmsBarcodeScanner.errorText(UnsupportedOperationException()))
    }
}
