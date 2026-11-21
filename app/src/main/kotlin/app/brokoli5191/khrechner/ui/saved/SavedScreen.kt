package app.brokoli5191.khrechner.ui.saved

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokoli5191.khrechner.R
import app.brokoli5191.khrechner.data.SavedEntry
import app.brokoli5191.khrechner.ui.components.LocalDecimalSeparator
import app.brokoli5191.khrechner.ui.components.NameDialog
import app.brokoli5191.khrechner.ui.components.shown
import app.brokoli5191.khrechner.ui.theme.bounceOnPress
import app.brokoli5191.khrechner.ui.theme.popIn
import app.brokoli5191.khrechner.ui.theme.rememberAppMotion
import kotlinx.coroutines.launch

@Composable
fun SavedScreen(
    viewModel: SavedViewModel,
    onOpen: (SavedEntry) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val resources = LocalResources.current

    // Ids, not entries: the sheet and dialog follow the list and close themselves if the entry goes away.
    var sheetEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetEntry = state.items.firstOrNull { it.entry.id == sheetEntryId }?.entry
    val renameEntry = state.items.firstOrNull { it.entry.id == renameEntryId }?.entry

    fun delete(entry: SavedEntry) {
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        scope.launch {
            val removed = viewModel.delete(entry).await() ?: return@launch
            snackbar.currentSnackbarData?.dismiss()
            val answer = snackbar.showSnackbar(
                message = resources.getString(R.string.snackbar_deleted, removed.name),
                actionLabel = resources.getString(R.string.action_undo),
                duration = SnackbarDuration.Long,
            )
            if (answer == SnackbarResult.ActionPerformed) viewModel.restore(removed)
        }
    }

    SavedContent(
        state = state,
        snackbar = snackbar,
        onOpen = onOpen,
        onEdit = { sheetEntryId = it.id },
        onDelete = ::delete,
    )

    if (sheetEntry != null) {
        val copyName = stringResource(R.string.duplicate_name, sheetEntry.name)
        EntryActionsSheet(
            entry = sheetEntry,
            onRename = {
                sheetEntryId = null
                renameEntryId = sheetEntry.id
            },
            onDuplicate = {
                sheetEntryId = null
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                viewModel.duplicate(sheetEntry, copyName)
            },
            onDelete = {
                sheetEntryId = null
                delete(sheetEntry)
            },
            onDismiss = { sheetEntryId = null },
        )
    }

    if (renameEntry != null) {
        NameDialog(
            title = stringResource(R.string.action_rename),
            confirmLabel = stringResource(R.string.action_save),
            initialName = renameEntry.name,
            onConfirm = { name ->
                renameEntryId = null
                viewModel.rename(renameEntry, name)
            },
            onDismiss = { renameEntryId = null },
        )
    }
}

@Composable
fun SavedContent(
    state: SavedUiState,
    snackbar: SnackbarHostState,
    onOpen: (SavedEntry) -> Unit,
    onEdit: (SavedEntry) -> Unit,
    onDelete: (SavedEntry) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val motion = rememberAppMotion()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.nav_saved)) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            !state.loaded -> Unit
            state.items.isEmpty() -> EmptyState(Modifier.padding(padding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.items, key = { _, item -> item.entry.id }) { index, item ->
                    SavedCard(
                        item = item,
                        // Neighbours spring into the gap a removed card leaves; new cards rise in.
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = motion.effects(),
                                placementSpec = motion.bouncy(),
                                fadeOutSpec = motion.effects(),
                            )
                            .popIn(index),
                        onOpen = { onOpen(item.entry) },
                        onEdit = { onEdit(item.entry) },
                        onDelete = { onDelete(item.entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedCard(
    item: SavedItem,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val separator = LocalDecimalSeparator.current
    val deleteLabel = stringResource(R.string.action_delete)
    val dismissState = rememberSwipeToDismissBoxState()
    val interactionSource = remember { MutableInteractionSource() }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { DeleteBackground(dismissState.dismissDirection, progress = { dismissState.progress }) },
        // Swiping is a gesture only; screen readers get the same action by name.
        modifier = modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(deleteLabel) {
                    onDelete()
                    true
                },
            )
        },
        onDismiss = { onDelete() },
    ) {
        Card(
            onClick = onOpen,
            modifier = Modifier.bounceOnPress(interactionSource),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            interactionSource = interactionSource,
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.date,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = item.carbsEaten?.let { stringResource(R.string.result_grams, it.shown(separator)) }
                        ?: stringResource(R.string.saved_no_result),
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                IconButton(onClick = onEdit) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.saved_edit, item.entry.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The bin grows as the card is pulled aside and is at full size once letting go would delete. */
@Composable
private fun DeleteBackground(direction: SwipeToDismissBoxValue, progress: () -> Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(MaterialTheme.shapes.large)
            .background(if (direction == SwipeToDismissBoxValue.Settled) Color.Transparent else MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        if (direction != SwipeToDismissBoxValue.Settled) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = null,
                modifier = Modifier.graphicsLayer {
                    val scale = 0.6f + 0.7f * (progress() * 2f).coerceIn(0f, 1f)
                    scaleX = scale
                    scaleY = scale
                },
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val motion = rememberAppMotion()
    val entrance = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, motion.bouncy()) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                // The cookie rolls in: it grows from nothing with a quarter turn and settles with a bounce.
                .graphicsLayer {
                    scaleX = entrance.value
                    scaleY = entrance.value
                    rotationZ = (1f - entrance.value) * -90f
                }
                .size(112.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_bookmarks),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            text = stringResource(R.string.saved_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryActionsSheet(
    entry: SavedEntry,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Three rows: there is no useful half-open state.
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Text(
            text = entry.name,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        SheetAction(R.drawable.ic_edit, stringResource(R.string.action_rename), onRename)
        SheetAction(R.drawable.ic_copy, stringResource(R.string.action_duplicate), onDuplicate)
        SheetAction(R.drawable.ic_delete, stringResource(R.string.action_delete), onDelete, destructive = true)
    }
}

@Composable
private fun SheetAction(icon: Int, label: String, onClick: () -> Unit, destructive: Boolean = false) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(label, color = color)
    }
}
