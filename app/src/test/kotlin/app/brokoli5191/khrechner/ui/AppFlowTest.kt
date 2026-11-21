package app.brokoli5191.khrechner.ui

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brokoli5191.khrechner.TestApplication
import app.brokoli5191.khrechner.data.RemoteProduct
import app.brokoli5191.khrechner.data.ScanResult
import app.brokoli5191.khrechner.data.Settings
import app.brokoli5191.khrechner.data.ThemeMode
import app.brokoli5191.khrechner.ui.theme.KhRechnerTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Drives the real app (view models, DataStore, navigation) on the JVM. German locale, so the
 * decimal comma and the default strings are what gets exercised.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class, qualifiers = "de-rAT-w411dp-h891dp-xhdpi")
class AppFlowTest {

    @get:Rule
    val compose = createComposeRule()

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

        // More than there is: inline error, no result, no crash.
        field("gegessen in prozent").performTextReplacement("120")
        awaitResult("noch kein ergebnis")
        compose.onNodeWithText("gegessen in prozent: mehr als 100 %").assertExists()
        compose.onNodeWithText("speichern").assertIsNotEnabled()
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
    fun `a scanned product fills the carb value and offers its weight`() {
        app.scanner.next = ScanResult.Code("3017624010701")
        app.productSource.answer = RemoteProduct.Found("Nutella", "57.5", "400")

        compose.onNodeWithContentDescription("barcode scannen").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Nutella")).fetchSemanticsNodes().isNotEmpty() }
        field("kh pro 100 g").assertTextContains("57,5")
        // Offered, not applied.
        field("gesamtgewicht").assertTextContains("")
        compose.onNodeWithText("gesamtgewicht 400 g übernehmen").performClick()
        field("gesamtgewicht").assertTextContains("400")
        compose.onNodeWithText("gesamtgewicht 400 g übernehmen").assertDoesNotExist()

        // Typing over the value detaches it from the product.
        field("kh pro 100 g").performTextReplacement("50")
        compose.onNodeWithText("Nutella").assertDoesNotExist()

        // Same barcode again with the network gone: answered from the cache, no second request.
        app.productSource.answer = RemoteProduct.Unavailable
        compose.onNodeWithContentDescription("barcode scannen").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Nutella")).fetchSemanticsNodes().isNotEmpty() }
        field("kh pro 100 g").assertTextContains("57,5")
        assertEquals(listOf("3017624010701"), app.productSource.requested)
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

        app.scanner.next = ScanResult.Unavailable
        compose.onNodeWithContentDescription("barcode scannen").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("scanner ist noch nicht bereit", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        // The calculator never waits on the network: typing still works and clears the message.
        field("kh pro 100 g").performTextInput("12")
        compose.onNode(hasText("scanner ist noch nicht bereit", substring = true)).assertDoesNotExist()
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
}
