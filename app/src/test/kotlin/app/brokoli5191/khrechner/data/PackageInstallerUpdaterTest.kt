package app.brokoli5191.khrechner.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** The hand-over to Android's installer, against Robolectric's stand-in for it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PackageInstallerUpdaterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the apk goes into one installer session for this app and is deleted afterwards`() = runTest {
        val apk = File(context.cacheDir, "test.apk").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val updater = PackageInstallerUpdater(context, "test", installedVersionCode = 1, io = UnconfinedTestDispatcher(testScheduler))

        updater.install(apk)

        val sessions = context.packageManager.packageInstaller.allSessions
        assertEquals(1, sessions.size)
        assertEquals(context.packageName, sessions.single().appPackageName)
        assertFalse(apk.exists())
    }
}
