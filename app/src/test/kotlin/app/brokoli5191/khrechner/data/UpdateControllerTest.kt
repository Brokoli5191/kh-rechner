package app.brokoli5191.khrechner.data

import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brokoli5191.khrechner.FakeReleases
import app.brokoli5191.khrechner.FakeUpdater
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class UpdateControllerTest {

    private val vienna = ZoneId.of("Europe/Vienna")
    private var clock = ZonedDateTime.of(2026, 11, 21, 9, 0, 0, 0, vienna).toInstant().toEpochMilli()
    private val releases = FakeReleases()
    private val updater = FakeUpdater()
    private val store = InMemoryDataStore(UpdateRecord())
    private val settings = MutableStateFlow(Settings())

    private fun release(version: String) = ReleaseInfo(version, "https://github.com/x/$version.apk", 4_400_000, null, "https://github.com/x/v$version")

    private fun TestScope.controller(installed: String = "1.3.0") =
        UpdateController(releases, updater, store, settings, installed, backgroundScope, now = { clock }, zone = { vienna })

    private fun test(body: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = body)

    private fun hours(count: Int) = count * 3_600_000L

    /** Lets the indicator's second run out, and with it everything waiting on it. */
    private fun TestScope.settle() {
        advanceTimeBy(2.seconds)
        runCurrent()
    }

    @Test
    fun `opening the app looks once per calendar day`() = test {
        releases.answer = ReleaseLookup.Found(release("1.3.0"))
        val updates = controller()
        updates.checkIfDue()
        updates.checkIfDue()
        settle()
        assertEquals(1, releases.calls)
        assertEquals(UpdateStatus.UpToDate(clock), updates.status.value)

        // Later the same day: nothing. After midnight in Vienna: again.
        clock += hours(14)
        updates.checkIfDue()
        assertEquals(1, releases.calls)
        clock += hours(2)
        updates.checkIfDue()
        settle()
        assertEquals(2, releases.calls)
    }

    @Test
    fun `switched off, opening the app asks nobody, but the button still does`() = test {
        settings.value = Settings(checkForUpdates = false)
        releases.answer = ReleaseLookup.Found(release("1.4.0"))
        val updates = controller()
        updates.checkIfDue()
        assertEquals(0, releases.calls)
        assertEquals(UpdateStatus.Unknown, updates.status.value)

        updates.checkNow()
        settle()
        assertEquals(UpdateStatus.Available(release("1.4.0")), updates.status.value)
    }

    @Test
    fun `a newer release found once is still there after a restart, and gone once installed`() = test {
        releases.answer = ReleaseLookup.Found(release("1.4.0"))
        controller().checkIfDue()
        settle()
        assertEquals(UpdateStatus.Available(release("1.4.0")), controller().status.value)

        // The same record read by the updated app: nothing newer than itself.
        assertEquals(UpdateStatus.UpToDate(clock), controller(installed = "1.4.0").status.value)
    }

    @Test
    fun `a failed look on opening stays quiet and is tried again next time, a failed button press says why`() = test {
        releases.answer = ReleaseLookup.Failed(UpdateFailure.NO_CONNECTION)
        val updates = controller()
        updates.checkIfDue()
        settle()
        assertEquals(UpdateStatus.Unknown, updates.status.value)
        updates.checkIfDue()
        settle()
        assertEquals(2, releases.calls)

        updates.checkNow()
        settle()
        assertEquals(UpdateStatus.Failed(UpdateFailure.NO_CONNECTION, null), updates.status.value)
    }

    @Test
    fun `installing downloads with progress and hands the file to android`() = test {
        releases.answer = ReleaseLookup.Found(release("1.4.0"))
        val gate = CompletableDeferred<Unit>()
        updater.gate = gate
        val updates = controller()
        updates.checkNow()
        settle()
        updates.install()
        assertEquals(UpdateStatus.Downloading(release("1.4.0"), 40), updates.status.value)

        gate.complete(Unit)
        assertEquals(UpdateStatus.Installing(release("1.4.0")), updates.status.value)
        assertEquals(listOf(File("kh-rechner-update.apk")), updater.installed)

        // Android wants a confirmation: it is passed on to the screen.
        val dialog = Intent("android.content.pm.action.CONFIRM_INSTALL")
        updates.onInstallResult(UpdateInstallReceiver.resultFor(PackageInstaller.STATUS_PENDING_USER_ACTION, dialog))
        assertEquals(dialog, updates.confirmations.first())

        // The user says no: back to the offer.
        updates.onInstallResult(UpdateInstallReceiver.resultFor(PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertEquals(UpdateStatus.Available(release("1.4.0")), updates.status.value)
    }

    @Test
    fun `a failed download or installation says why and can be retried`() = test {
        releases.answer = ReleaseLookup.Found(release("1.4.0"))
        val updates = controller()
        updates.checkNow()
        settle()
        updater.downloadResult = DownloadResult.Failed(UpdateFailure.WRONG_SIGNATURE)
        updates.install()
        assertEquals(UpdateStatus.Failed(UpdateFailure.WRONG_SIGNATURE, release("1.4.0")), updates.status.value)
        assertTrue(updater.installed.isEmpty())

        updater.downloadResult = DownloadResult.Ready(File("ok.apk"))
        updates.install()
        updates.onInstallResult(UpdateInstallReceiver.resultFor(PackageInstaller.STATUS_FAILURE_STORAGE, null))
        assertEquals(UpdateStatus.Failed(UpdateFailure.NO_STORAGE, release("1.4.0")), updates.status.value)
        assertEquals(2, updater.downloaded.size)
    }

    @Test
    fun `the indicator stays a second, however fast github answers`() = test {
        releases.answer = ReleaseLookup.Found(release("1.3.0"))
        val updates = controller()
        updates.checkNow()
        assertEquals(1, releases.calls)
        advanceTimeBy(999.milliseconds)
        runCurrent()
        assertEquals(UpdateStatus.Checking, updates.status.value)
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(UpdateStatus.UpToDate(clock), updates.status.value)
    }

    @Test
    fun `pressing again within a minute shows the same answer without asking github`() = test {
        releases.answer = ReleaseLookup.Found(release("1.3.0"))
        val updates = controller()
        updates.checkNow()
        settle()
        val checked = clock

        // GitHub would still hand out its cached answer, whatever was published meanwhile.
        releases.answer = ReleaseLookup.Found(release("1.4.0"))
        clock += 59_000
        updates.checkNow()
        assertEquals(UpdateStatus.Checking, updates.status.value)
        settle()
        assertEquals(1, releases.calls)
        assertEquals(UpdateStatus.UpToDate(checked), updates.status.value)

        clock += 1_000
        updates.checkNow()
        settle()
        assertEquals(2, releases.calls)
        assertEquals(UpdateStatus.Available(release("1.4.0")), updates.status.value)
    }

    @Test
    fun `android's answers are sorted into what the user can do about them`() {
        assertEquals(InstallResult.Done, UpdateInstallReceiver.resultFor(PackageInstaller.STATUS_SUCCESS, null))
        assertEquals(InstallResult.Cancelled, UpdateInstallReceiver.resultFor(PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertEquals(InstallResult.Failed(UpdateFailure.WRONG_SIGNATURE), UpdateInstallReceiver.resultFor(PackageInstaller.STATUS_FAILURE_CONFLICT, null))
        assertEquals(InstallResult.Failed(UpdateFailure.BLOCKED), UpdateInstallReceiver.resultFor(PackageInstaller.STATUS_FAILURE_BLOCKED, null))
        assertEquals(InstallResult.Failed(UpdateFailure.DAMAGED), UpdateInstallReceiver.resultFor(PackageInstaller.STATUS_FAILURE_INVALID, null))
        assertEquals(InstallResult.Failed(UpdateFailure.INSTALL_FAILED), UpdateInstallReceiver.resultFor(PackageInstaller.STATUS_FAILURE, null))
        // A confirmation without its dialog cannot be shown.
        assertEquals(InstallResult.Failed(UpdateFailure.INSTALL_FAILED), UpdateInstallReceiver.resultFor(PackageInstaller.STATUS_PENDING_USER_ACTION, null))
    }
}
