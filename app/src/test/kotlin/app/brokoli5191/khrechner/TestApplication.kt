package app.brokoli5191.khrechner

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import app.brokoli5191.khrechner.data.AapsHandoff
import app.brokoli5191.khrechner.data.AppUpdater
import app.brokoli5191.khrechner.data.BarcodeScanner
import app.brokoli5191.khrechner.data.DownloadResult
import app.brokoli5191.khrechner.data.InstallUpdate
import app.brokoli5191.khrechner.data.ProductSource
import app.brokoli5191.khrechner.data.ReleaseInfo
import app.brokoli5191.khrechner.data.ReleaseLookup
import app.brokoli5191.khrechner.data.ReleaseSource
import app.brokoli5191.khrechner.data.RemoteProduct
import app.brokoli5191.khrechner.data.ScanResult
import app.brokoli5191.khrechner.data.ScannerAvailability
import app.brokoli5191.khrechner.ui.scanner.CameraEvent
import app.brokoli5191.khrechner.ui.scanner.CameraScanner
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The real app with its outside dependencies (both scanners, network, AAPS) replaced by scripted fakes. */
class TestApplication : KhRechnerApplication() {

    val scanner = FakeScanner()
    val productSource = FakeProductSource()
    val aaps = FakeAaps()
    val camera = FakeCamera()
    val releases = FakeReleases()
    val updater = FakeUpdater()

    override fun createContainer(): AppContainer = AppContainer(this, scanner, productSource, aaps, camera, releases, updater)
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
 * Scripted stand-in for Google's scanner. While [installed] is false every scan fails the way the
 * real one does without its module; [install] then plays [installUpdates] and, if [installGate]
 * is set, waits for it before the last one so a test can look at the progress in between.
 */
class FakeScanner : BarcodeScanner {
    var next: ScanResult = ScanResult.Cancelled
    var supported = true
    var installed = true
    var installUpdates: List<InstallUpdate> = listOf(InstallUpdate.Done)
    var installGate: CompletableDeferred<Unit>? = null
    var scans = 0
    var installs = 0

    override val isSupported: Boolean get() = supported

    override suspend fun availability(): ScannerAvailability =
        if (installed) ScannerAvailability.READY else ScannerAvailability.NOT_INSTALLED

    override fun install(): Flow<InstallUpdate> = flow {
        installs++
        installUpdates.forEachIndexed { index, update ->
            if (index == installUpdates.lastIndex) installGate?.await()
            if (update == InstallUpdate.Done) installed = true
            emit(update)
        }
    }

    override suspend fun scan(activityContext: Context): ScanResult {
        scans++
        return if (installed) next else ScanResult.Unavailable(moduleMissing = true)
    }
}

/**
 * Stands in for the camera of the built-in scanner. The viewfinder shows [picture] and "sees"
 * whatever a test tells it to with [see].
 */
class FakeCamera : CameraScanner {
    override var isAvailable = true
    var hasTorch = true

    /** What the app last asked of the torch. */
    var torch = false
        private set

    /** Drawn where the live picture would be. */
    var picture: @Composable (Modifier) -> Unit = { modifier -> Box(modifier) }

    private var report: ((CameraEvent) -> Unit)? = null

    /** True while the viewfinder is on screen. */
    val isOpen: Boolean get() = report != null

    fun see(barcode: String) = checkNotNull(report) { "the viewfinder is not on screen" }(CameraEvent.Code(barcode))

    fun fail(error: Exception) = checkNotNull(report) { "the viewfinder is not on screen" }(CameraEvent.Failed(error))

    @Composable
    override fun Viewfinder(torch: Boolean, onEvent: (CameraEvent) -> Unit, modifier: Modifier) {
        val currentOnEvent by rememberUpdatedState(onEvent)
        SideEffect { this.torch = torch }
        DisposableEffect(Unit) {
            report = { event -> currentOnEvent(event) }
            currentOnEvent(CameraEvent.Started(hasTorch))
            onDispose { report = null }
        }
        picture(modifier)
    }
}

/** GitHub as a test wants it: no release unless [answer] says otherwise. */
class FakeReleases : ReleaseSource {
    var answer: ReleaseLookup = ReleaseLookup.NoRelease
    var calls = 0
    override suspend fun latest(): ReleaseLookup {
        calls++
        return answer
    }
}

/**
 * Stands in for download and installer. A download reports 40 % and, if [gate] is set, waits for
 * it before it ends with [downloadResult].
 */
class FakeUpdater : AppUpdater {
    var downloadResult: DownloadResult = DownloadResult.Ready(File("kh-rechner-update.apk"))
    var gate: CompletableDeferred<Unit>? = null
    val downloaded = mutableListOf<ReleaseInfo>()
    val installed = mutableListOf<File>()

    override suspend fun download(release: ReleaseInfo, progress: (Int?) -> Unit): DownloadResult {
        downloaded += release
        progress(null)
        progress(40)
        gate?.await()
        return downloadResult
    }

    override suspend fun install(apk: File) {
        installed += apk
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
