import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.canvas.painters.LinearGradient
import com.sksamuel.scrimage.nio.PngWriter
import com.sksamuel.scrimage.nio.StreamingGifWriter
import com.target.slack.ImageHelp
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Duration
import kotlin.random.Random

class TestImageHelp {
    @Test
    fun testPositiveGifDetection() {
        val actualBytes = exampleAnimatedGifImage()
        assert(ImageHelp.isGif(actualBytes))
    }

    @Test
    fun testNegativeGifDetection() {
        val pngBytes = examplePngImage()
        assert(!ImageHelp.isGif(pngBytes))
    }

    @Test
    fun testPositiveAnimatedGifDetection() {
        val actualBytes = exampleAnimatedGifImage()
        assert(ImageHelp.isAnimatedGif(actualBytes))
    }

    @Test
    fun testNegativeAnimatedGifDetection() {
        val staticGifBytes = exampleNonAnimatedGifImage()
        assert(!ImageHelp.isAnimatedGif(staticGifBytes))
    }

    @Test
    fun testTooLargeAnimatedGifDetetection() {
        val actualBytes = exampleLargeAnimatedGifImage()
        val warnings = ImageHelp.checkSize(actualBytes)
        var containsWarning = false
        warnings.forEach {
            if (it.startsWith("The animated GIF image file size is larger than 128 KB")) {
                containsWarning = true
            }
        }
        assert(containsWarning)
    }

    @Test
    fun testTooLongAnimatedGifDetetection() {
        val actualBytes = exampleLongAnimatedGifImage()
        val warnings = ImageHelp.checkSize(actualBytes)
        assert(warnings.contains("The animated GIF image has more than 50 frames (60), Slack will reject it."))
    }

    private fun exampleAnimatedGifImage(): ByteArray {
        val frame1 = ImmutableImage.create(127, 127).fill(Color.BLUE)
        val frame2 = ImmutableImage.create(127, 127).fill(Color.RED)
        val writer = StreamingGifWriter(Duration.ofMillis(1000 / 60), true, true)
        val bytes = ByteArrayOutputStream()
        with(writer.prepareStream(bytes, BufferedImage.TYPE_INT_ARGB)) {
            writeFrame(frame1)
            writeFrame(frame2)
            close()
        }
        return bytes.toByteArray()
    }

    private fun exampleLongAnimatedGifImage(): ByteArray {
        val writer = StreamingGifWriter(Duration.ofMillis(1000 / 60), true, true)
        val bytes = ByteArrayOutputStream()
        with(writer.prepareStream(bytes, BufferedImage.TYPE_INT_ARGB)) {
            for (i in 0..59) {
                writeFrame(ImmutableImage.create(128, 128).fill(Color.BLUE))
            }
            close()
        }
        return bytes.toByteArray()
    }

    private fun exampleLargeAnimatedGifImage(): ByteArray {
        val writer = StreamingGifWriter(Duration.ofMillis(1000 / 60), true, true)
        val bytes = ByteArrayOutputStream()
        with(writer.prepareStream(bytes, BufferedImage.TYPE_INT_ARGB)) {
            for (i in 0..49) {
                writeFrame(
                    ImmutableImage.create(512, 512)
                        .fill(
                            LinearGradient.vertical(
                                Color(Random.nextInt(0xFFFFFF)),
                                Color(Random.nextInt(0xFFFFFF))
                            )
                        )
                )
            }
            close()
        }
        return bytes.toByteArray()
    }

    private fun exampleNonAnimatedGifImage(): ByteArray {
        val frame1 = ImmutableImage.create(127, 127).fill(Color.CYAN)
        val writer = StreamingGifWriter(Duration.ofMillis(0), false, true)
        val bytes = ByteArrayOutputStream()
        with(writer.prepareStream(bytes, BufferedImage.TYPE_INT_ARGB)) {
            writeFrame(frame1)
            close()
        }
        return bytes.toByteArray()
    }

    private fun examplePngImage(): ByteArray {
        val writer = PngWriter.NoCompression
        val frame1 = ImmutableImage.create(127, 127).fill(Color.YELLOW)
        return frame1.bytes(writer)
    }
}
