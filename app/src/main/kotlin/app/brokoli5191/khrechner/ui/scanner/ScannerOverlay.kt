package app.brokoli5191.khrechner.ui.scanner

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.brokoli5191.khrechner.R
import app.brokoli5191.khrechner.ui.theme.AppMotion
import app.brokoli5191.khrechner.ui.theme.bounceOnPress
import app.brokoli5191.khrechner.ui.theme.popIn
import app.brokoli5191.khrechner.ui.theme.rememberAppMotion
import kotlinx.coroutines.delay

/** Where the app stands with the camera permission while the scanner is open. */
enum class CameraPermission { GRANTED, ASKING, DENIED }

/** What the scanner is doing once it may use the camera. */
sealed interface ScannerPhase {
    data object Starting : ScannerPhase
    data class Searching(val hasTorch: Boolean) : ScannerPhase
    data class Found(val barcode: String) : ScannerPhase

    /** @param detail the camera's own error text, shown as it is for bug reports. */
    data class Failed(val detail: String) : ScannerPhase
}

/**
 * The scanner built into the app, shown over everything else. It is used where Google's scanner
 * cannot run; unlike that one it needs the camera permission, which it asks for when it opens.
 *
 * @param onCode called once with the barcode; the caller closes the scanner.
 */
@Composable
fun ScannerOverlay(visible: Boolean, camera: CameraScanner, onCode: (String) -> Unit, onClose: () -> Unit) {
    val motion = rememberAppMotion()
    AnimatedVisibility(
        visible = visible,
        enter = if (motion.reduced) EnterTransition.None else scaleIn(motion.bouncy(), initialScale = 0.88f) + fadeIn(motion.effects()),
        exit = if (motion.reduced) ExitTransition.None else scaleOut(motion.snappy(), targetScale = 0.92f) + fadeOut(motion.effects()),
    ) {
        ScannerHost(camera, onCode, onClose)
    }
}

@Composable
private fun ScannerHost(camera: CameraScanner, onCode: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val haptics = LocalHapticFeedback.current
    val motion = rememberAppMotion()

    var permission by remember {
        mutableStateOf(if (context.hasCameraPermission()) CameraPermission.GRANTED else CameraPermission.ASKING)
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = if (granted) CameraPermission.GRANTED else CameraPermission.DENIED
    }
    LaunchedEffect(Unit) {
        if (permission == CameraPermission.ASKING) permissionRequest.launch(Manifest.permission.CAMERA)
    }
    // The user may have granted the permission in the system settings and come back.
    LifecycleResumeEffect(Unit) {
        if (permission == CameraPermission.DENIED && context.hasCameraPermission()) permission = CameraPermission.GRANTED
        onPauseOrDispose { }
    }

    var torch by rememberSaveable { mutableStateOf(false) }
    var phase by remember { mutableStateOf<ScannerPhase>(ScannerPhase.Starting) }

    // The scan button sits in a text field; its keyboard would cover the hint.
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    BackHandler(onBack = onClose)
    LightSystemBarIcons()

    (phase as? ScannerPhase.Found)?.let { found ->
        LaunchedEffect(found) {
            // Long enough to see what was read before the scanner gets out of the way.
            if (!motion.reduced) delay(FOUND_PAUSE_MILLIS)
            onCode(found.barcode)
        }
    }

    ScannerContent(
        permission = permission,
        phase = phase,
        torch = torch,
        viewfinder = { modifier ->
            camera.Viewfinder(
                torch = torch,
                onEvent = { event ->
                    when (event) {
                        is CameraEvent.Started -> if (phase == ScannerPhase.Starting) phase = ScannerPhase.Searching(event.hasTorch)
                        is CameraEvent.Code -> if (phase !is ScannerPhase.Found) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            phase = ScannerPhase.Found(event.barcode)
                        }
                        is CameraEvent.Failed -> phase = ScannerPhase.Failed(event.error.describe())
                    }
                },
                modifier = modifier,
            )
        },
        onToggleTorch = { torch = !torch },
        onAllowCamera = {
            // Android stops showing its dialog after the second "no"; from then on only the settings help.
            if (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
                permissionRequest.launch(Manifest.permission.CAMERA)
            } else {
                context.openAppSettings()
            }
        },
        onClose = onClose,
    )
}

/** The scanner's screen without its camera and permission plumbing, so it can be shown in any state. */
@Composable
internal fun ScannerContent(
    permission: CameraPermission,
    phase: ScannerPhase,
    torch: Boolean,
    viewfinder: @Composable (Modifier) -> Unit,
    onToggleTorch: () -> Unit,
    onAllowCamera: () -> Unit,
    onClose: () -> Unit,
) {
    val motion = rememberAppMotion()
    val title = stringResource(R.string.action_scan)
    val showsCamera = permission == CameraPermission.GRANTED && phase !is ScannerPhase.Failed

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Nothing underneath may be touched while the scanner is open.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .semantics { paneTitle = title },
    ) {
        if (showsCamera) {
            viewfinder(Modifier.fillMaxSize())
            ScanWindow(found = phase is ScannerPhase.Found, motion = motion)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val closeSource = remember { MutableInteractionSource() }
            FilledTonalIconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(56.dp)
                    .bounceOnPress(closeSource),
                interactionSource = closeSource,
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close_scanner))
            }
            if (showsCamera && (phase as? ScannerPhase.Searching)?.hasTorch == true) {
                val torchSource = remember { MutableInteractionSource() }
                FilledTonalIconToggleButton(
                    checked = torch,
                    onCheckedChange = { onToggleTorch() },
                    modifier = Modifier
                        .size(56.dp)
                        .bounceOnPress(torchSource)
                        .popIn(),
                    interactionSource = torchSource,
                ) {
                    Icon(
                        painter = painterResource(if (torch) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off),
                        contentDescription = stringResource(if (torch) R.string.action_torch_off else R.string.action_torch_on),
                    )
                }
            }
        }

        when {
            permission == CameraPermission.DENIED -> MessageCard(
                icon = R.drawable.ic_no_photography,
                title = stringResource(R.string.scanner_permission_title),
                text = stringResource(R.string.scanner_permission_text),
                action = stringResource(R.string.action_allow_camera),
                onAction = onAllowCamera,
                modifier = Modifier.align(Alignment.Center),
            )
            permission == CameraPermission.ASKING -> Unit
            phase is ScannerPhase.Failed -> MessageCard(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.scanner_camera_failed_title),
                text = stringResource(R.string.scanner_camera_failed_text, phase.detail),
                action = stringResource(R.string.action_close_scanner),
                onAction = onClose,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> StatusPill(
                phase = phase,
                motion = motion,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(24.dp),
            )
        }
    }
}

/**
 * Darkens the picture except for a window in the middle. The window springs open, a line sweeps
 * through it while the scanner searches, and it snaps tight in the theme colour once a code is read.
 */
@Composable
private fun ScanWindow(found: Boolean, motion: AppMotion) {
    val opening = remember { Animatable(if (motion.reduced) 1f else 0.6f) }
    LaunchedEffect(Unit) { opening.animateTo(1f, motion.bouncy()) }
    val grip by animateFloatAsState(if (found) 0.9f else 1f, motion.bouncy(), label = "grip")
    // The picture is dark whatever the theme, so the accent is the light one of the two primaries.
    val colors = MaterialTheme.colorScheme
    val accent = if (colors.primary.luminance() > colors.inversePrimary.luminance()) colors.primary else colors.inversePrimary
    val frame by animateColorAsState(if (found) accent else Color.White, motion.effects(), label = "frame")

    val sweep = if (motion.reduced || found) {
        null
    } else {
        rememberInfiniteTransition(label = "sweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(SWEEP_MILLIS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "sweep",
        )
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = minOf(size.width - 48.dp.toPx(), 380.dp.toPx()) * opening.value * grip
            val height = width * WINDOW_ASPECT
            val window = Rect(Offset((size.width - width) / 2, (size.height - height) / 2), Size(width, height))
            val outline = Path().apply { addRoundRect(RoundRect(window, CornerRadius(28.dp.toPx()))) }
            clipPath(outline, clipOp = ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.6f)) }
            drawPath(outline, frame, style = Stroke(width = 4.dp.toPx()))
            if (sweep != null) {
                val inset = 20.dp.toPx()
                val y = window.top + inset + (window.height - 2 * inset) * sweep.value
                drawLine(accent, Offset(window.left + inset, y), Offset(window.right - inset, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        AnimatedVisibility(
            visible = found,
            enter = if (motion.reduced) EnterTransition.None else scaleIn(motion.bouncy(), initialScale = 0.3f) + fadeIn(motion.effects()),
            exit = ExitTransition.None,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                modifier = Modifier
                    .size(88.dp)
                    .background(MaterialTheme.colorScheme.onPrimary, CircleShape),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** One line at the bottom: what to do, or what was read. */
@Composable
private fun StatusPill(phase: ScannerPhase, motion: AppMotion, modifier: Modifier = Modifier) {
    val text = when (phase) {
        ScannerPhase.Starting -> stringResource(R.string.scanner_starting)
        is ScannerPhase.Found -> phase.barcode
        else -> stringResource(R.string.scanner_hint)
    }
    Surface(
        modifier = modifier.popIn(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                if (motion.reduced) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (scaleIn(motion.bouncy(), initialScale = 0.8f) + fadeIn(motion.effects())) togetherWith
                        (scaleOut(motion.snappy(), targetScale = 0.8f) + fadeOut(motion.effects()))
                }
            },
            contentAlignment = Alignment.Center,
            label = "status",
        ) { line ->
            Text(
                text = line,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Takes the place of the picture when there is none to show, with the one thing that helps. */
@Composable
private fun MessageCard(
    icon: Int,
    title: String,
    text: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .padding(24.dp)
            .widthIn(max = 420.dp)
            .popIn(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            val source = remember { MutableInteractionSource() }
            Button(onClick = onAction, modifier = Modifier.bounceOnPress(source), interactionSource = source) { Text(action) }
        }
    }
}

/** The picture underneath is dark whatever the theme, so the system bar icons have to be light. */
@Composable
private fun LightSystemBarIcons() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window, view) {
        val bars = WindowCompat.getInsetsController(window, view)
        val statusWasLight = bars.isAppearanceLightStatusBars
        val navigationWasLight = bars.isAppearanceLightNavigationBars
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false
        onDispose {
            bars.isAppearanceLightStatusBars = statusWasLight
            bars.isAppearanceLightNavigationBars = navigationWasLight
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A device without that settings page; the card keeps saying what is needed.
    }
}

private fun Exception.describe(): String {
    val name = javaClass.simpleName.ifEmpty { javaClass.name }
    return message?.takeIf(String::isNotBlank)?.let { "$name: ${it.trim()}" } ?: name
}

private const val FOUND_PAUSE_MILLIS = 450L
private const val SWEEP_MILLIS = 1400

/** Height of the scan window relative to its width; a retail barcode is wider than tall. */
private const val WINDOW_ASPECT = 0.6f
