package app.brokoli5191.khrechner.ui.settings

import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokoli5191.khrechner.R
import app.brokoli5191.khrechner.data.AppData
import app.brokoli5191.khrechner.data.BackupCodec
import app.brokoli5191.khrechner.data.ImportError
import app.brokoli5191.khrechner.data.ImportMode
import app.brokoli5191.khrechner.data.Settings
import app.brokoli5191.khrechner.data.ThemeMode
import app.brokoli5191.khrechner.data.UpdateStatus
import app.brokoli5191.khrechner.domain.CarbUnit
import app.brokoli5191.khrechner.ui.components.Choice
import app.brokoli5191.khrechner.ui.components.ConnectedChoiceGroup
import app.brokoli5191.khrechner.ui.theme.popIn
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Language tags offered in the picker; null follows the system. Must match `locales_config.xml`. */
private const val GERMAN = "de"
private const val ENGLISH = "en"

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, settings: Settings, onOpenLibraries: () -> Unit) {
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val scannerStatus by viewModel.scannerStatus.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Google's scanner module may have arrived (or gone) while the app was in the background.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshScanner()
        onPauseOrDispose { }
    }
    val haptics = LocalHapticFeedback.current
    val resources = LocalResources.current

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupCodec.MIME_TYPE)) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.readImport(uri)
    }

    LaunchedEffect(viewModel, resources) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is SettingsEvent.Exported -> resources.getQuantityString(R.plurals.export_done, event.entries, event.entries)
                SettingsEvent.ExportFailed -> resources.getString(R.string.export_failed)
                is SettingsEvent.Imported -> resources.getString(R.string.import_done, event.added, event.skipped)
                is SettingsEvent.ImportFailed -> when (event.error) {
                    ImportError.UNREADABLE -> resources.getString(R.string.import_error_unreadable)
                    ImportError.NOT_JSON -> resources.getString(R.string.import_error_not_json)
                    ImportError.NOT_A_BACKUP -> resources.getString(R.string.import_error_not_a_backup)
                    ImportError.NEWER_VERSION -> resources.getString(R.string.import_error_newer_version, event.fileVersion ?: 0)
                    ImportError.UNKNOWN_VERSION -> resources.getString(R.string.import_error_unknown_version, event.fileVersion ?: 0)
                    ImportError.INVALID_CONTENT -> resources.getString(R.string.import_error_invalid_content)
                }
            }
            if (event is SettingsEvent.Exported || event is SettingsEvent.Imported) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            }
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    SettingsContent(
        settings = settings,
        scanner = ScannerInfo(scannerStatus, viewModel.cameraAvailable),
        updateStatus = updateStatus,
        libraryCount = libraries.size,
        snackbar = snackbar,
        onInstallScanner = viewModel::installScanner,
        onCheckForUpdate = viewModel::checkForUpdate,
        onInstallUpdate = viewModel::installUpdate,
        onAutoUpdateCheck = viewModel::setCheckForUpdates,
        onOpenLibraries = onOpenLibraries,
        onOpenLink = rememberLinkOpener(snackbar),
        onThemeMode = viewModel::setThemeMode,
        onDynamicColor = viewModel::setDynamicColor,
        onCarbUnit = viewModel::setCarbUnit,
        onExport = { exportLauncher.launch(BackupCodec.fileName(LocalDate.now())) },
        // Some file providers report JSON as plain text or as an unknown binary type.
        onImport = { importLauncher.launch(arrayOf(BackupCodec.MIME_TYPE, "text/plain", "application/octet-stream")) },
    )

    pendingImport?.let { data ->
        ImportDialog(
            data = data,
            onMerge = { viewModel.confirmImport(ImportMode.MERGE) },
            onReplace = { viewModel.confirmImport(ImportMode.REPLACE) },
            onDismiss = viewModel::cancelImport,
        )
    }
}

@Composable
fun SettingsContent(
    settings: Settings,
    scanner: ScannerInfo,
    updateStatus: UpdateStatus,
    libraryCount: Int,
    snackbar: SnackbarHostState,
    onInstallScanner: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onAutoUpdateCheck: (Boolean) -> Unit,
    onOpenLibraries: () -> Unit,
    onOpenLink: (String) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onCarbUnit: (CarbUnit) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_appearance))
            SettingsGroup(index = 0) {
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                    colors = groupColors(),
                    content = {
                        ConnectedChoiceGroup(
                            choices = listOf(
                                Choice(ThemeMode.SYSTEM, stringResource(R.string.theme_system)),
                                Choice(ThemeMode.LIGHT, stringResource(R.string.theme_light)),
                                Choice(ThemeMode.DARK, stringResource(R.string.theme_dark)),
                            ),
                            selected = settings.themeMode,
                            onSelect = onThemeMode,
                        )
                    },
                )
                SegmentedListItem(
                    onClick = { onDynamicColor(!settings.dynamicColor) },
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                    colors = groupColors(),
                    leadingContent = { Icon(painterResource(R.drawable.ic_palette), contentDescription = null) },
                    supportingContent = { Text(stringResource(R.string.settings_dynamic_color_summary)) },
                    // The row itself toggles; the switch only shows the state.
                    trailingContent = { Switch(checked = settings.dynamicColor, onCheckedChange = null) },
                    content = { Text(stringResource(R.string.settings_dynamic_color)) },
                )
            }

            SectionTitle(stringResource(R.string.settings_language))
            SettingsGroup(index = 1) {
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                    colors = groupColors(),
                    content = { LanguagePicker() },
                )
            }

            SectionTitle(stringResource(R.string.settings_result))
            SettingsGroup(index = 2) {
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                    colors = groupColors(),
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.settings_carb_unit))
                            ConnectedChoiceGroup(
                                choices = listOf(
                                    Choice(CarbUnit.NONE, stringResource(R.string.carb_unit_none)),
                                    Choice(CarbUnit.KE, stringResource(R.string.carb_unit_ke)),
                                    Choice(CarbUnit.BE, stringResource(R.string.carb_unit_be)),
                                ),
                                selected = settings.carbUnit,
                                onSelect = onCarbUnit,
                            )
                            Text(
                                text = stringResource(R.string.settings_carb_unit_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }

            SectionTitle(stringResource(R.string.settings_data))
            SettingsGroup(index = 3) {
                SegmentedListItem(
                    onClick = onExport,
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                    colors = groupColors(),
                    leadingContent = { Icon(painterResource(R.drawable.ic_export), contentDescription = null) },
                    supportingContent = { Text(stringResource(R.string.settings_export_summary)) },
                    content = { Text(stringResource(R.string.settings_export)) },
                )
                SegmentedListItem(
                    onClick = onImport,
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                    colors = groupColors(),
                    leadingContent = { Icon(painterResource(R.drawable.ic_import), contentDescription = null) },
                    supportingContent = { Text(stringResource(R.string.settings_import_summary)) },
                    content = { Text(stringResource(R.string.settings_import)) },
                )
            }

            UpdateSection(
                status = updateStatus,
                autoCheck = settings.checkForUpdates,
                index = 4,
                onAutoCheck = onAutoUpdateCheck,
                onCheck = onCheckForUpdate,
                onInstall = onInstallUpdate,
                onOpenLink = onOpenLink,
            )

            AboutSection(
                scanner = scanner,
                libraryCount = libraryCount,
                firstIndex = 5,
                onInstallScanner = onInstallScanner,
                onOpenLibraries = onOpenLibraries,
                onOpenLink = onOpenLink,
            )
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(start = 8.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Rows sit on a tonal card so each group reads as one block against the plain page. */
@Composable
internal fun groupColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** [index] staggers the groups' entrance down the page. */
@Composable
internal fun SettingsGroup(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.popIn(index),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) { content() }
}

/**
 * Per-app language through the platform's [LocaleManager], so this picker and the system
 * "App languages" page always agree. Changing it recreates the activity.
 */
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    val localeManager = remember(context) { context.getSystemService(LocaleManager::class.java) }
    // Re-read whenever the configuration changes, e.g. after the system page changed the language.
    val configuration = LocalConfiguration.current
    val selected = remember(configuration, localeManager) {
        localeManager?.applicationLocales?.takeUnless { it.isEmpty }?.get(0)?.language?.takeIf { it == GERMAN || it == ENGLISH }
    }
    ConnectedChoiceGroup(
        choices = listOf(
            Choice<String?>(null, stringResource(R.string.language_system)),
            Choice<String?>(GERMAN, stringResource(R.string.language_german)),
            Choice<String?>(ENGLISH, stringResource(R.string.language_english)),
        ),
        selected = selected,
        onSelect = { tag ->
            localeManager?.applicationLocales = if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        },
    )
}

@Composable
private fun ImportDialog(
    data: AppData,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_dialog_title)) },
        text = { Text(pluralStringResource(R.plurals.import_dialog_text, data.entries.size, data.entries.size)) },
        confirmButton = {
            TextButton(onClick = onMerge) { Text(stringResource(R.string.import_merge)) }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(
                    onClick = onReplace,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.import_replace))
                }
            }
        },
    )
}

/**
 * Opens a web link in the browser. If the device has nothing that can open it, says so in
 * [snackbar] instead of failing silently.
 */
@Composable
internal fun rememberLinkOpener(snackbar: SnackbarHostState): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.link_failed)
    return remember(uriHandler, snackbar, failed) {
        { url: String ->
            try {
                uriHandler.openUri(url)
            } catch (_: IllegalArgumentException) {
                scope.launch { snackbar.showSnackbar(failed) }
            } catch (_: ActivityNotFoundException) {
                scope.launch { snackbar.showSnackbar(failed) }
            }
        }
    }
}
