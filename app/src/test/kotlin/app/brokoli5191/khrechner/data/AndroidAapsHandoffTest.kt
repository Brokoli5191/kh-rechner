package app.brokoli5191.khrechner.data

import android.app.Application
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brokoli5191.khrechner.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real hand-off against Android's package manager, clipboard and activity start. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class AndroidAapsHandoffTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val handoff = AndroidAapsHandoff(application)

    private val clipboardText: String?
        get() = application.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private fun installAaps() {
        val packages = shadowOf(application.packageManager)
        packages.installPackage(PackageInfo().apply { packageName = AndroidAapsHandoff.PACKAGE })
        val launcher = ComponentName(AndroidAapsHandoff.PACKAGE, "app.aaps.ComposeMainActivity")
        packages.addActivityIfNotPresent(launcher)
        packages.addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    @Test
    fun `aaps is found by the application id of its full flavour`() {
        assertEquals("info.nightscout.androidaps", AndroidAapsHandoff.PACKAGE)
        assertFalse(handoff.isInstalled())
        installAaps()
        assertTrue(handoff.isInstalled())
    }

    @Test
    fun `the amount goes to the clipboard as a bare number and aaps is opened`() {
        installAaps()
        assertTrue(handoff.copyAndOpen(13))

        assertEquals("13", clipboardText)
        val started = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, started.action)
        assertEquals(AndroidAapsHandoff.PACKAGE, started.component?.packageName ?: started.`package`)
        // Only a plain launch: no extras, no data, nothing AAPS could act on by itself.
        assertNull(started.data)
        assertTrue(started.extras == null || started.extras!!.isEmpty)
    }

    @Test
    fun `without aaps the amount is still copied and the failure is reported`() {
        assertFalse(handoff.copyAndOpen(7))
        assertEquals("7", clipboardText)
        assertNull(shadowOf(application).nextStartedActivity)
    }
}
