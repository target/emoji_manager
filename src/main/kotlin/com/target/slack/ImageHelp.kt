package com.target.slack

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.Position
import com.sksamuel.scrimage.ScaleMethod
import com.sksamuel.scrimage.color.RGBColor
import com.sksamuel.scrimage.format.Format
import com.sksamuel.scrimage.format.FormatDetector
import com.sksamuel.scrimage.nio.AnimatedGif
import com.sksamuel.scrimage.nio.AnimatedGifReader
import com.sksamuel.scrimage.nio.ImageSource
import com.sksamuel.scrimage.nio.PngWriter
import com.sksamuel.scrimage.nio.StreamingGifWriter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.text.CharacterIterator
import java.text.StringCharacterIterator
import java.util.Optional
import kotlin.math.abs

// Measured in bytes
const val KILOBTYE = 1024

// measured in pixels
const val MAX_EMOJI_IMAGE_SIZE = 128
const val MIN_EMOJI_IMAGE_SIZE = 50
const val SM_EMOJI_IMAGE_SIZE = 16
const val PREVIEW_EMOJI_IMAGE_SIZE = 70
const val MAX_EMOJI_FILE_SIZE = 128
const val WARN_EMOJI_FILE_SIZE = 256
const val MAX_ASPECT_RATIO = 2.0
const val MAX_FRAME_COUNT = 50

fun ImmutableImage.aspectRatio(): Long {
    return if (width > height) {
        width.toLong() / height.toLong()
    } else {
        height.toLong() / width.toLong()
    }
}

// Yes, this is from StackOverflow:
// https://stackoverflow.com/questions/3758606/how-can-i-convert-byte-size-into-a-human-readable-format-in-java
fun humanReadableByteCountBin(bytes: Long): String {
    val absB = if (bytes == Long.MIN_VALUE) Long.MAX_VALUE else abs(bytes)
    if (absB < KILOBTYE) {
        return "$bytes B"
    }
    var value = absB
    val ci: CharacterIterator = StringCharacterIterator("KMGTPE")
    var i = 40
    while (i >= 0 && absB > 0xfffccccccccccccL shr i) {
        value = value shr 10
        ci.next()
        i -= 10
    }
    value *= java.lang.Long.signum(bytes).toLong()
    return String.format("%.1f %cB", value / 1024.0, ci.current())
}

object ImageHelp {

    private val lightBackground = RGBColor(255, 255, 255).toAWT()
    private val darkBackground = RGBColor(27, 29, 33).toAWT()

    fun isGif(bytes: ByteArray?): Boolean {
        if (bytes == null) return false
        return FormatDetector
            .detect(bytes)
            .map { f -> f == Format.GIF }
            .orElse(false)
    }

    private fun extractGif(bytes: ByteArray): Optional<AnimatedGif> {
        return if (!isGif(bytes)) {
            Optional.empty()
        } else {
            Optional.of(AnimatedGifReader.read(ImageSource.of(bytes)))
        }
    }

    private fun isAnimated(gif: AnimatedGif): Boolean {
        return gif.frameCount > 1
    }

    fun isAnimatedGif(bytes: ByteArray): Boolean {
        return extractGif(bytes)
            .map { g -> isAnimated(g) }
            .orElse(false)
    }

    fun checkSize(emojiFile: ByteArray): MutableList<String> {
        with(mutableListOf<String>()) {
            with(ImmutableImage.loader().fromBytes(emojiFile)) {
                if (aspectRatio() > MAX_ASPECT_RATIO) {
                    if (width < MIN_EMOJI_IMAGE_SIZE || height < MIN_EMOJI_IMAGE_SIZE) {
                        add("This image is small, and may be hard to read")
                    } else if (width > MAX_EMOJI_IMAGE_SIZE || height > MAX_EMOJI_IMAGE_SIZE) {
                        add("This image is large, and may be hard to read when scaled down.")
                    }
                    add("Slack emoji look best when mostly square, consider adjusting the aspect ratio.")
                }
                if (width > MAX_EMOJI_IMAGE_SIZE || height > MAX_EMOJI_IMAGE_SIZE) {
                    val emojiSm = run {
                        if (height > width) {
                            scaleToHeight(MAX_EMOJI_IMAGE_SIZE, ScaleMethod.Lanczos3)
                        } else {
                            scaleToWidth(MAX_EMOJI_IMAGE_SIZE, ScaleMethod.Lanczos3)
                        }
                    }
                    val resizedSize = emojiSm.bytes(PngWriter()).size
                    if (resizedSize > MAX_EMOJI_FILE_SIZE * KILOBTYE) {
                        val size = humanReadableByteCountBin(resizedSize.toLong())
                        add("The image file size is larger than $MAX_EMOJI_FILE_SIZE KB ($size) when scaled to ${MAX_EMOJI_IMAGE_SIZE}x$MAX_EMOJI_IMAGE_SIZE, Slack will reject it.")
                    }
                }
            }
            if (isAnimatedGif(emojiFile)) {
                if (emojiFile.size > MAX_EMOJI_FILE_SIZE * KILOBTYE) {
                    val size = humanReadableByteCountBin(emojiFile.size.toLong())
                    add("The animated GIF image file size is larger than $MAX_EMOJI_FILE_SIZE KB ($size), *Slack will reject it.*")
                }
                val frameCount = AnimatedGifReader.read(ImageSource.of(emojiFile)).frameCount
                if (frameCount > MAX_FRAME_COUNT) {
                    add("The animated GIF image has more than $MAX_FRAME_COUNT frames ($frameCount), Slack will reject it.")
                }
            }
            if (emojiFile.size > WARN_EMOJI_FILE_SIZE * KILOBTYE) {
                val size = humanReadableByteCountBin(emojiFile.size.toLong())
                add("The image file size is very large ($size), Slack may reject it.")
            }
            if (this.size > 0) {
                add("The ideal image size is ${MAX_EMOJI_IMAGE_SIZE}x$MAX_EMOJI_IMAGE_SIZE, and less than $MAX_EMOJI_FILE_SIZE KB.")
            }
            return this
        }
    }

    fun generatePreview(emojiFile: ByteArray): ByteArray {
        if (isAnimatedGif(emojiFile)) {
            return generateAnimatedPreview(emojiFile)
        }
        val preview = generatePreviewFrame(ImmutableImage.loader().fromBytes(emojiFile))
        return preview.bytes(PngWriter())
    }

    private fun generatePreviewFrame(srcImage: ImmutableImage): ImmutableImage {
        val emoji = srcImage.run {
            if (height > width) {
                scaleToHeight(MIN_EMOJI_IMAGE_SIZE, ScaleMethod.Lanczos3)
            } else {
                scaleToWidth(MIN_EMOJI_IMAGE_SIZE, ScaleMethod.Lanczos3)
            }
        }
        val emojiSm = srcImage.run {
            if (height > width) {
                scaleToHeight(SM_EMOJI_IMAGE_SIZE, ScaleMethod.Lanczos3)
            } else {
                scaleToWidth(SM_EMOJI_IMAGE_SIZE, ScaleMethod.Lanczos3)
            }
        }
        val emojiLight = ImmutableImage.create(PREVIEW_EMOJI_IMAGE_SIZE, PREVIEW_EMOJI_IMAGE_SIZE).fill(lightBackground).overlay(emoji, Position.Center)
        val emojiDark = ImmutableImage.create(PREVIEW_EMOJI_IMAGE_SIZE, PREVIEW_EMOJI_IMAGE_SIZE).fill(darkBackground).overlay(emoji, Position.Center)
        val emojiLightSm = ImmutableImage.create(PREVIEW_EMOJI_IMAGE_SIZE, PREVIEW_EMOJI_IMAGE_SIZE).fill(lightBackground).overlay(emojiSm, Position.Center)
        val emojiDarkSm = ImmutableImage.create(PREVIEW_EMOJI_IMAGE_SIZE, PREVIEW_EMOJI_IMAGE_SIZE).fill(darkBackground).overlay(emojiSm, Position.Center)
        val emojiImageSize = PREVIEW_EMOJI_IMAGE_SIZE * 2
        return ImmutableImage.create(emojiImageSize, emojiImageSize)
            .overlay(emojiLight, 0, 0)
            .overlay(emojiDark, PREVIEW_EMOJI_IMAGE_SIZE, 0)
            .overlay(emojiLightSm, 0, PREVIEW_EMOJI_IMAGE_SIZE)
            .overlay(emojiDarkSm, PREVIEW_EMOJI_IMAGE_SIZE, PREVIEW_EMOJI_IMAGE_SIZE)
    }

    private fun generateAnimatedPreview(emojiFile: ByteArray): ByteArray {
        val origGif = AnimatedGifReader.read(ImageSource.of(emojiFile))

        val writer = StreamingGifWriter(origGif.getDelay(0), origGif.loopCount == 0, true)
        val output = ByteArrayOutputStream()
        val preview = writer.prepareStream(output, BufferedImage.TYPE_INT_ARGB)

        preview.use { p ->
            for (frame in origGif.frames) {
                p.writeFrame(generatePreviewFrame(frame))
            }
        }

        return output.toByteArray()
    }
}
