package app.brokoli5191.khrechner.ui.calculator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brokoli5191.khrechner.R
import app.brokoli5191.khrechner.data.AapsHandoff
import app.brokoli5191.khrechner.domain.CalcError
import app.brokoli5191.khrechner.domain.CalcField
import app.brokoli5191.khrechner.domain.CarbUnit
import app.brokoli5191.khrechner.domain.DecimalText
import app.brokoli5191.khrechner.domain.DisplayNumber
import app.brokoli5191.khrechner.domain.EatenSource
import app.brokoli5191.khrechner.domain.Fraction
import app.brokoli5191.khrechner.ui.components.APPROXIMATELY
import app.brokoli5191.khrechner.ui.components.LocalDecimalSeparator
import app.brokoli5191.khrechner.ui.components.NameDialog
import app.brokoli5191.khrechner.ui.components.RollingNumber
import app.brokoli5191.khrechner.ui.components.shown
import app.brokoli5191.khrechner.ui.components.withSeparator
import app.brokoli5191.khrechner.ui.theme.AppMotion
import app.brokoli5191.khrechner.ui.theme.rememberAppMotion
import kotlinx.coroutines.launch

@Composable
fun CalculatorScreen(
    viewModel: CalculatorViewModel,
    carbUnit: CarbUnit,
    canScan: Boolean,
    aaps: AapsHandoff,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val resources = LocalResources.current
    val context = LocalContext.current
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }

    // AAPS can be installed or removed while this app is in the background.
    var aapsInstalled by remember { mutableStateOf(false) }
    LifecycleResumeEffect(aaps) {
        aapsInstalled = aaps.isInstalled()
        onPauseOrDispose { }
    }

    CalculatorContent(
        state = state,
        fields = viewModel.fields,
        carbUnit = carbUnit,
        snackbar = snackbar,
        canScan = canScan,
        showAaps = aapsInstalled,
        onScan = { scope.launch { viewModel.scanBarcode(context) } },
        onSendToAaps = {
            state.wholeGrams?.let { grams ->
                // Clipboard first, then AAPS on top; the user pastes and confirms over there.
                aaps.copyAndOpen(grams)
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(
                        message = resources.getString(R.string.snackbar_aaps_copied, grams),
                        duration = SnackbarDuration.Long,
                    )
                }
            }
        },
        onApplyWeight = viewModel::applySuggestedWeight,
        onClearProduct = viewModel::clearProduct,
        onSave = { showSaveDialog = true },
        onReset = {
            val previous = viewModel.reset()
            focusManager.clearFocus()
            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val answer = snackbar.showSnackbar(
                    message = resources.getString(R.string.snackbar_reset),
                    actionLabel = resources.getString(R.string.action_undo),
                    duration = SnackbarDuration.Long,
                )
                if (answer == SnackbarResult.ActionPerformed) viewModel.restore(previous)
            }
        },
    )

    if (showSaveDialog) {
        NameDialog(
            title = stringResource(R.string.save_dialog_title),
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = { name ->
                showSaveDialog = false
                scope.launch {
                    viewModel.save(name).join()
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(resources.getString(R.string.snackbar_saved))
                }
            },
            onDismiss = { showSaveDialog = false },
        )
    }
}

/**
 * The whole form is two connected groups of tiles (a 2x2 grid and a row of three) so that every
 * input is on screen at once, also with the keyboard open. Scrolling stays possible as a fallback
 * for very small windows and large font sizes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalculatorContent(
    state: CalculatorUiState,
    fields: CalculatorFields,
    carbUnit: CarbUnit,
    snackbar: SnackbarHostState,
    canScan: Boolean,
    showAaps: Boolean,
    onScan: () -> Unit,
    onSendToAaps: () -> Unit,
    onApplyWeight: () -> Unit,
    onClearProduct: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    val motion = rememberAppMotion()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val result = state.result
    val source = result.eatenSource
    val errors = result.errors

    // The keyboard takes a third of the screen: fold the large title away while it is up.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        val bar = scrollBehavior.state
        val target = if (imeVisible) bar.heightOffsetLimit else 0f
        if (target.isFinite() && target > -Float.MAX_VALUE && target != bar.heightOffset) {
            animate(bar.heightOffset, target, animationSpec = motion.snappy()) { value, _ -> bar.heightOffset = value }
        }
    }

    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val roomy = !imeVisible && windowHeight >= RoomyFromHeight
    val tileHeight by animateDpAsState(if (roomy) TileHeightRoomy else TileHeightCompact, motion.bouncy(), label = "tileHeight")

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            ResultPanel(
                carbsEaten = state.carbsEaten,
                value = result.carbsEaten,
                unitLine = unitLine(state, carbUnit),
                compact = imeVisible,
                canSave = state.canSave,
                canReset = state.hasInput || state.product != null,
                aapsGrams = state.wholeGrams.takeIf { showAaps },
                showAaps = showAaps,
                onSave = onSave,
                onSendToAaps = onSendToAaps,
                onReset = onReset,
                motion = motion,
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
                .padding(bottom = 12.dp),
        ) {
            Popping(visible = state.product != null, motion = motion) {
                // Keeps showing the last product while the row animates out.
                val product = rememberLast(state.product)
                if (product != null) ProductChips(product, onApplyWeight, onClearProduct)
            }

            Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                    NumberTile(
                        state = fields.carbsPer100.state,
                        input = fields.carbsPer100.input,
                        label = stringResource(R.string.field_carbs_per_100),
                        unit = stringResource(R.string.unit_grams),
                        corners = TileCorners(topStart = true),
                        isError = CalcField.CARBS_PER_100 in errors,
                        modifier = Modifier.weight(1f),
                        height = tileHeight,
                        motion = motion,
                        trailingIcon = if (canScan) {
                            { ScanButton(loading = state.lookup == LookupStatus.Loading, onScan = onScan) }
                        } else {
                            null
                        },
                    )
                    NumberTile(
                        state = fields.totalWeight.state,
                        input = fields.totalWeight.input,
                        label = stringResource(R.string.field_total_weight),
                        unit = stringResource(R.string.unit_grams),
                        corners = TileCorners(topEnd = true),
                        isError = CalcField.TOTAL_WEIGHT in errors,
                        modifier = Modifier.weight(1f),
                        height = tileHeight,
                        motion = motion,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                    NumberTile(
                        state = fields.pieceCount.state,
                        input = fields.pieceCount.input,
                        label = stringResource(R.string.field_piece_count),
                        unit = null,
                        corners = TileCorners(bottomStart = true),
                        isError = CalcField.PIECE_COUNT in errors,
                        modifier = Modifier.weight(1f),
                        height = tileHeight,
                        motion = motion,
                        decimal = false,
                    )
                    // Always computed, never typed, and skipped by "next" on the keyboard.
                    NumberTile(
                        state = fields.gramsPerPiece,
                        input = null,
                        label = stringResource(R.string.field_grams_per_piece),
                        unit = stringResource(R.string.unit_grams),
                        corners = TileCorners(bottomEnd = true),
                        isError = false,
                        modifier = Modifier.weight(1f),
                        height = tileHeight,
                        motion = motion,
                        derived = state.derived[CalcField.GRAMS_PER_PIECE],
                        enabled = state.derived[CalcField.GRAMS_PER_PIECE] != null,
                        readOnly = true,
                    )
                }
            }
            GroupMessage(
                message = errors.firstMessage(CalcField.CARBS_PER_100, CalcField.TOTAL_WEIGHT, CalcField.PIECE_COUNT),
                hint = state.lookup.message(),
                motion = motion,
            )

            Text(
                text = stringResource(R.string.section_eaten),
                modifier = Modifier
                    .padding(start = 4.dp, top = 12.dp, bottom = 6.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                NumberTile(
                    state = fields.eatenPercent.state,
                    input = fields.eatenPercent.input,
                    label = stringResource(R.string.field_eaten_percent_short),
                    description = stringResource(R.string.field_eaten_percent),
                    unit = null,
                    corners = TileCorners(topStart = true, bottomStart = true),
                    isError = CalcField.EATEN_PERCENT in errors,
                    modifier = Modifier.weight(1f),
                        height = tileHeight,
                    motion = motion,
                    derived = state.derived[CalcField.EATEN_PERCENT],
                    // A field the user typed into stays usable even if what it needs went missing.
                    enabled = result.percentUsable || source == EatenSource.PERCENT,
                )
                NumberTile(
                    state = fields.eatenPieces.state,
                    input = fields.eatenPieces.input,
                    label = stringResource(R.string.field_eaten_pieces_short),
                    description = stringResource(R.string.field_eaten_pieces),
                    unit = null,
                    corners = TileCorners(),
                    isError = CalcField.EATEN_PIECES in errors,
                    modifier = Modifier.weight(1f),
                        height = tileHeight,
                    motion = motion,
                    derived = state.derived[CalcField.EATEN_PIECES],
                    enabled = result.piecesUsable || source == EatenSource.PIECES,
                )
                NumberTile(
                    state = fields.eatenGrams.state,
                    input = fields.eatenGrams.input,
                    label = stringResource(R.string.field_eaten_grams_short),
                    description = stringResource(R.string.field_eaten_grams),
                    unit = null,
                    corners = TileCorners(topEnd = true, bottomEnd = true),
                    isError = CalcField.EATEN_GRAMS in errors,
                    modifier = Modifier.weight(1f),
                        height = tileHeight,
                    motion = motion,
                    derived = state.derived[CalcField.EATEN_GRAMS],
                    imeAction = ImeAction.Done,
                )
            }
            GroupMessage(
                message = errors.firstMessage(CalcField.EATEN_PERCENT, CalcField.EATEN_PIECES, CalcField.EATEN_GRAMS),
                hint = null,
                motion = motion,
            )
        }
    }
}

// --- tiles ----------------------------------------------------------------------------------

private val TileGap = 3.dp
private val TileHeightCompact = 64.dp
private val TileHeightRoomy = 84.dp

/** Vertical padding that makes label plus value exactly [TileHeightCompact] tall. */
private val TilePaddingCompact = 10.dp

/** Windows at least this tall get the roomy tiles while the keyboard is down. */
private val RoomyFromHeight = 760.dp
private val TileOuterCorner = 24.dp
private val TileInnerCorner = 6.dp

/** Which corners of a tile lie on the outside of its group and therefore get the large radius. */
private data class TileCorners(
    val topStart: Boolean = false,
    val topEnd: Boolean = false,
    val bottomStart: Boolean = false,
    val bottomEnd: Boolean = false,
)

/**
 * One number input as a tonal tile: small label on top, large value below.
 *
 * - What the user typed sits on a strong container in upright type.
 * - What the app computed ([derived]) sits on a soft container in italics. It stays editable;
 *   the first edit makes that tile the source for its siblings.
 * - The focused tile takes the primary container and rounds all four corners, the same way a
 *   connected button group marks its selection.
 *
 * @param height wanted height; the extra room is split above and below so the content stays centred.
 * @param input filter for typed text, or null for a tile that is never typed into.
 * @param description full name for screen readers where [label] is abbreviated.
 */
@Composable
private fun NumberTile(
    state: TextFieldState,
    input: InputTransformation?,
    label: String,
    unit: String?,
    corners: TileCorners,
    isError: Boolean,
    height: Dp,
    motion: AppMotion,
    modifier: Modifier = Modifier,
    description: String = label,
    derived: DisplayNumber? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    decimal: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    // Stored text is plain ("20.8"); what is drawn gets the locale's separator and, for a rounded
    // computed value, a leading "≈". Neither is part of what the user edits.
    val separator = LocalDecimalSeparator.current
    val approximate = derived?.isApproximate == true
    val shownText = remember(separator, approximate) {
        OutputTransformation {
            val dot = asCharSequence().indexOf('.')
            if (dot >= 0 && separator != '.') replace(dot, dot + 1, separator.toString())
            if (approximate && length > 0) insert(0, "$APPROXIMATELY ")
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    // Focusing a computed value selects it, so typing replaces it instead of appending to it.
    val isDerived by rememberUpdatedState(derived != null)
    LaunchedEffect(focused) {
        if (focused && isDerived && !readOnly) {
            // Let the tap place its cursor first; otherwise it would undo the selection.
            withFrameNanos { }
            state.edit { selectAll() }
        }
    }

    // Shape morph: inner corners spring open to the outer radius while the tile has focus.
    val focus by animateFloatAsState(if (focused) 1f else 0f, motion.snappy(), label = "tileFocus")
    fun corner(outer: Boolean): Dp =
        lerp(if (outer) TileOuterCorner else TileInnerCorner, TileOuterCorner, focus).coerceAtLeast(0.dp)
    val shape = RoundedCornerShape(
        topStart = corner(corners.topStart),
        topEnd = corner(corners.topEnd),
        bottomStart = corner(corners.bottomStart),
        bottomEnd = corner(corners.bottomEnd),
    )

    // A computed value gives a small nudge whenever it changes, so the link between the fields is felt.
    val pulse = remember { Animatable(1f) }
    val derivedText = derived?.text
    LaunchedEffect(derivedText) {
        if (derivedText != null && !motion.reduced && !focused) {
            pulse.snapTo(0.93f)
            pulse.animateTo(1f, motion.bouncy())
        }
    }

    val scheme = MaterialTheme.colorScheme
    val restingContainer = if (derived != null) scheme.surfaceContainer else scheme.surfaceContainerHighest
    val restingText = if (derived != null) scheme.onSurfaceVariant else scheme.onSurface
    val calculated = stringResource(R.string.state_calculated)

    TextField(
        state = state,
        modifier = modifier
            .graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            }
            .then(if (readOnly) Modifier.focusProperties { canFocus = false } else Modifier)
            .semantics { if (derived != null) stateDescription = calculated },
        enabled = enabled,
        readOnly = readOnly,
        textStyle = MaterialTheme.typography.titleLarge.copy(
            fontStyle = if (derived != null) FontStyle.Italic else FontStyle.Normal,
        ),
        labelPosition = TextFieldLabelPosition.Inside(isAlwaysMinimized = true),
        label = {
            Text(
                text = label,
                modifier = Modifier.semantics { contentDescription = description },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingIcon = trailingIcon,
        suffix = unit?.let { { Text(it, style = MaterialTheme.typography.titleMedium) } },
        isError = isError,
        inputTransformation = input,
        outputTransformation = shownText,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = imeAction,
        ),
        lineLimits = TextFieldLineLimits.SingleLine,
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedTextColor = scheme.onPrimaryContainer,
            unfocusedTextColor = restingText,
            errorTextColor = scheme.onErrorContainer,
            focusedContainerColor = scheme.primaryContainer,
            unfocusedContainerColor = restingContainer,
            disabledContainerColor = scheme.surfaceContainerLow,
            errorContainerColor = scheme.errorContainer,
            // The tile's colour and shape carry the state; no underline.
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedLabelColor = scheme.onPrimaryContainer,
            errorLabelColor = scheme.onErrorContainer,
            focusedSuffixColor = scheme.onPrimaryContainer,
            errorSuffixColor = scheme.onErrorContainer,
            errorCursorColor = scheme.onErrorContainer,
        ),
        contentPadding = TextFieldDefaults.contentPaddingWithLabel(
            start = 14.dp,
            end = 12.dp,
            top = TilePaddingCompact + (height - TileHeightCompact).coerceAtLeast(0.dp) / 2,
            bottom = TilePaddingCompact + (height - TileHeightCompact).coerceAtLeast(0.dp) / 2,
        ),
        interactionSource = interactionSource,
    )
}

@Composable
private fun ScanButton(loading: Boolean, onScan: () -> Unit) {
    if (loading) {
        LookupIndicator()
    } else {
        IconButton(onClick = onScan) {
            Icon(painterResource(R.drawable.ic_barcode_scanner), contentDescription = stringResource(R.string.action_scan))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LookupIndicator() {
    val description = stringResource(R.string.lookup_loading)
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        LoadingIndicator(
            Modifier
                .size(32.dp)
                .semantics { contentDescription = description },
        )
    }
}

/** One line under a group of tiles: the first error in it, or else a neutral [hint]. */
@Composable
private fun GroupMessage(message: String?, hint: String?, motion: AppMotion) {
    val text = message ?: hint
    Popping(visible = text != null, motion = motion) {
        val shown = rememberLast(text).orEmpty()
        val isError = rememberLast(text?.let { message != null }) ?: false
        Text(
            text = shown,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Content that pops in and out: it grows into place with a bounce instead of just fading. */
@Composable
private fun Popping(visible: Boolean, motion: AppMotion, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(motion.bouncy()) + scaleIn(motion.bouncy(), initialScale = 0.8f) + fadeIn(motion.effects()),
        exit = shrinkVertically(motion.snappy()) + scaleOut(motion.snappy(), targetScale = 0.8f) + fadeOut(motion.effects()),
    ) {
        content()
    }
}

/** The latest non-null [value], so content can still be drawn while it animates away. */
@Composable
private fun <T : Any> rememberLast(value: T?): T? {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    return last
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductChips(product: ProductInfo, onApplyWeight: () -> Unit, onClear: () -> Unit) {
    val separator = LocalDecimalSeparator.current
    FlowRow(
        modifier = Modifier.padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InputChip(
            selected = true,
            onClick = onClear,
            label = { Text(product.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                Icon(painterResource(R.drawable.ic_nutrition), contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize))
            },
            trailingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.action_remove_product),
                    modifier = Modifier.size(InputChipDefaults.IconSize),
                )
            },
        )
        product.suggestedWeight?.let { weight ->
            SuggestionChip(
                onClick = onApplyWeight,
                label = { Text(stringResource(R.string.product_weight_suggestion, weight.withSeparator(separator))) },
                icon = { Icon(painterResource(R.drawable.ic_scale), contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

// --- result ---------------------------------------------------------------------------------

/**
 * Result and actions on a tonal sheet docked above the navigation bar, so the number stays in
 * view while typing. [compact] (keyboard up) drops the buttons: the result matters more.
 */
@Composable
private fun ResultPanel(
    carbsEaten: DisplayNumber?,
    value: Fraction?,
    unitLine: String?,
    compact: Boolean,
    canSave: Boolean,
    canReset: Boolean,
    aapsGrams: Int?,
    showAaps: Boolean,
    onSave: () -> Unit,
    onSendToAaps: () -> Unit,
    onReset: () -> Unit,
    motion: AppMotion,
) {
    val separator = LocalDecimalSeparator.current
    val spoken = when {
        carbsEaten == null -> stringResource(R.string.a11y_result_none)
        carbsEaten.isApproximate -> stringResource(R.string.a11y_result_approx, carbsEaten.text.withSeparator(separator))
        else -> stringResource(R.string.a11y_result, carbsEaten.text.withSeparator(separator))
    }
    val shown = carbsEaten?.let { stringResource(R.string.result_grams, it.shown(separator)) } ?: stringResource(R.string.result_none)

    // Digits roll upwards when the result grows and downwards when it shrinks.
    var previous by remember { mutableStateOf(value) }
    var rollUp by remember { mutableStateOf(true) }
    if (value != previous) {
        val before = previous
        rollUp = before == null || (value != null && value >= before)
        previous = value
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp)
                    .clearAndSetSemantics {
                        contentDescription = spoken
                        liveRegion = LiveRegionMode.Polite
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.result_label),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(horizontalAlignment = Alignment.End) {
                    RollingNumber(
                        text = shown,
                        up = rollUp,
                        style = MaterialTheme.typography.displayMediumEmphasized,
                        color = if (carbsEaten == null) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary,
                    )
                    Popping(visible = unitLine != null, motion = motion) {
                        Text(
                            text = rememberLast(unitLine).orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Popping(visible = !compact, motion = motion) {
                ActionButtons(
                    canSave = canSave,
                    canReset = canReset,
                    aapsGrams = aapsGrams,
                    showAaps = showAaps,
                    onSave = onSave,
                    onSendToAaps = onSendToAaps,
                    onReset = onReset,
                )
            }
        }
    }
}

/**
 * Expressive button group: the pressed button grows, its neighbour gives way, both morph shape.
 *
 * Normally "save" shares the row with "reset". With AAPS installed, the hand-off takes that place
 * next to "save" and "reset" moves to a quieter row of its own, so all three stay one tap away on
 * narrow phones too.
 *
 * @param aapsGrams the amount the AAPS button would hand over, or null while there is no result.
 */
@Composable
private fun ActionButtons(
    canSave: Boolean,
    canReset: Boolean,
    aapsGrams: Int?,
    showAaps: Boolean,
    onSave: () -> Unit,
    onSendToAaps: () -> Unit,
    onReset: () -> Unit,
) {
    val height = ButtonDefaults.MediumContainerHeight
    val saveLabel = stringResource(R.string.action_save)
    val resetLabel = stringResource(R.string.action_reset)
    // The label states the rounding: "13 g an aaps" is exactly what lands on the clipboard.
    val aapsLabel = aapsGrams?.let { stringResource(R.string.action_send_aaps_amount, it) }
        ?: stringResource(R.string.action_send_aaps)
    // Nothing to paste for no result or for zero grams.
    val canSend = aapsGrams != null && aapsGrams > 0
    // Two equally wide buttons need slimmer padding to keep icon and label on one line.
    val padding = if (showAaps) PaddingValues(horizontal = 16.dp) else ButtonDefaults.contentPaddingFor(height)

    Column {
        ButtonGroup(
            overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (showAaps) 4.dp else 12.dp),
            // More give than the default, so the squeeze between the two buttons is clearly visible.
            expandedRatio = 0.25f,
        ) {
            customItem(
                buttonGroupContent = {
                    val interactionSource = remember { MutableInteractionSource() }
                    Button(
                        onClick = onSave,
                        shapes = ButtonDefaults.shapesFor(height),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = height)
                            .animateWidth(interactionSource),
                        enabled = canSave,
                        contentPadding = padding,
                        interactionSource = interactionSource,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_bookmark_add),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
                        )
                        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
                        Text(saveLabel, style = ButtonDefaults.textStyleFor(height), maxLines = 1)
                    }
                },
                menuContent = { menuState ->
                    DropdownMenuItem(
                        text = { Text(saveLabel) },
                        onClick = {
                            menuState.dismiss()
                            onSave()
                        },
                        enabled = canSave,
                    )
                },
            )
            if (showAaps) {
                customItem(
                    buttonGroupContent = {
                        val interactionSource = remember { MutableInteractionSource() }
                        FilledTonalButton(
                            onClick = onSendToAaps,
                            shapes = ButtonDefaults.shapesFor(height),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = height)
                                .animateWidth(interactionSource),
                            enabled = canSend,
                            contentPadding = padding,
                            interactionSource = interactionSource,
                        ) {
                            Text(aapsLabel, style = ButtonDefaults.textStyleFor(height), maxLines = 1)
                        }
                    },
                    menuContent = { menuState ->
                        DropdownMenuItem(
                            text = { Text(aapsLabel) },
                            onClick = {
                                menuState.dismiss()
                                onSendToAaps()
                            },
                            enabled = canSend,
                        )
                    },
                )
            } else {
                customItem(
                    buttonGroupContent = {
                        val interactionSource = remember { MutableInteractionSource() }
                        TextButton(
                            onClick = onReset,
                            shapes = ButtonDefaults.shapesFor(height),
                            modifier = Modifier
                                .heightIn(min = height)
                                .animateWidth(interactionSource),
                            enabled = canReset,
                            contentPadding = padding,
                            interactionSource = interactionSource,
                        ) {
                            Text(resetLabel, style = ButtonDefaults.textStyleFor(height), maxLines = 1)
                        }
                    },
                    menuContent = { menuState ->
                        DropdownMenuItem(
                            text = { Text(resetLabel) },
                            onClick = {
                                menuState.dismiss()
                                onReset()
                            },
                            enabled = canReset,
                        )
                    },
                )
            }
        }
        if (showAaps) {
            TextButton(
                onClick = onReset,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                enabled = canReset,
            ) {
                Text(resetLabel)
            }
        }
    }
}

// --- text -----------------------------------------------------------------------------------

@Composable
private fun unitLine(state: CalculatorUiState, unit: CarbUnit): String? {
    val converted = state.result.carbsEaten?.let(unit::convert) ?: return null
    val shown = DecimalText.format(converted).shown(LocalDecimalSeparator.current)
    return when (unit) {
        CarbUnit.KE -> stringResource(R.string.result_unit_ke, shown)
        CarbUnit.BE -> stringResource(R.string.result_unit_be, shown)
        CarbUnit.NONE -> null
    }
}

@Composable
private fun LookupStatus.message(): String? = when (this) {
    LookupStatus.Idle, LookupStatus.Loading -> null
    LookupStatus.NotFound -> stringResource(R.string.lookup_not_found)
    is LookupStatus.NoCarbs -> stringResource(R.string.lookup_no_carbs, name)
    LookupStatus.Offline -> stringResource(R.string.lookup_offline)
}

/** Message for the first of [fields] that has an error, prefixed with the field it belongs to. */
@Composable
private fun Map<CalcField, CalcError>.firstMessage(vararg fields: CalcField): String? {
    val field = fields.firstOrNull { it in this } ?: return null
    val error = getValue(field)
    val name = stringResource(
        when (field) {
            CalcField.CARBS_PER_100 -> R.string.field_carbs_per_100
            CalcField.TOTAL_WEIGHT -> R.string.field_total_weight
            CalcField.PIECE_COUNT -> R.string.field_piece_count
            CalcField.GRAMS_PER_PIECE -> R.string.field_grams_per_piece
            CalcField.EATEN_PERCENT -> R.string.field_eaten_percent
            CalcField.EATEN_PIECES -> R.string.field_eaten_pieces
            CalcField.EATEN_GRAMS -> R.string.field_eaten_grams
        },
    )
    val problem = stringResource(
        when (error) {
            CalcError.INVALID_NUMBER -> R.string.error_invalid_number
            CalcError.NEGATIVE -> R.string.error_negative
            CalcError.NOT_POSITIVE -> R.string.error_not_positive
            CalcError.NOT_WHOLE -> R.string.error_not_whole
            CalcError.ABOVE_100 -> R.string.error_above_100
            CalcError.EXCEEDS_TOTAL -> when (field) {
                CalcField.EATEN_PERCENT -> R.string.error_exceeds_percent
                CalcField.EATEN_PIECES -> R.string.error_exceeds_pieces
                else -> R.string.error_exceeds_grams
            }
            CalcError.NEEDS_TOTAL_WEIGHT -> R.string.error_needs_total_weight
            CalcError.NEEDS_PIECE_WEIGHT -> R.string.error_needs_piece_weight
        },
    )
    return stringResource(R.string.error_in_field, name, problem)
}
