package app.brokoli5191.khrechner.ui.scanner

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.brokoli5191.khrechner.data.FrameScanner
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

/** What the camera reports while the built-in scanner is open. */
sealed interface CameraEvent {
    /** The picture is live. */
    data class Started(val hasTorch: Boolean) : CameraEvent

    /** A barcode two frames in a row agreed on. Reported once. */
    data class Code(val barcode: String) : CameraEvent

    /** The camera could not be used, for example because another app holds it. */
    data class Failed(val error: Exception) : CameraEvent
}

/** The eye of the built-in scanner. Tests swap it for a picture that sees what they tell it to. */
interface CameraScanner {

    /** False on devices without a camera; nothing can scan there and the scan button is hidden. */
    val isAvailable: Boolean

    /**
     * Shows the live picture and looks for a barcode in it for as long as it is on screen.
     * Requires the camera permission.
     */
    @Composable
    fun Viewfinder(torch: Boolean, onEvent: (CameraEvent) -> Unit, modifier: Modifier)
}

/** CameraX for the picture, ZXing ([FrameScanner]) for reading it. Frames never leave the device. */
class CameraXScanner(context: Context) : CameraScanner {

    override val isAvailable: Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    @Composable
    override fun Viewfinder(torch: Boolean, onEvent: (CameraEvent) -> Unit, modifier: Modifier) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val currentOnEvent by rememberUpdatedState(onEvent)
        var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
        var camera by remember { mutableStateOf<Camera?>(null) }

        LaunchedEffect(lifecycleOwner) {
            val preview = Preview.Builder().build()
            val analysis = ImageAnalysis.Builder()
                // The default, 640x480, leaves a small code barely one pixel per bar.
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val analysisThread = Executors.newSingleThreadExecutor()
            val mainThread = ContextCompat.getMainExecutor(context)
            var provider: ProcessCameraProvider? = null
            try {
                provider = ProcessCameraProvider.awaitInstance(context.applicationContext)
                preview.setSurfaceProvider { request -> surfaceRequest = request }

                val frames = FrameScanner()
                var finished = false // touched on the analysis thread only
                analysis.setAnalyzer(analysisThread) { image ->
                    image.use {
                        if (finished) return@use
                        val event = try {
                            val plane = image.planes[0]
                            // The sensor is mounted sideways in a phone; CameraX says by how much.
                            val sideways = image.imageInfo.rotationDegrees % 180 != 0
                            frames.scan(plane.buffer, plane.rowStride, image.width, image.height, sideways)?.let(CameraEvent::Code)
                        } catch (e: RuntimeException) {
                            // A frame layout the reader cannot handle would otherwise take the app down.
                            Log.w(TAG, "frame could not be read", e)
                            CameraEvent.Failed(e)
                        }
                        if (event != null) {
                            finished = true
                            mainThread.execute { currentOnEvent(event) }
                        }
                    }
                }

                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                val bound = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                camera = bound
                currentOnEvent(CameraEvent.Started(hasTorch = bound.cameraInfo.hasFlashUnit()))
                awaitCancellation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "camera could not be started", e)
                currentOnEvent(CameraEvent.Failed(e))
            } finally {
                analysis.clearAnalyzer()
                provider?.unbind(preview, analysis)
                analysisThread.shutdown()
            }
        }

        LaunchedEffect(camera, torch) {
            camera?.takeIf { it.cameraInfo.hasFlashUnit() }?.cameraControl?.enableTorch(torch)
        }

        val request = surfaceRequest
        if (request == null) {
            Box(modifier)
        } else {
            CameraXViewfinder(surfaceRequest = request, modifier = modifier, contentScale = ContentScale.Crop)
        }
    }

    private companion object {
        const val TAG = "CameraXScanner"
        val ANALYSIS_SIZE = Size(1920, 1080)
    }
}
