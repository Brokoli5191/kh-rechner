package app.brokoli5191.khrechner.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brokoli5191.khrechner.BuildConfig
import app.brokoli5191.khrechner.TestApplication
import app.brokoli5191.khrechner.data.DownloadResult
import app.brokoli5191.khrechner.data.InstallFailure
import app.brokoli5191.khrechner.data.InstallResult
import app.brokoli5191.khrechner.data.InstallUpdate
import app.brokoli5191.khrechner.data.ProductPieces
import app.brokoli5191.khrechner.data.ReleaseInfo
import app.brokoli5191.khrechner.data.ReleaseLookup
import app.brokoli5191.khrechner.data.RemoteProduct
import app.brokoli5191.khrechner.data.ScanResult
import app.brokoli5191.khrechner.data.Settings
import app.brokoli5191.khrechner.data.ThemeMode
import app.brokoli5191.khrechner.data.UpdateFailure
import app.brokoli5191.khrechner.ui.theme.KhRechnerTheme
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives the real app (view models, DataStore, navigation) on the JVM. German locale, so the
 * decimal comma and the default strings are what gets exercised.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class, qualifiers = "de-rAT-w411dp-h891dp-xhdpi")
class AppFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: TestApplication get() = ApplicationProvider.getApplicationContext()

    @Before
    fun launch() {
        val container = app.container
        compose.setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
            KhRechnerTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                KhRechnerApp(container = container, settings = settings)
            }
        }
    }

    /** A tile by its name: the visible label, or the spoken one where the label is abbreviated. */
    private fun field(name: String) = compose.onNode(hasSetTextAction() and (hasText(name) or hasContentDescription(name)))

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Lets the update indicator's second run out; Robolectric's clock only moves when told to. */
    private fun passIndicator() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))

    private fun grantCamera() = shadowOf(app as Application).grantPermissions(Manifest.permission.CAMERA)

    private fun scan() = compose.onNodeWithContentDescription("barcode scannen").performClick()

    /** Waits until the built-in scanner shows its picture, or until it is gone again. */
    private fun awaitCamera(open: Boolean = true) {
        compose.waitUntil(5_000) { app.camera.isOpen == open }
    }

    private fun awaitResult(description: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `grams entered directly give a result without weight or pieces`() {
        // Nothing to take a percentage or a piece count of yet.
        // (A disabled field offers no text action, so it is looked up by its label alone.)
        compose.onNodeWithContentDescription("gegessen in prozent").assertIsNotEnabled()
        compose.onNodeWithContentDescription("gegessen in stücken").assertIsNotEnabled()
        compose.onNodeWithText("gramm pro stück").assertIsNotEnabled()
        field("gegessen in g").assertIsEnabled()
        compose.onNodeWithText("speichern").assertIsNotEnabled()
        compose.onNodeWithContentDescription("noch kein ergebnis").assertExists()

        field("kh pro 100 g").performTextInput("5.3")
        field("gegessen in g").performScrollTo().performTextInput("30")

        awaitResult("ungefähr 1,6 gramm kohlenhydrate")
        // (The number itself is drawn one rolling character at a time; TalkBack gets the sentence above.)
        compose.onNodeWithText("speichern").assertIsEnabled()
    }

    @Test
    fun `the last edited eaten field drives the other two`() {
        field("kh pro 100 g").performTextInput("10")
        field("gesamtgewicht").performTextInput("200")
        field("anzahl der stücke").performTextInput("4")
        field("gegessen in g").performScrollTo().performTextInput("75")

        awaitResult("7,5 gramm kohlenhydrate")
        compose.onNodeWithText("gramm pro stück").assertTextContains("50")
        field("gegessen in prozent").assertIsEnabled().assertTextContains("37,5")
        field("gegessen in stücken").assertIsEnabled().assertTextContains("1,5")

        // Editing a derived field makes it the source; grams become derived.
        field("gegessen in stücken").performTextReplacement("3")
        awaitResult("15 gramm kohlenhydrate")
        field("gegessen in g").assertTextContains("150")
        field("gegessen in prozent").assertTextContains("75")

        // More than there is (one and a half packages scanned once) counts, with a note that says so.
        field("gegessen in prozent").performTextReplacement("150")
        awaitResult("30 gramm kohlenhydrate")
        compose.onNodeWithText("mehr als eine packung: 1,5 packungen").assertExists()
        field("gegessen in stücken").assertTextContains("6")
        compose.onNodeWithText("speichern").assertIsEnabled()

        field("gegessen in prozent").performTextReplacement("100")
        compose.onNode(hasText("mehr als eine packung", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `input is sanitised instead of rejected wholesale`() {
        field("kh pro 100 g").performTextInput("5,3")
        field("kh pro 100 g").assertTextContains("5,3")
        // A second separator, letters and a minus sign are refused; the field keeps its value.
        field("kh pro 100 g").performTextInput(",")
        field("kh pro 100 g").performTextInput("x")
        field("kh pro 100 g").performTextInput("-")
        field("kh pro 100 g").assertTextContains("5,3")
        field("anzahl der stücke").performTextInput("2,5")
        field("anzahl der stücke").assertTextContains("")
    }

    @Test
    fun `save, find it under gespeichert, load it back, reset and undo`() {
        field("kh pro 100 g").performTextInput("12")
        field("gesamtgewicht").performTextInput("250")
        field("gegessen in prozent").performScrollTo().performTextInput("40")
        awaitResult("12 gramm kohlenhydrate")

        compose.onNodeWithText("speichern").performClick()
        compose.onNodeWithText("berechnung speichern").assertExists()
        compose.onNode(hasSetTextAction() and hasText("name, z. b. apfel")).performTextInput("müsli")
        compose.onNodeWithText("0 / 60").assertDoesNotExist()
        compose.onNodeWithText("5 / 60").assertExists()
        // The dialog's confirm button is the second "speichern" on screen now.
        compose.onAllNodes(hasText("speichern"))[1].performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("gespeichert")).fetchSemanticsNodes().size > 1 }

        compose.onNodeWithText("zurücksetzen").performClick()
        awaitResult("noch kein ergebnis")
        compose.onNodeWithText("rückgängig").performClick()
        awaitResult("12 gramm kohlenhydrate")
        compose.onNodeWithText("zurücksetzen").performClick()
        awaitResult("noch kein ergebnis")

        compose.onAllNodes(hasText("gespeichert"))[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("müsli")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("12 g").assertExists()

        compose.onNodeWithText("müsli").performClick()
        awaitResult("12 gramm kohlenhydrate")
        field("gegessen in prozent").assertTextContains("40")
        field("gegessen in g").assertTextContains("100")
    }

    @Test
    fun `a scanned product fills in carbs, package weight and piece count`() {
        app.scanner.next = ScanResult.Code("4017100291005")
        app.productSource.answer = RemoteProduct.Found("Choco Leibniz", "58", "125", ProductPieces(9, estimated = true))

        scan()
        awaitText("Choco Leibniz")
        field("kh pro 100 g").assertTextContains("58")
        field("gesamtgewicht").assertTextContains("125")
        field("anzahl der stücke").assertTextContains("9")
        // The count is a guess from the serving size, and the screen says what it assumes.
        awaitText("stückzahl geschätzt, bitte prüfen: 1 stück ≈ 13,9 g")

        field("gegessen in stücken").performScrollTo().performTextInput("2")
        awaitResult("ungefähr 16,1 gramm kohlenhydrate")

        // Once the user sets the count, it is theirs and the note goes.
        field("anzahl der stücke").performTextReplacement("10")
        compose.onNode(hasText("geschätzt", substring = true)).assertDoesNotExist()

        // Typing over the carb value detaches it from the product; weight and count stay.
        field("kh pro 100 g").performTextReplacement("50")
        compose.onNodeWithText("Choco Leibniz").assertDoesNotExist()
        field("gesamtgewicht").assertTextContains("125")

        // Same barcode again with the network gone: answered from the cache, no second request.
        app.productSource.answer = RemoteProduct.Unavailable
        scan()
        awaitText("Choco Leibniz")
        field("kh pro 100 g").assertTextContains("58")
        field("anzahl der stücke").assertTextContains("9")
        assertEquals(listOf("4017100291005"), app.productSource.requested)
    }

    @Test
    fun `a label that states the piece count needs no note`() {
        app.scanner.next = ScanResult.Code("4000000000002")
        app.productSource.answer = RemoteProduct.Found("riegel", "60", "150", ProductPieces(6, estimated = false))
        scan()
        awaitText("riegel")
        field("anzahl der stücke").assertTextContains("6")
        compose.onNode(hasText("geschätzt", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `the next scan does not inherit weight and pieces from the last product`() {
        app.scanner.next = ScanResult.Code("4000000000002")
        app.productSource.answer = RemoteProduct.Found("riegel", "60", "150", ProductPieces(6, estimated = false))
        scan()
        awaitText("riegel")

        // A product Open Food Facts knows only the carbs of.
        app.scanner.next = ScanResult.Code("4000000000003")
        app.productSource.answer = RemoteProduct.Found("marmelade", "55", null)
        scan()
        awaitText("marmelade")
        field("kh pro 100 g").assertTextContains("55")
        field("gesamtgewicht").assert(hasText("150").not())
        field("anzahl der stücke").assert(hasText("6").not())

        // What the user typed is theirs and survives a scan that knows no better.
        field("gesamtgewicht").performTextInput("340")
        app.scanner.next = ScanResult.Code("4000000000004")
        app.productSource.answer = RemoteProduct.Found("honig", "80", null)
        scan()
        awaitText("honig")
        field("gesamtgewicht").assertTextContains("340")
    }

    @Test
    fun `scan problems show a message and leave the field usable`() {
        app.scanner.next = ScanResult.Code("4000000000001")

        app.productSource.answer = RemoteProduct.NotFound
        compose.onNodeWithContentDescription("barcode scannen").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("produkt nicht gefunden", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        app.productSource.answer = RemoteProduct.NoCarbs("Wasser")
        compose.onNodeWithContentDescription("barcode scannen").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("für „Wasser“ ist kein kh-wert hinterlegt.", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        app.productSource.answer = RemoteProduct.Unavailable
        compose.onNodeWithContentDescription("barcode scannen").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("keine verbindung", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        // The calculator never waits on the network: typing still works and clears the message.
        field("kh pro 100 g").performTextInput("12")
        compose.onNode(hasText("keine verbindung", substring = true)).assertDoesNotExist()
        field("gegessen in g").performScrollTo().performTextInput("50")
        awaitResult("6 gramm kohlenhydrate")
    }

    @Test
    fun `settings apply at once, on their own screen and on the calculator`() {
        compose.onNodeWithText("einstellungen").performClick()
        compose.onNodeWithText("aus").performScrollTo().assertIsOn()
        compose.onNodeWithText("ke").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("ke") and isOn()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("aus").assertIsOff()
        compose.onNodeWithText("dunkel").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("dunkel") and isOn()).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("rechnen").performClick()
        field("kh pro 100 g").performTextInput("5,3")
        field("gegessen in g").performScrollTo().performTextInput("30")
        awaitResult("ungefähr 1,6 gramm kohlenhydrate")
        compose.onNodeWithText("≈ 0,2 ke", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `where google's scanner cannot run, the built-in one reads the code`() {
        grantCamera()
        app.scanner.installed = false
        app.productSource.answer = RemoteProduct.Found("Nutella", "57.5", "400")

        scan()
        awaitCamera()
        awaitText("barcode in den rahmen halten")
        compose.runOnIdle { app.camera.see("3017624010701") }

        // What was read is shown for a moment, then the scanner gets out of the way.
        awaitText("3017624010701")
        awaitText("Nutella")
        awaitCamera(open = false)
        field("kh pro 100 g").assertTextContains("57,5")
        assertEquals(listOf("3017624010701"), app.productSource.requested)
    }

    @Test
    fun `google's scanner is fetched on the side and used from then on`() {
        grantCamera()
        app.scanner.installed = false
        scan()
        awaitCamera()
        compose.onNodeWithContentDescription("scanner schließen").performClick()
        awaitCamera(open = false)
        assertEquals(2, app.scanner.installs) // once at launch, once because the scan found it missing

        // The download has finished in the meantime.
        app.scanner.next = ScanResult.Code("3017624010701")
        app.productSource.answer = RemoteProduct.Found("Nutella", "57.5", "400")
        scan()
        awaitText("Nutella")
        assertFalse(app.camera.isOpen)
    }

    @Test
    fun `without play services the built-in scanner opens straight away`() {
        grantCamera()
        app.scanner.supported = false
        scan()
        awaitCamera()
        assertEquals(0, app.scanner.scans)

        // Back closes the scanner, not the app.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitCamera(open = false)
        field("kh pro 100 g").performTextInput("12")
        field("kh pro 100 g").assertTextContains("12")
    }

    @Test
    fun `the built-in scanner asks for the camera and says what it needs when refused`() {
        app.scanner.installed = false
        scan()
        compose.waitForIdle()
        val request = shadowOf(compose.activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.CAMERA), request.requestedPermissions.toList())
        assertFalse(app.camera.isOpen)

        // Deprecated for apps, but it is how the system hands the answer to the activity.
        @Suppress("DEPRECATION")
        compose.runOnIdle {
            compose.activity.onRequestPermissionsResult(
                request.requestCode,
                arrayOf(Manifest.permission.CAMERA),
                intArrayOf(PackageManager.PERMISSION_DENIED),
            )
        }
        awaitText("ohne kamera kein scan")
        assertFalse(app.camera.isOpen)

        // Android no longer shows its dialog here, so the button leads to the app's settings page.
        compose.onNodeWithText("kamera erlauben").performClick()
        compose.waitForIdle()
        assertEquals(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(app as Application).nextStartedActivity.action)

        // Granted over there; coming back is enough.
        grantCamera()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        awaitCamera()
        awaitText("barcode in den rahmen halten")
    }

    @Test
    fun `the light can be switched, and a camera that does not open says so`() {
        grantCamera()
        app.scanner.installed = false
        scan()
        awaitCamera()
        compose.onNodeWithContentDescription("licht einschalten").performClick()
        compose.onNodeWithContentDescription("licht ausschalten").assertExists()
        compose.runOnIdle { assertTrue(app.camera.torch) }

        compose.runOnIdle { app.camera.fail(IllegalStateException("camera in use")) }
        awaitText("die kamera lässt sich nicht öffnen")
        compose.onNodeWithText("IllegalStateException: camera in use", substring = true).assertExists()
        compose.onNodeWithText("scanner schließen").performClick()
        awaitCamera(open = false)
        compose.onNodeWithText("die kamera lässt sich nicht öffnen").assertDoesNotExist()
    }

    @Test
    fun `the about section says which scanner is in use and why`() {
        app.scanner.installed = false
        app.scanner.installUpdates = listOf(InstallUpdate.Failed(InstallFailure.ServicesError(8)))
        compose.onNodeWithText("einstellungen").performClick()
        compose.onNodeWithText("barcode-scanner").performScrollTo()
        awaitText("die app scannt selbst. der google-scanner ist noch nicht geladen.")

        compose.onNodeWithText("laden").performClick()
        // Only the reason: no status text, no version, no stack trace, no repeat of what still works.
        awaitText("google-dienste melden fehler 8.")
        compose.onNodeWithText("google-dienste melden fehler 8.").assertExists()
        compose.onNode(hasText("die app scannt selbst", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("INTERNAL_ERROR", substring = true)).assertDoesNotExist()

        app.scanner.installUpdates = listOf(InstallUpdate.Failed(InstallFailure.NoAnswer))
        compose.onNodeWithText("nochmal").performScrollTo().performClick()
        awaitText("google-dienste antworten nicht.")
        compose.onNodeWithText("google-dienste antworten nicht.").assertExists()

        app.scanner.installUpdates = listOf(InstallUpdate.Done)
        compose.onNodeWithText("nochmal").performScrollTo().performClick()
        awaitText("google-scanner bereit")
        compose.onNodeWithText("nochmal").assertDoesNotExist()
        compose.onNode(hasText("die app scannt selbst", substring = true)).assertDoesNotExist()
    }

    private val newer = ReleaseInfo(
        version = "9.1.0",
        apkUrl = "https://github.com/Brokoli5191/kh-rechner/releases/download/v9.1.0/kh-rechner-9.1.0.apk",
        apkSize = 4_400_000,
        sha256 = null,
        pageUrl = "https://github.com/Brokoli5191/kh-rechner/releases/tag/v9.1.0",
    )

    @Test
    fun `looking for updates shows the indicator for a second and asks github once a minute at most`() {
        compose.onNodeWithText("einstellungen").performClick()
        compose.onNodeWithText("updates").performScrollTo()
        awaitText("noch nicht gesucht")

        // The answer is there at once, the indicator stays anyway.
        compose.onNodeWithText("suchen").performScrollTo().performClick()
        awaitText("suche nach updates …")
        passIndicator()
        awaitText("kh rechner ist aktuell")
        awaitText("heute geprüft")
        compose.onNodeWithContentDescription("update verfügbar").assertDoesNotExist()

        // Pressed again right away: the same indicator, the same answer, no second request.
        compose.onNodeWithText("suchen").performScrollTo().performClick()
        awaitText("suche nach updates …")
        passIndicator()
        awaitText("kh rechner ist aktuell")
        assertEquals(1, app.releases.calls)
    }

    @Test
    fun `the update section finds a newer version, marks the tab and installs it on request`() {
        app.releases.answer = ReleaseLookup.Found(newer)
        compose.onNodeWithText("einstellungen").performClick()
        compose.onNodeWithText("updates").performScrollTo()
        awaitText("noch nicht gesucht")
        compose.onNodeWithContentDescription("update verfügbar").assertDoesNotExist()

        compose.onNodeWithText("suchen").performScrollTo().performClick()
        passIndicator()
        awaitText("version 9.1.0 ist da")
        compose.onNodeWithText("was ist neu").assertExists()
        compose.onNodeWithContentDescription("update verfügbar").assertExists()

        val gate = CompletableDeferred<Unit>()
        app.updater.gate = gate
        compose.onNodeWithText("installieren").performScrollTo().performClick()
        awaitText("wird geladen … 40 %")
        gate.complete(Unit)
        awaitText("wird installiert …")
        assertEquals(listOf(newer), app.updater.downloaded)
        assertEquals(1, app.updater.installed.size)

        // Android's dialog, closed with "cancel": the offer is back.
        compose.runOnIdle {
            app.container.updates.onInstallResult(InstallResult.Cancelled)
        }
        awaitText("installieren")
    }

    @Test
    fun `a failed update says why in one sentence and offers to try again`() {
        app.releases.answer = ReleaseLookup.Found(newer)
        app.updater.downloadResult = DownloadResult.Failed(UpdateFailure.WRONG_SIGNATURE)
        compose.onNodeWithText("einstellungen").performClick()
        compose.onNodeWithText("updates").performScrollTo()
        compose.onNodeWithText("suchen").performScrollTo().performClick()
        passIndicator()
        awaitText("version 9.1.0 ist da")
        compose.onNodeWithText("installieren").performScrollTo().performClick()
        awaitText("das update ist anders signiert als die installierte app.")

        app.updater.downloadResult = DownloadResult.Ready(java.io.File("ok.apk"))
        compose.onNodeWithText("nochmal").performScrollTo().performClick()
        awaitText("wird installiert …")
    }

    @Test
    fun `looking on opening can be switched off`() {
        compose.onNodeWithText("einstellungen").performClick()
        compose.onNodeWithText("beim öffnen suchen").performScrollTo().performClick()
        // The setting is written on a background thread; let the click land before asking for it.
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            kotlinx.coroutines.runBlocking { !app.container.settings.settings.first().checkForUpdates }
        }
        app.releases.answer = ReleaseLookup.Found(newer)
        compose.runOnIdle { app.container.updates.checkIfDue() }
        compose.waitForIdle()
        assertEquals(0, app.releases.calls)
    }

    @Test
    fun `the about section leads to the libraries and back`() {
        compose.onNodeWithText("einstellungen").performClick()
        compose.onNodeWithText("verwendete bibliotheken").performScrollTo()
        compose.onNodeWithText("version " + BuildConfig.VERSION_NAME).assertExists()
        compose.onNodeWithText("quellcode").assertExists()
        compose.onNodeWithText("verwendete bibliotheken").performClick()

        awaitText("androidx.activity")
        // Still under the settings tab, with a way back.
        compose.onNodeWithContentDescription("zurück").performClick()
        awaitText("darstellung")
        compose.onNodeWithText("androidx.activity").assertDoesNotExist()
    }
}
