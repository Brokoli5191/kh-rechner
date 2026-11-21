package app.brokoli5191.khrechner

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Starts the real activity: splash screen, edge-to-edge, settings load, theme, navigation. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class, qualifiers = "de-rAT-w411dp-h891dp-xhdpi")
class MainActivityTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `the activity starts on the calculator with all three destinations`() {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("kh rechner")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("rechnen").assertExists()
        compose.onNodeWithText("gespeichert").assertExists()
        compose.onNodeWithText("einstellungen").assertExists()
        compose.onNodeWithText("kh pro 100 g").assertExists()
    }
}
