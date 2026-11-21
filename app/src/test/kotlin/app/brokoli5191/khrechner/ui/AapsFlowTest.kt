package app.brokoli5191.khrechner.ui

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brokoli5191.khrechner.TestApplication
import app.brokoli5191.khrechner.data.Settings
import app.brokoli5191.khrechner.data.ThemeMode
import app.brokoli5191.khrechner.ui.theme.KhRechnerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The clipboard hand-off to AAPS, driven through the real UI with a scripted AAPS. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class, qualifiers = "de-rAT-w411dp-h891dp-xhdpi")
class AapsFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: TestApplication get() = ApplicationProvider.getApplicationContext()

    private fun launch(aapsInstalled: Boolean) {
        app.aaps.installed = aapsInstalled
        val container = app.container
        compose.setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
            KhRechnerTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                KhRechnerApp(container = container, settings = settings)
            }
        }
    }

    private fun field(name: String) = compose.onNode(hasSetTextAction() and (hasText(name) or hasContentDescription(name)))

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `without aaps there is no hand-off button and reset keeps its place`() {
        launch(aapsInstalled = false)
        field("kh pro 100 g").performTextInput("5,3")
        field("gegessen in g").performTextInput("240")
        awaitText("speichern")
        compose.onNode(hasText("an aaps", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("zurücksetzen").assertIsDisplayed()
    }

    @Test
    fun `the button names the rounded amount, copies it and opens aaps`() {
        launch(aapsInstalled = true)
        // No result yet: the button is there but has nothing to hand over.
        compose.onNodeWithText("an aaps").assertIsNotEnabled()

        field("kh pro 100 g").performTextInput("5,3")
        field("gegessen in g").performTextInput("240")
        // 12,72 g of carbs: shown with one decimal, handed over as whole grams.
        awaitText("13 g an aaps")
        compose.onNodeWithText("13 g an aaps").assertIsEnabled().performClick()

        assertEquals(listOf(13), app.aaps.handedOver)
        awaitText("13 g kopiert, in aaps einfügen")
    }

    @Test
    fun `an amount that rounds to zero is not handed over`() {
        launch(aapsInstalled = true)
        field("kh pro 100 g").performTextInput("5,3")
        field("gegessen in g").performTextInput("9")
        // 0,477 g rounds down, 0,53 g rounds up.
        awaitText("0 g an aaps")
        compose.onNodeWithText("0 g an aaps").assertIsNotEnabled()
        field("gegessen in g").performTextReplacement("10")
        awaitText("1 g an aaps")
        compose.onNodeWithText("1 g an aaps").assertIsEnabled()
    }

    @Test
    fun `installing aaps while the app is open brings the button in on return`() {
        launch(aapsInstalled = false)
        compose.onNode(hasText("an aaps", substring = true)).assertDoesNotExist()
        app.aaps.installed = true
        compose.onNodeWithText("gespeichert").performClick()
        compose.onNodeWithText("rechnen").performClick()
        awaitText("an aaps")
    }

    @Test
    @Config(qualifiers = "de-rAT-w360dp-h740dp-xhdpi")
    fun `all three actions fit on a narrow phone`() {
        launch(aapsInstalled = true)
        field("kh pro 100 g").performTextInput("63,2")
        field("gegessen in g").performTextInput("999")
        awaitText("631 g an aaps")
        compose.onNodeWithText("speichern").assertIsDisplayed()
        compose.onNodeWithText("631 g an aaps").assertIsDisplayed()
        compose.onNodeWithText("zurücksetzen").assertIsDisplayed()
    }
}
