package app.brokoli5191.khrechner.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokoli5191.khrechner.R
import app.brokoli5191.khrechner.data.LibraryGroup
import app.brokoli5191.khrechner.ui.theme.popIn

/** Every open-source project the app is built with, with its version and licence. */
@Composable
fun LibrariesScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LibrariesContent(
        libraries = libraries,
        snackbar = snackbar,
        onBack = onBack,
        onOpenLink = rememberLinkOpener(snackbar),
    )
}

@Composable
fun LibrariesContent(
    libraries: List<LibraryGroup>,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.about_libraries)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(libraries) { index, library ->
                LibraryRow(library, index, libraries.size, onOpenLink, Modifier.popIn(index))
            }
        }
    }
}

@Composable
private fun LibraryRow(
    library: LibraryGroup,
    index: Int,
    count: Int,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modules = if (library.moduleCount > 1) {
        pluralStringResource(R.plurals.library_modules, library.moduleCount, library.moduleCount)
    } else {
        null
    }
    // Project names come from the libraries themselves; lowercase keeps them in the app's voice.
    val details = listOfNotNull(library.version, library.licences.joinToString().ifEmpty { null }, modules)
        .joinToString(" · ")
        .lowercase()
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val supporting: (@Composable () -> Unit)? = details.takeIf { it.isNotEmpty() }?.let { { Text(it) } }
    val title: @Composable () -> Unit = { Text(library.name.lowercase()) }

    val url = library.url
    if (url == null) {
        SegmentedListItem(
            shapes = shapes,
            modifier = modifier,
            colors = groupColors(),
            supportingContent = supporting,
            content = title,
        )
    } else {
        SegmentedListItem(
            onClick = { onOpenLink(url) },
            shapes = shapes,
            modifier = modifier,
            colors = groupColors(),
            supportingContent = supporting,
            trailingContent = {
                Icon(
                    painter = painterResource(R.drawable.ic_open_in_new),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            content = title,
        )
    }
}
