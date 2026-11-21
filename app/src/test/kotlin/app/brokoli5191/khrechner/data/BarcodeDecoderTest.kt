package app.brokoli5191.khrechner.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Feeds the decoder synthetic camera frames: a printed barcode somewhere on a grey background. */
class BarcodeDecoderTest {

    private class Frame(val pixels: ByteArray, val width: Int, val height: Int)

    /**
     * A [width] x [height] frame with [code] printed on it, [barWidth] pixels per narrow bar.
     * With [noise] the picture is grainy the way a camera frame is, same grain on every call.
     */
    private fun frame(
        code: String,
        format: BarcodeFormat,
        width: Int = 1280,
        height: Int = 720,
        barWidth: Int = 3,
        noise: Int = 0,
    ): Frame {
        // Width 0 makes the writer return one pixel per bar, quiet zone included.
        val bars = MultiFormatWriter().encode(code, format, 0, 1)
        val random = Random(7)
        val pixels = ByteArray(width * height) { (PAPER + grain(random, noise)).toByte() }
        val left = (width - bars.width * barWidth) / 2
        val top = height / 3
        for (y in top until top + height / 4) {
            for (bar in 0 until bars.width) {
                if (!bars[bar, 0]) continue
                for (x in left + bar * barWidth until left + (bar + 1) * barWidth) {
                    pixels[y * width + x] = (INK + grain(random, noise)).toByte()
                }
            }
        }
        return Frame(pixels, width, height)
    }

    private fun grain(random: Random, noise: Int): Int = if (noise == 0) 0 else random.nextInt(-noise, noise + 1)

    /** The same picture as the sensor delivers it while the phone is held upright. */
    private fun Frame.quarterTurned(): Frame {
        val turned = ByteArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) turned[x * height + (height - 1 - y)] = pixels[y * width + x]
        return Frame(turned, height, width)
    }

    private fun decode(frame: Frame, sideways: Boolean = false): String? =
        BarcodeDecoder().decode(frame.pixels, frame.width, frame.height, sideways)

    @Test
    fun `reads an ean-13`() {
        assertEquals("4006381333931", decode(frame("4006381333931", BarcodeFormat.EAN_13)))
    }

    @Test
    fun `reads an ean-8`() {
        assertEquals("96385074", decode(frame("96385074", BarcodeFormat.EAN_8)))
    }

    @Test
    fun `reads a upc-a as the ean-13 it is`() {
        assertEquals("0036000291452", decode(frame("036000291452", BarcodeFormat.UPC_A)))
    }

    @Test
    fun `reads a code whose bars run along the rows, whichever way is tried first`() {
        val frame = frame("9001234567896", BarcodeFormat.EAN_13).quarterTurned()
        assertEquals("9001234567896", decode(frame, sideways = true))
        assertEquals("9001234567896", decode(frame, sideways = false))
    }

    @Test
    fun `reads a small code in a grainy frame`() {
        val frame = frame("4006381333931", BarcodeFormat.EAN_13, barWidth = 2, noise = 18)
        assertEquals("4006381333931", decode(frame))
    }

    @Test
    fun `reads a frame of the size the camera delivers`() {
        val frame = frame("4006381333931", BarcodeFormat.EAN_13, width = 1920, height = 1080, barWidth = 2, noise = 12).quarterTurned()
        assertEquals("4006381333931", decode(frame, sideways = true))
    }

    @Test
    fun `finds nothing where there is nothing`() {
        val blank = Frame(ByteArray(640 * 480) { PAPER.toByte() }, 640, 480)
        assertNull(decode(blank))
        val random = Random(1)
        val grainy = Frame(ByteArray(640 * 480) { (PAPER + grain(random, 40)).toByte() }, 640, 480)
        assertNull(decode(grainy))
    }

    @Test
    fun `one decoder reads frames of different sizes one after the other`() {
        val decoder = BarcodeDecoder()
        val large = frame("4006381333931", BarcodeFormat.EAN_13).quarterTurned()
        val small = frame("96385074", BarcodeFormat.EAN_8, width = 640, height = 480, barWidth = 2).quarterTurned()
        assertEquals("4006381333931", decoder.decode(large.pixels, large.width, large.height, sideways = true))
        assertEquals("96385074", decoder.decode(small.pixels, small.width, small.height, sideways = true))
    }

    // --- FrameScanner -----------------------------------------------------------------------

    /** The frame as a camera hands it over: every row padded to [stride] bytes, behind [offset] bytes of other data. */
    private fun Frame.asPlane(stride: Int, offset: Int = 0): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(offset + stride * height)
        for (row in 0 until height) {
            buffer.position(offset + row * stride)
            buffer.put(pixels, row * width, width)
        }
        buffer.position(offset)
        return buffer
    }

    private fun FrameScanner.scan(frame: Frame, stride: Int = frame.width, offset: Int = 0): String? =
        scan(frame.asPlane(stride, offset), stride, frame.width, frame.height, sideways = false)

    @Test
    fun `a code counts once two frames in a row agree on it`() {
        val scanner = FrameScanner()
        val frame = frame("4006381333931", BarcodeFormat.EAN_13)
        assertNull(scanner.scan(frame))
        assertEquals("4006381333931", scanner.scan(frame))
    }

    @Test
    fun `a different code or an empty frame in between starts the count again`() {
        val scanner = FrameScanner()
        val first = frame("4006381333931", BarcodeFormat.EAN_13)
        val second = frame("9001234567896", BarcodeFormat.EAN_13)
        val blank = Frame(ByteArray(1280 * 720) { PAPER.toByte() }, 1280, 720)
        assertNull(scanner.scan(first))
        assertNull(scanner.scan(second))
        assertNull(scanner.scan(blank))
        assertNull(scanner.scan(second))
        assertEquals("9001234567896", scanner.scan(second))
    }

    @Test
    fun `row padding and a buffer that does not start at zero are handled`() {
        val scanner = FrameScanner()
        val frame = frame("4006381333931", BarcodeFormat.EAN_13)
        val plane = frame.asPlane(stride = 1344, offset = 64)
        assertNull(scanner.scan(plane, 1344, frame.width, frame.height, sideways = false))
        // Reading must not have moved the caller's buffer.
        assertEquals(64, plane.position())
        assertEquals("4006381333931", scanner.scan(plane, 1344, frame.width, frame.height, sideways = false))
    }

    private companion object {
        const val PAPER = 200
        const val INK = 40
    }
}
