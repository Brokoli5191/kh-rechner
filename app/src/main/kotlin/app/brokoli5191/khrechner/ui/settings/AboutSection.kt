package app.brokoli5191.khrechner.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.brokoli5191.khrechner.BuildConfig
import app.brokoli5191.khrechner.R
import app.brokoli5191.khrechner.data.ScannerStatus
import app.brokoli5191.khrechner.ui.components.installFailureMessage
import app.brokoli5191.khrechner.ui.theme.popIn

/** Where the about section points to. */
internal object Links {
    const val SOURCE = "https://github.com/Brokoli5191/kh-rechner"
    const val LICENCE = "$SOURCE/blob/main/LICENSE"
    const val REPORT = "$SOURCE/issues/new"
    const val PRODUCT_DATA = "https://world.openfoodfacts.org"
}

/**
 * "über": what the app is, the state of the barcode scanner, and where its code, its building
 * blocks and its data come from.
 *
 * @param firstIndex position of this section among the settings groups, for the staggered entrance.
 */
@Composable
internal fun AboutSection(
    scannerStatus: ScannerStatus,
    libraryCount: Int,
    firstIndex: Int,
    onInstallScanner: () -> Unit,
    onOpenLibraries: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_about))
    AboutHero(Modifier.popIn(firstIndex))

    SettingsGroup(index = firstIndex + 1, modifier = Modifier.padding(top = 12.dp)) {
        val rows = 6
        ScannerRow(scannerStatus, index = 0, count = rows, onInstall = onInstallScanner)
        LinkRow(
            icon = R.drawable.ic_code,
            title = stringResource(R.string.about_source),
            summary = stringResource(R.string.about_source_summary),
            index = 1,
            count = rows,
            onClick = { onOpenLink(Links.SOURCE) },
        )
        LinkRow(
            icon = R.drawable.ic_library,
            title = stringResource(R.string.about_libraries),
            summary = if (libraryCount > 0) pluralStringResource(R.plurals.about_libraries_count, libraryCount, libraryCount) else null,
            index = 2,
            count = rows,
            // Stays inside the app, so it gets a chevron rather than the "opens elsewhere" arrow.
            trailingIcon = R.drawable.ic_chevron_right,
            onClick = onOpenLibraries,
        )
        LinkRow(
            icon = R.drawable.ic_licence,
            title = stringResource(R.string.about_licence),
            summary = stringResource(R.string.about_licence_summary),
            index = 3,
            count = rows,
            onClick = { onOpenLink(Links.LICENCE) },
        )
        LinkRow(
            icon = R.drawable.ic_database,
            title = stringResource(R.string.about_product_data),
            summary = stringResource(R.string.about_product_data_summary),
            index = 4,
            count = rows,
            onClick = { onOpenLink(Links.PRODUCT_DATA) },
        )
        LinkRow(
            icon = R.drawable.ic_bug,
            title = stringResource(R.string.about_report),
            summary = stringResource(R.string.about_report_summary),
            index = 5,
            count = rows,
            onClick = { onOpenLink(Links.REPORT) },
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .popIn(firstIndex + 2),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = stringResource(R.string.about_disclaimer),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The app's own cookie, on a cookie-shaped badge. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AboutHero(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                // The launcher artwork only fills the middle of its canvas, so it is drawn oversized and clipped.
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_monochrome),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(124.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Column {
                Text(stringResource(R.string.app_title), style = MaterialTheme.typography.headlineMediumEmphasized)
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(R.string.about_description),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Scanner state in words, with the one action that helps in that state. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScannerRow(status: ScannerStatus, index: Int, count: Int, onInstall: () -> Unit) {
    val failed = status is ScannerStatus.InstallFailed
    val summary = when (status) {
        ScannerStatus.Unsupported -> stringResource(R.string.scanner_status_unsupported)
        ScannerStatus.Checking -> stringResource(R.string.scanner_status_checking)
        ScannerStatus.Ready -> stringResource(R.string.scanner_status_ready)
        ScannerStatus.NotInstalled -> stringResource(R.string.scanner_status_not_installed)
        is ScannerStatus.Installing ->
            if (status.percent == null) {
                stringResource(R.string.scanner_status_installing)
            } else {
                stringResource(R.string.scanner_status_installing_percent, status.percent)
            }
        is ScannerStatus.InstallFailed -> installFailureMessage(status.reason, status.code)
    }
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = groupColors(),
        leadingContent = { Icon(painterResource(R.drawable.ic_barcode_scanner), contentDescription = null) },
        supportingContent = {
            Text(summary, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            when (status) {
                ScannerStatus.Ready ->
                    Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                is ScannerStatus.Installing -> LoadingIndicator(Modifier.size(32.dp))
                ScannerStatus.NotInstalled ->
                    FilledTonalButton(onClick = onInstall) { Text(stringResource(R.string.action_load)) }
                is ScannerStatus.InstallFailed ->
                    FilledTonalButton(onClick = onInstall) { Text(stringResource(R.string.action_retry)) }
                ScannerStatus.Unsupported ->
                    Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                ScannerStatus.Checking -> Unit
            }
        },
        content = { Text(stringResource(R.string.about_scanner)) },
    )
}

@Composable
private fun LinkRow(
    icon: Int,
    title: String,
    summary: String?,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    trailingIcon: Int = R.drawable.ic_open_in_new,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = groupColors(),
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = {
            Icon(painterResource(trailingIcon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        content = { Text(title) },
    )
}
