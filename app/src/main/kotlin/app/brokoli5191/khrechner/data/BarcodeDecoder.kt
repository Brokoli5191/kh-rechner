package app.brokoli5191.khrechner.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.oned.MultiFormatUPCEANReader
import java.nio.ByteBuffer

/**
 * Finds a retail barcode (EAN-13, EAN-8, UPC-A, UPC-E) in a greyscale picture. Plain JVM code on
 * top of ZXing, so it is tested without a camera.
 *
 * Not thread-safe: it reuses its buffers between calls.
 */
class BarcodeDecoder {

    private val hints = mapOf(
        // UPC-A needs no entry: ZXing reads it as an EAN-13 with a leading zero, which is also how
        // Open Food Facts files those products.
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_E),
        // Look at every second row instead of fifteen; a phone has the time.
        DecodeHintType.TRY_HARDER to true,
    )

    // Only the UPC/EAN readers, so the rest of ZXing (QR, PDF417, ...) is stripped from the app.
    private val reader = MultiFormatUPCEANReader(hints)
    private var turned = ByteArray(0)

    /**
     * @param luminance one byte per pixel, row after row, at least [width] * [height] bytes.
     * @param sideways true if the picture is a quarter turn away from how the user sees it, as
     *   camera frames are while the phone is held upright. Only decides which way is tried first.
     * @return the digits of the code, or null if none can be read.
     */
    fun decode(luminance: ByteArray, width: Int, height: Int, sideways: Boolean = false): String? {
        require(luminance.size >= width * height) { "frame of ${luminance.size} bytes is smaller than $width x $height" }
        // ZXing reads along the rows only, and the bars have to cross them.
        return if (sideways) {
            readTurned(luminance, width, height) ?: read(luminance, width, height)
        } else {
            read(luminance, width, height) ?: readTurned(luminance, width, height)
        }
    }

    private fun read(luminance: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun readTurned(luminance: ByteArray, width: Int, height: Int): String? {
        if (turned.size < width * height) turned = ByteArray(width * height)
        // A quarter turn clockwise. The direction does not matter: a barcode reads both ways.
        var index = 0
        for (y in 0 until height) {
            var target = height - 1 - y
            for (x in 0 until width) {
                turned[target] = luminance[index++]
                target += height
            }
        }
        return read(turned, height, width)
    }
}

/**
 * Turns a stream of camera frames into one barcode. A code counts once two frames in a row agree
 * on it: a single reading of a blurred frame can be wrong, and the check digit does not catch
 * every misread.
 */
class FrameScanner(private val decoder: BarcodeDecoder = BarcodeDecoder()) {

    private var pixels = ByteArray(0)
    private var candidate: String? = null

    /**
     * @param plane the brightness (Y) plane of a camera frame, starting at its first pixel.
     * @param rowStride bytes from one row to the next in [plane]; cameras pad rows beyond [width].
     * @return the confirmed code, or null to keep looking.
     */
    fun scan(plane: ByteBuffer, rowStride: Int, width: Int, height: Int, sideways: Boolean): String? {
        val code = decoder.decode(pack(plane, rowStride, width, height), width, height, sideways)
        val confirmed = code != null && code == candidate
        candidate = code
        return if (confirmed) code else null
    }

    /** Copies the frame without its row padding. */
    private fun pack(plane: ByteBuffer, rowStride: Int, width: Int, height: Int): ByteArray {
        if (pixels.size < width * height) pixels = ByteArray(width * height)
        val source = plane.duplicate()
        val start = source.position()
        for (row in 0 until height) {
            source.position(start + row * rowStride)
            source.get(pixels, row * width, width)
        }
        return pixels
    }
}
