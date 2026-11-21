package app.brokoli5191.khrechner.ui

import android.Manifest
import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brokoli5191.khrechner.TestApplication
import app.brokoli5191.khrechner.data.InstallFailure
import app.brokoli5191.khrechner.data.InstallUpdate
import app.brokoli5191.khrechner.data.ProductPieces
import app.brokoli5191.khrechner.data.ReleaseInfo
import app.brokoli5191.khrechner.data.ReleaseLookup
import app.brokoli5191.khrechner.data.RemoteProduct
import app.brokoli5191.khrechner.data.ScanResult
import app.brokoli5191.khrechner.data.Settings
import app.brokoli5191.khrechner.data.ThemeMode
import app.brokoli5191.khrechner.domain.CalcInput
import app.brokoli5191.khrechner.domain.CarbUnit
import app.brokoli5191.khrechner.domain.EatenSource
import app.brokoli5191.khrechner.ui.scanner.CameraPermission
import app.brokoli5191.khrechner.ui.scanner.ScannerContent
import app.brokoli5191.khrechner.ui.scanner.ScannerPhase
import app.brokoli5191.khrechner.ui.theme.KhRechnerTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen of the real app, with the fallback (brand) colours unless noted. Run with
 * `-Pscreenshots` to write the images the README shows; without it this is a render smoke test.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApplication::class, qualifiers = "de-rAT-w411dp-h891dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: TestApplication get() = ApplicationProvider.getApplicationContext()

    private fun launch(themeMode: ThemeMode = ThemeMode.LIGHT, dynamicColor: Boolean = false) {
        val container = app.container
        compose.setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
            KhRechnerTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                KhRechnerApp(container = container, settings = settings)
            }
        }
    }

    /** A tile by its name: the visible label, or the spoken one where the label is abbreviated. */
    private fun field(name: String) = compose.onNode(hasSetTextAction() and (hasText(name) or hasContentDescription(name)))

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun fillExample() {
        app.scanner.next = ScanResult.Code("9001234567890")
        app.productSource.answer = RemoteProduct.Found("vollkornkekse", "63.2", "150", ProductPieces(12, estimated = false))
        compose.onNodeWithContentDescription("barcode scannen").performClick()
        awaitText("vollkornkekse")
        field("gegessen in stücken").performScrollTo().performTextInput("2,5")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("gramm kohlenhydrate", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("../docs/screenshots/$name.png")
    }

    @Test
    fun calculator() {
        launch()
        capture("calculator-empty")
        fillExample()
        capture("calculator")
    }

    /** A piece count worked out from the serving size, with the note that says so. */
    @Test
    fun calculatorWithEstimatedPieces() {
        app.scanner.next = ScanResult.Code("4017100291005")
        app.productSource.answer = RemoteProduct.Found("choco leibniz edelherb", "58", "125", ProductPieces(9, estimated = true))
        launch()
        compose.onNodeWithContentDescription("barcode scannen").performClick()
        awaitText("stückzahl geschätzt")
        field("gegessen in stücken").performScrollTo().performTextInput("2")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("gramm kohlenhydrate", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        capture("calculator-estimate")
    }

    @Test
    fun calculatorDarkWithSecondUnit() {
        runBlocking { app.container.settings.update { it.copy(carbUnit = CarbUnit.KE) } }
        launch(ThemeMode.DARK)
        fillExample()
        capture("calculator-dark")
    }

    /** Material You: colours from the system palette (here the emulated device's default wallpaper). */
    @Test
    fun calculatorDynamicColour() {
        launch(dynamicColor = true)
        fillExample()
        capture("calculator-material-you")
    }

    /** With AAPS on the device, the hand-off sits next to "save" and "reset" gets its own row. */
    @Test
    fun calculatorWithAaps() {
        app.aaps.installed = true
        launch()
        fillExample()
        capture("calculator-aaps")
    }

    /** Not captured: the JVM renderer sizes dialog windows differently from a device. */
    @Test
    fun saveDialog() {
        launch()
        fillExample()
        compose.onNodeWithText("speichern").performClick()
        compose.onNode(hasSetTextAction() and hasText("name, z. b. apfel")).performTextInput("kekse, nachmittag")
        compose.onNodeWithText("17 / 60").assertExists()
    }

    @Test
    fun saved() {
        runBlocking {
            val entries = app.container.entries
            entries.save("apfel", CalcInput(carbsPer100 = "11.4", totalWeight = "180", eatenSource = EatenSource.PERCENT, eatenValue = "100"), null)
            entries.save("vollkornkekse", CalcInput("63.2", "150", "12", EatenSource.PIECES, "2.5"), "vollkornkekse")
            entries.save("semmel mit butter", CalcInput(carbsPer100 = "52", eatenSource = EatenSource.GRAMS, eatenValue = "62"), null)
        }
        launch()
        compose.onNodeWithText("gespeichert").performClick()
        awaitText("semmel mit butter")
        capture("saved")
        compose.onNodeWithContentDescription("apfel bearbeiten").performClick()
        awaitText("duplizieren")
        capture("saved-actions")
    }

    @Test
    fun savedEmpty() {
        launch()
        compose.onNodeWithText("gespeichert").performClick()
        awaitText("noch nichts gespeichert")
        capture("saved-empty")
    }

    @Test
    fun settings() {
        launch()
        compose.onNodeWithText("einstellungen").performClick()
        awaitText("darstellung")
        capture("settings")
    }

    @Test
    fun about() {
        launch()
        compose.onNodeWithText("einstellungen").performClick()
        compose.onNode(hasText("kein medizinprodukt", substring = true)).performScrollTo()
        awaitText("bereit")
        capture("settings-about")

        compose.onNodeWithText("verwendete bibliotheken").performClick()
        awaitText("androidx.activity")
        capture("libraries")
    }

    /** Google's scanner module still downloading, as seen right after installing the app. */
    @Test
    fun aboutWhileScannerDownloads() {
        app.scanner.installed = false
        app.scanner.installUpdates = listOf(InstallUpdate.Progress(40), InstallUpdate.Done)
        app.scanner.installGate = CompletableDeferred()
        launch()
        compose.onNodeWithText("einstellungen").performClick()
        compose.onNodeWithText("barcode-scanner").performScrollTo()
        awaitText("noch nicht geladen")
        compose.onNodeWithText("laden").performClick()
        awaitText("wird geladen … 40 %")
        compose.onNode(hasText("kein medizinprodukt", substring = true)).performScrollTo()
        capture("settings-about-downloading")
    }

    /** What the about page says on a device where Play services will not hand over Google's scanner. */
    @Test
    fun aboutWhenGoogleScannerCannotBeLoaded() {
        app.scanner.installed = false
        app.scanner.installUpdates = listOf(InstallUpdate.Failed(InstallFailure.ServicesError(8)))
        launch()
        compose.onNodeWithText("einstellungen").performClick()
        compose.onNodeWithText("barcode-scanner").performScrollTo()
        awaitText("noch nicht geladen")
        compose.onNodeWithText("laden").performClick()
        awaitText("fehler 8")
        compose.onNode(hasText("kein medizinprodukt", substring = true)).performScrollTo()
        capture("settings-about-fallback")
    }

    /** The scanner built into the app, looking at a drawn package instead of a live picture. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun builtInScanner() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.CAMERA)
        app.scanner.installed = false
        app.camera.picture = { modifier -> PackagePhoto(modifier) }
        app.productSource.answer = RemoteProduct.Found("vollkornkekse", "63.2", "150")
        launch()
        compose.onNodeWithContentDescription("barcode scannen").performClick()
        awaitText("barcode in den rahmen halten")
        capture("scanner")

        // Hold the clock inside the moment between reading the code and closing the scanner.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { app.camera.see(PHOTO_BARCODE) }
        compose.mainClock.advanceTimeBy(350)
        captureScreenRoboImage("../docs/screenshots/scanner-found.png")
        compose.mainClock.autoAdvance = true
        awaitText("vollkornkekse")
    }

    @Test
    fun builtInScannerWithoutPermission() {
        compose.setContent {
            KhRechnerTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                ScannerContent(
                    permission = CameraPermission.DENIED,
                    phase = ScannerPhase.Starting,
                    torch = false,
                    viewfinder = { },
                    onToggleTorch = { },
                    onAllowCamera = { },
                    onClose = { },
                )
            }
        }
        awaitText("ohne kamera kein scan")
        capture("scanner-permission")
    }

    /** A newer version waiting: the section offers it and the settings tab carries a dot. */
    @Test
    fun settingsWithUpdate() {
        app.releases.answer = ReleaseLookup.Found(
            ReleaseInfo(
                version = "9.1.0",
                apkUrl = "https://github.com/Brokoli5191/kh-rechner/releases/download/v9.1.0/kh-rechner-9.1.0.apk",
                apkSize = 4_402_561,
                sha256 = null,
                pageUrl = "https://github.com/Brokoli5191/kh-rechner/releases/tag/v9.1.0",
            ),
        )
        launch()
        compose.onNodeWithText("einstellungen").performClick()
        compose.onNodeWithText("updates").performScrollTo()
        compose.onNodeWithText("suchen").performScrollTo().performClick()
        awaitText("version 9.1.0 ist da")
        compose.onNodeWithText("beim öffnen suchen").performScrollTo()
        capture("settings-updates")
    }

    private companion object {
        const val PHOTO_BARCODE = "9001234567896"
    }
}

/** A package with a barcode lying on a table, where the camera picture would be. */
@Composable
private fun PackagePhoto(modifier: Modifier) {
    val bars = remember { MultiFormatWriter().encode("9001234567896", BarcodeFormat.EAN_13, 0, 1) }
    Canvas(modifier.background(Brush.verticalGradient(listOf(Color(0xFF7A5633), Color(0xFF3A2716))))) {
        val label = Size(size.width * 0.74f, size.width * 0.74f * 0.5f)
        val corner = Offset((size.width - label.width) / 2, (size.height - label.height) / 2)
        drawRoundRect(Color(0xFFF4EFE6), corner, label, CornerRadius(10.dp.toPx()))
        val module = label.width * 0.84f / bars.width
        val left = corner.x + (label.width - module * bars.width) / 2
        for (bar in 0 until bars.width) {
            if (!bars[bar, 0]) continue
            drawRect(Color(0xFF1B1B1B), Offset(left + bar * module, corner.y + label.height * 0.14f), Size(module + 0.5f, label.height * 0.72f))
        }
    }
}
