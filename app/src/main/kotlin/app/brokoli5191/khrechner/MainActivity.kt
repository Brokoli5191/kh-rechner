package app.brokoli5191.khrechner

import android.app.UiModeManager
import android.graphics.Color
import android.os.Bundle
import android.view.animation.AnticipateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokoli5191.khrechner.data.Settings
import app.brokoli5191.khrechner.data.ThemeMode
import app.brokoli5191.khrechner.ui.KhRechnerApp
import app.brokoli5191.khrechner.ui.theme.KhRechnerTheme
import app.brokoli5191.khrechner.ui.theme.isDark

class MainActivity : ComponentActivity() {

    /** Set once the stored settings are in, so the first frame already has the right theme. */
    private var settingsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !settingsLoaded }
        splash.setOnExitAnimationListener(::bounceSplashAway)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as KhRechnerApplication).container
        setContent {
            val stored by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val settings: Settings = stored ?: return@setContent
            settingsLoaded = true

            val dark = settings.themeMode.isDark()
            // Keep the system bar icons readable when the in-app theme differs from the system one.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            // Tell the platform too, so the splash screen and system dialogs follow the choice.
            LaunchedEffect(settings.themeMode) {
                getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                    when (settings.themeMode) {
                        ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                    },
                )
            }

            KhRechnerTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                KhRechnerApp(container = container, settings = settings)
            }
        }
    }

    /**
     * The splash icon ducks, then springs out towards the viewer while the splash fades. View
     * animators follow the system animation scale, so this is instant with animations removed.
     */
    private fun bounceSplashAway(splash: SplashScreenViewProvider) {
        try {
            splash.iconView.animate()
                .scaleX(SPLASH_EXIT_SCALE)
                .scaleY(SPLASH_EXIT_SCALE)
                .setInterpolator(AnticipateInterpolator(SPLASH_ANTICIPATION))
                .setDuration(SPLASH_EXIT_MILLIS)
                .start()
            splash.view.animate()
                .alpha(0f)
                .setStartDelay(SPLASH_EXIT_MILLIS / 3)
                .setDuration(SPLASH_EXIT_MILLIS * 2 / 3)
                .withEndAction(splash::remove)
                .start()
        } catch (_: RuntimeException) {
            // Some launch paths hand over a splash without an icon view; just get out of the way.
            splash.remove()
        }
    }

    private companion object {
        const val SPLASH_EXIT_MILLIS = 360L
        const val SPLASH_EXIT_SCALE = 1.8f
        const val SPLASH_ANTICIPATION = 2.5f
    }
}
