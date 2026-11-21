package app.brokoli5191.khrechner.ui

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brokoli5191.khrechner.TestApplication
import app.brokoli5191.khrechner.data.RemoteProduct
import app.brokoli5191.khrechner.data.ScanResult
import app.brokoli5191.khrechner.data.Settings
import app.brokoli5191.khrechner.data.ThemeMode
import app.brokoli5191.khrechner.domain.CalcInput
import app.brokoli5191.khrechner.domain.CarbUnit
import app.brokoli5191.khrechner.domain.EatenSource
import app.brokoli5191.khrechner.ui.theme.KhRechnerTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
        app.productSource.answer = RemoteProduct.Found("vollkornkekse", "63.2", "150")
        compose.onNodeWithContentDescription("barcode scannen").performClick()
        awaitText("vollkornkekse")
        compose.onNodeWithText("gesamtgewicht 150 g übernehmen").performClick()
        field("anzahl der stücke").performTextInput("12")
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
}
