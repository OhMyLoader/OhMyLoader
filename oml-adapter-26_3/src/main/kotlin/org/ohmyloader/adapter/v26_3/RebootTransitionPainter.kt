package org.ohmyloader.adapter.v26_3

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import org.ohmyloader.api26_3.RebootRestore

/**
 * The reboot transition's pixels: vanilla's own loading screen, copied number for number.
 *
 * <p>This lives in the version module because the GUI drawing surface is the one place 26.x is not
 * source-stable: `GuiGraphicsExtractor.blit`'s parameter list, `TextureManager.getTexture`'s return
 * type and the pipeline holder the mark is drawn with all differ between the shipped 26.3 jar and
 * the next snapshot, while `oml-api-26_3` is checked against both. The state machine that decides
 * *whether* to paint stays there; only the calls that touch those three shapes are here.
 *
 * <p>The look is a transcription of `LoadingOverlay.extractRenderState`, including the awkward
 * parts: the logo is two mirrored halves with a fractional overlap, the bar is a hollow rectangle
 * whose inner fill starts two pixels in, and the whole thing is sized off `min(width * 0.75,
 * height)`. A hand-drawn approximation looks wrong at every window size, which is why there is no
 * approximation here — only vanilla's arithmetic.
 */
object RebootTransitionPainter {

    /** `textures/gui/title/mojangstudios.png`: two 120x120 marks stacked, one per half. */
    private const val LOGO_TEXTURE = "textures/gui/title/mojangstudios.png"

    /** `ARGB.color(255, 239, 50, 61)` in `LoadingOverlay.LOGO_BACKGROUND_COLOR`. */
    private const val LOGO_RED = 0xEF323D

    /** The logo's height as a fraction of `min(width * 0.75, height)`: vanilla's `* 0.25`. */
    private const val LOGO_HEIGHT_OF_MIN = 0.25

    /** Vanilla draws the mark four times as wide as it is tall. */
    private const val LOGO_ASPECT = 4.0

    /** The bar's centre line, `0.8325` of the GUI height in vanilla. */
    private const val BAR_Y = 0.8325

    /** Half the bar's height: vanilla's `barY - 5` / `barY + 5`. */
    private const val BAR_HALF_HEIGHT = 5

    /** The blit's own coordinate space for the logo, as vanilla passes it: 120x120, halves 120x60. */
    private const val LOGO_TEX = 120
    private const val LOGO_TEX_HALF = 60

    /** Vanilla nudges each half by this to hide the seam where they meet. */
    private const val LOGO_OVERLAP = 0.0625f

    /** Frames traced from the first painted frame; a stutter on this path must not cost a log line. */
    private const val TRACE_FRAMES = 60

    private var paintFrame = 0

    @JvmStatic
    fun paint(extractor: GuiGraphicsExtractor) {
        val frame = RebootRestore.transitionFrame() ?: return
        val width = extractor.guiWidth()
        val height = extractor.guiHeight()
        if (width <= 0 || height <= 0) return

        paintFrame++
        ptrace("begin frame=$paintFrame ${width}x$height fade=${frame.alpha} progress=${frame.progress}")
        // Vanilla opens the loading screen with `nextStratum()` so the red field sits above whatever
        // the frame already drew; here that is the retiring session's "Saving world" and the starting
        // session's level-loading screen, which must both end up underneath.
        extractor.nextStratum()
        ptrace("nextStratum ok")
        extractor.fill(0, 0, width, height, replaceAlpha(0xFF000000.toInt() or LOGO_RED, Math.round(frame.alpha * 255f)))
        ptrace("backdrop ok")

        val contentX = (width * 0.5).toInt()
        val logoY = (height * 0.5).toInt()
        val logoHeight = minOf(width * 0.75, height.toDouble()) * LOGO_HEIGHT_OF_MIN
        val logoHeightHalf = (logoHeight * 0.5).toInt()
        val logoWidthHalf = ((logoHeight * LOGO_ASPECT) * 0.5).toInt()
        val logoPixels = logoHeight.toInt()
        val color = ARGB.white(frame.alpha)
        // Two halves, not one blit: the second samples the lower 120x60 of the texture and starts
        // where the first ends. This is how vanilla keeps the wordmark crisp at any GUI scale.
        if (logoReady()) {
            ptrace("logo ready, blit left half")
            extractor.blit(
                RenderPipelines.MOJANG_LOGO, logoTexture,
                contentX - logoWidthHalf, logoY - logoHeightHalf,
                -LOGO_OVERLAP, 0f,
                logoWidthHalf, logoPixels,
                LOGO_TEX, LOGO_TEX_HALF, LOGO_TEX, LOGO_TEX,
                color,
            )
            ptrace("blit left ok, blit right half")
            extractor.blit(
                RenderPipelines.MOJANG_LOGO, logoTexture,
                contentX, logoY - logoHeightHalf,
                LOGO_OVERLAP, LOGO_TEX_HALF.toFloat(),
                logoWidthHalf, logoPixels,
                LOGO_TEX, LOGO_TEX_HALF, LOGO_TEX, LOGO_TEX,
                color,
            )
            ptrace("blit right ok")
        } else {
            ptrace("logo not ready, skipping mark")
        }

        val barY = (height * BAR_Y).toInt()
        progressBar(
            extractor,
            width / 2 - logoWidthHalf, barY - BAR_HALF_HEIGHT,
            width / 2 + logoWidthHalf, barY + BAR_HALF_HEIGHT,
            frame.alpha, frame.progress,
        )
        ptrace("bar ok")
    }

    private fun ptrace(message: String) {
        if (paintFrame > TRACE_FRAMES) return
        println("[paint] $message")
        System.out.flush()
    }

    /**
     * `LoadingOverlay.extractProgressBar`: a one-pixel border with the fill two pixels inside it,
     * measured from the left edge of the track and never reaching the right one.
     */
    private fun progressBar(
        extractor: GuiGraphicsExtractor,
        x0: Int, y0: Int, x1: Int, y1: Int,
        fade: Float, progress: Float,
    ) {
        val filled = Math.ceil(((x1 - x0 - 2) * progress).toDouble()).toInt()
        val alpha = Math.round(fade * 255f)
        val white = ARGB.color(alpha, 255, 255, 255)
        extractor.fill(x0 + 2, y0 + 2, x0 + filled, y1 - 2, white)
        extractor.fill(x0 + 1, y0, x1 - 1, y0 + 1, white)
        extractor.fill(x0 + 1, y1, x1 - 1, y1 - 1, white)
        extractor.fill(x0, y0, x0 + 1, y1, white)
        extractor.fill(x1, y0, x1 - 1, y1, white)
    }

    /** `LoadingOverlay.replaceAlpha`: keep the RGB, swap the alpha channel. */
    private fun replaceAlpha(color: Int, alpha: Int): Int = (color and 0xFFFFFF) or (alpha shl 24)

    private val logoTexture: Identifier = Identifier.withDefaultNamespace(LOGO_TEXTURE)

    /**
     * Whether the logo texture has a GPU view yet.
     *
     * <p>Vanilla can blit unconditionally because it only starts drawing once the initial resource
     * reload has produced the texture. This painter can fire on the very first extracted frame of a
     * session — before that reload — and the texture's view throws rather than returning null. A
     * missing mark for one frame is invisible; an exception in the GUI pass is not.
     */
    private fun logoReady(): Boolean = runCatching {
        Minecraft.getInstance().getTextureManager().getTexture(logoTexture).textureView != null
    }.getOrDefault(false)
}
