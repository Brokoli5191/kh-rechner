package app.brokoli5191.khrechner.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import app.brokoli5191.khrechner.AppContainer
import app.brokoli5191.khrechner.R
import app.brokoli5191.khrechner.data.Settings
import app.brokoli5191.khrechner.ui.calculator.CalculatorScreen
import app.brokoli5191.khrechner.ui.calculator.CalculatorViewModel
import app.brokoli5191.khrechner.ui.components.LocalDecimalSeparator
import app.brokoli5191.khrechner.ui.saved.SavedScreen
import app.brokoli5191.khrechner.ui.saved.SavedViewModel
import app.brokoli5191.khrechner.ui.scanner.CameraScanner
import app.brokoli5191.khrechner.ui.scanner.ScannerOverlay
import app.brokoli5191.khrechner.ui.settings.LibrariesScreen
import app.brokoli5191.khrechner.ui.settings.SettingsScreen
import app.brokoli5191.khrechner.ui.settings.SettingsViewModel
import app.brokoli5191.khrechner.ui.theme.AppMotion
import app.brokoli5191.khrechner.ui.theme.rememberAppMotion
import java.text.DecimalFormatSymbols

/** The three top-level destinations, in navigation bar order. */
enum class Destination(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
) {
    CALCULATOR(R.string.nav_calculate, R.drawable.ic_calculate, R.drawable.ic_calculate_filled),
    SAVED(R.string.nav_saved, R.drawable.ic_bookmarks, R.drawable.ic_bookmarks_filled),
    SETTINGS(R.string.nav_settings, R.drawable.ic_settings, R.drawable.ic_settings_filled),
}

/** Everything that can be on the back stack: the three tabs and the pages opened from them. */
enum class Screen(val tab: Destination) {
    CALCULATOR(Destination.CALCULATOR),
    SAVED(Destination.SAVED),
    SETTINGS(Destination.SETTINGS),

    /** "verwendete bibliotheken", opened from the about section. */
    LIBRARIES(Destination.SETTINGS),
}

private val Destination.root: Screen get() = Screen.valueOf(name)

/**
 * The back stack is `[CALCULATOR]`, `[CALCULATOR, tab]` or that plus a page opened from the tab.
 * Back closes the page, then returns to the calculator (with the predictive back preview), and
 * from the calculator leaves the app. Choosing a tab always lands on the tab itself.
 */
private fun SnapshotStateList<Screen>.switchTo(tab: Destination) {
    val target = tab.root
    if (lastOrNull() == target) return
    removeRange(1, size)
    if (target != Screen.CALCULATOR) add(target)
}

@Composable
fun KhRechnerApp(container: AppContainer, settings: Settings) {
    val backStack = rememberSaveable(
        saver = listSaver(
            save = { stack -> stack.map(Screen::name) },
            restore = { names -> names.map(Screen::valueOf).toMutableStateList() },
        ),
    ) { mutableStateListOf(Screen.CALCULATOR) }
    val current = backStack.last().tab
    // NavDisplay keeps the entries it built, so their content must read settings through state
    // instead of capturing the value they were created with.
    val currentSettings by rememberUpdatedState(settings)

    // Scoped to the activity so the calculator keeps its input while other tabs are open.
    val calculatorViewModel: CalculatorViewModel = viewModel(factory = container.viewModelFactory)
    val savedViewModel: SavedViewModel = viewModel(factory = container.viewModelFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = container.viewModelFactory)

    val locale = LocalConfiguration.current.locales[0]
    val decimalSeparator = remember(locale) { DecimalFormatSymbols.getInstance(locale).decimalSeparator }

    val motion = rememberAppMotion()
    // Which way the next screen change travels; set just before the back stack changes.
    var direction by remember { mutableIntStateOf(1) }
    fun switchTo(destination: Destination) {
        // Towards the end of the bar travels forwards; so does nothing, and closing a page goes back.
        direction = if (destination.ordinal > current.ordinal) 1 else -1
        backStack.switchTo(destination)
    }
    fun open(screen: Screen) {
        direction = 1
        backStack.add(screen)
    }

    CompositionLocalProvider(LocalDecimalSeparator provides decimalSeparator) {
        Box {
            Scaffold(
                bottomBar = {
                    ShortNavigationBar {
                        Destination.entries.forEach { destination ->
                            val selected = destination == current
                            // The icon of the chosen tab pops: it grows past its size and settles.
                            val iconScale by animateFloatAsState(if (selected) 1.15f else 1f, motion.bouncy(), label = "navIcon")
                            ShortNavigationBarItem(
                                selected = selected,
                                onClick = { switchTo(destination) },
                                icon = {
                                    Icon(
                                        painter = painterResource(if (selected) destination.selectedIcon else destination.icon),
                                        contentDescription = null,
                                        modifier = Modifier.graphicsLayer {
                                            scaleX = iconScale
                                            scaleY = iconScale
                                        },
                                    )
                                },
                                label = { Text(stringResource(destination.label)) },
                            )
                        }
                    }
                },
                // Each screen brings its own top bar and handles the top inset there.
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { padding ->
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding)
                        // Lifts the content, including the docked result, above the keyboard.
                        .imePadding()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                    onBack = { backStack.removeLastOrNull() },
                    transitionSpec = { tabTransition(motion, direction) },
                    // Back always leads to the calculator, the first tab.
                    popTransitionSpec = { tabTransition(motion, direction = -1) },
                    predictivePopTransitionSpec = { tabTransition(motion, direction = -1) },
                    entryProvider = { screen ->
                        NavEntry(screen) {
                            when (screen) {
                                Screen.CALCULATOR -> CalculatorScreen(
                                    viewModel = calculatorViewModel,
                                    carbUnit = currentSettings.carbUnit,
                                    canScan = container.camera.isAvailable,
                                    aaps = container.aaps,
                                )
                                Screen.SAVED -> SavedScreen(
                                    viewModel = savedViewModel,
                                    onOpen = { entry ->
                                        calculatorViewModel.load(entry)
                                        switchTo(Destination.CALCULATOR)
                                    },
                                )
                                Screen.SETTINGS -> SettingsScreen(
                                    viewModel = settingsViewModel,
                                    settings = currentSettings,
                                    onOpenLibraries = { open(Screen.LIBRARIES) },
                                )
                                Screen.LIBRARIES -> LibrariesScreen(
                                    viewModel = settingsViewModel,
                                    onBack = { backStack.removeLastOrNull() },
                                )
                            }
                        }
                    },
                )
            }
            CameraScannerOverlay(calculatorViewModel, container.camera)
        }
    }
}

/** The built-in scanner covers the whole app, navigation bar included, so it lives outside the scaffold. */
@Composable
private fun CameraScannerOverlay(viewModel: CalculatorViewModel, camera: CameraScanner) {
    // Collected here rather than in the caller, which would otherwise recompose on every keystroke.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScannerOverlay(
        visible = state.cameraScannerOpen,
        camera = camera,
        onCode = viewModel::onCameraScan,
        onClose = viewModel::closeCameraScanner,
    )
}

/**
 * Screens travel sideways in the order of the navigation bar: the new one springs in from the
 * side it sits on and overshoots slightly, the old one is pushed out the other way and shrinks.
 * [direction] is +1 towards the end of the bar and -1 towards its start; the predictive back
 * gesture scrubs through the same motion.
 */
private fun tabTransition(motion: AppMotion, direction: Int): ContentTransform {
    if (motion.reduced) return EnterTransition.None togetherWith ExitTransition.None
    val enter = slideInHorizontally(motion.bouncy()) { width -> direction * width / 3 } +
        scaleIn(motion.bouncy(), initialScale = 0.9f) +
        fadeIn(motion.effects())
    val exit = slideOutHorizontally(motion.snappy()) { width -> -direction * width / 3 } +
        scaleOut(motion.snappy(), targetScale = 0.9f) +
        fadeOut(motion.effects())
    return enter togetherWith exit
}
