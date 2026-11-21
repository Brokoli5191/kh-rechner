package app.brokoli5191.khrechner.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.brokoli5191.khrechner.BuildConfig
import app.brokoli5191.khrechner.R
import app.brokoli5191.khrechner.data.ReleaseInfo
import app.brokoli5191.khrechner.data.UpdateFailure
import app.brokoli5191.khrechner.data.UpdateStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "updates": the state of the installed version, the one action that moves it on, the release
 * notes of a newer version, and whether to look on opening.
 */
@Composable
internal fun UpdateSection(
    status: UpdateStatus,
    autoCheck: Boolean,
    index: Int,
    onAutoCheck: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_updates))
    SettingsGroup(index = index) {
        val release = status.release
        val rows = if (release != null) 3 else 2
        StatusRow(status, index = 0, count = rows, onCheck = onCheck, onInstall = onInstall)
        if (release != null) {
            LinkRow(
                icon = R.drawable.ic_new_releases,
                title = stringResource(R.string.update_release_notes),
                summary = stringResource(R.string.update_release_notes_summary, release.version),
                index = 1,
                count = rows,
                onClick = { onOpenLink(release.pageUrl) },
            )
        }
        SegmentedListItem(
            onClick = { onAutoCheck(!autoCheck) },
            shapes = ListItemDefaults.segmentedShapes(index = rows - 1, count = rows),
            colors = groupColors(),
            leadingContent = { Icon(painterResource(R.drawable.ic_autorenew), contentDescription = null) },
            supportingContent = { Text(stringResource(R.string.update_auto_check_summary)) },
            // The row itself toggles; the switch only shows the state.
            trailingContent = { Switch(checked = autoCheck, onCheckedChange = null) },
            content = { Text(stringResource(R.string.update_auto_check)) },
        )
    }
}

/** The release the status is about, if any. */
private val UpdateStatus.release: ReleaseInfo?
    get() = when (this) {
        is UpdateStatus.Available -> release
        is UpdateStatus.Downloading -> release
        is UpdateStatus.Installing -> release
        is UpdateStatus.Failed -> release
        else -> null
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusRow(status: UpdateStatus, index: Int, count: Int, onCheck: () -> Unit, onInstall: () -> Unit) {
    val installed = BuildConfig.VERSION_NAME
    val release = status.release
    val title = when {
        release != null -> stringResource(R.string.update_title_available, release.version)
        status is UpdateStatus.Checking -> stringResource(R.string.update_title_checking)
        status is UpdateStatus.UpToDate -> stringResource(R.string.update_title_current)
        status is UpdateStatus.Failed -> stringResource(R.string.update_title_failed)
        else -> stringResource(R.string.update_title_unknown)
    }
    val summary = when (status) {
        UpdateStatus.Unknown, UpdateStatus.Checking -> stringResource(R.string.update_summary_installed, installed)
        is UpdateStatus.UpToDate -> checkedSummary(installed, status.checkedAt)
        is UpdateStatus.Available -> stringResource(R.string.update_summary_available, installed, size(status.release.apkSize))
        is UpdateStatus.Downloading ->
            status.percent?.let { stringResource(R.string.update_downloading_percent, it) } ?: stringResource(R.string.update_downloading)
        is UpdateStatus.Installing -> stringResource(R.string.update_installing)
        is UpdateStatus.Failed -> status.reason.message()
    }
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = groupColors(),
        leadingContent = { Icon(painterResource(R.drawable.ic_update), contentDescription = null) },
        supportingContent = { Text(summary) },
        trailingContent = {
            when (status) {
                UpdateStatus.Checking, is UpdateStatus.Downloading, is UpdateStatus.Installing ->
                    LoadingIndicator(Modifier.size(32.dp))
                is UpdateStatus.Available -> Button(onClick = onInstall) { Text(stringResource(R.string.action_install)) }
                // A failed download is retried as such; a failed look looks again.
                is UpdateStatus.Failed -> FilledTonalButton(onClick = if (status.release != null) onInstall else onCheck) {
                    Text(stringResource(R.string.action_retry))
                }
                UpdateStatus.Unknown, is UpdateStatus.UpToDate ->
                    FilledTonalButton(onClick = onCheck) { Text(stringResource(R.string.action_check)) }
            }
        },
        content = { Text(title) },
    )
}

@Composable
private fun checkedSummary(installed: String, checkedAt: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(checkedAt).atZone(zone).toLocalDate()
    return if (day == LocalDate.now(zone)) {
        stringResource(R.string.update_summary_current_today, installed)
    } else {
        stringResource(R.string.update_summary_current, installed, DATE.format(day))
    }
}

/** "4,4 mb", in the app's lowercase. */
@Composable
private fun size(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes).lowercase()

@Composable
private fun UpdateFailure.message(): String = stringResource(
    when (this) {
        UpdateFailure.NO_CONNECTION -> R.string.update_failed_no_connection
        UpdateFailure.GITHUB_UNAVAILABLE -> R.string.update_failed_github
        UpdateFailure.NO_APK -> R.string.update_failed_no_apk
        UpdateFailure.DAMAGED -> R.string.update_failed_damaged
        UpdateFailure.WRONG_SIGNATURE -> R.string.update_failed_signature
        UpdateFailure.NO_STORAGE -> R.string.update_failed_storage
        UpdateFailure.BLOCKED -> R.string.update_failed_blocked
        UpdateFailure.INSTALL_FAILED -> R.string.update_failed_install
    },
)

// Austrian date format, independent of the UI language, as on the saved entries.
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
