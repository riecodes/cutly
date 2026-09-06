package com.eirmon.cutly.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import com.eirmon.cutly.transcribe.Segment

/**
 * Burns the transcript into the video, one line at a time.
 *
 * [CanvasOverlay] rather than `TextOverlay`, which is otherwise the obvious fit: `TextOverlay`
 * measures the text at its natural width with nowhere to say how wide the frame is, so any caption
 * longer than a few words runs off both edges. Drawing it here means a [StaticLayout] bounded to
 * the frame, which wraps.
 *
 * The overlay goes on the *composition*, not on each `EditedMediaItem`: a cut export is many items
 * over one source, so per-item presentation times restart at every join and every caption after
 * the first cut would be placed against the wrong clock. Composition effects see the output
 * timeline, which is the timeline [Segment.remap] produces.
 *
 * @param segments captions already remapped onto the output timeline, sorted and non-overlapping.
 */
@OptIn(UnstableApi::class)
class CaptionOverlay(private val segments: List<Segment>) : CanvasOverlay(false) {

    private var frameWidth = 0
    private var frameHeight = 0

    override fun configure(videoSize: Size) {
        frameWidth = videoSize.width
        frameHeight = videoSize.height
        // One overlay pixel per frame pixel, so the text is laid out at the size it is encoded at
        // and nothing is resampled.
        setCanvasSize(frameWidth, frameHeight)
        super.configure(videoSize)
    }

    /**
     * Redraws the whole canvas every frame rather than caching per caption.
     *
     * This is an export, not playback: the H.264 encode dwarfs a text draw, and a cache would have
     * to assume the bitmap survives between calls, which is not something the class promises.
     */
    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val text = Segment.at(segments, presentationTimeUs / 1000)?.text ?: return
        if (frameWidth == 0) return

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            // Tied to frame height so a 720p and a 1080p export read the same on the same phone.
            textSize = frameHeight * TEXT_SIZE_FRACTION
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val sideMargin = frameWidth * SIDE_MARGIN_FRACTION
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, (frameWidth - 2 * sideMargin).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()

        // Sat above the bottom edge, clear of a phone's gesture bar and of the caption strip
        // TikTok and Reels lay over the bottom of a video.
        val top = frameHeight * (1 - BOTTOM_FRACTION) - layout.height
        canvas.save()
        canvas.translate(sideMargin, top)

        // A band per line, not one box around the block: a short last line under a long first one
        // otherwise sits inside a wide slab of black with nothing in it.
        val backing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKING }
        val padX = textPaint.textSize * 0.35f
        val radius = textPaint.textSize * 0.18f
        for (line in 0 until layout.lineCount) {
            canvas.drawRoundRect(
                layout.getLineLeft(line) - padX,
                layout.getLineTop(line).toFloat(),
                layout.getLineRight(line) + padX,
                layout.getLineBottom(line).toFloat(),
                radius,
                radius,
                backing
            )
        }

        layout.draw(canvas)
        canvas.restore()
    }

    private companion object {
        /**
         * Semi-opaque black behind the words.
         *
         * Captions land on footage nobody has seen, so contrast cannot be assumed. White on a band
         * reads over both a bright sky and a dark room; white alone reads reliably over neither.
         */
        const val BACKING = 0xB3000000.toInt()

        /** About 4.5% of frame height, which is roughly what CapCut and Reels captions run at. */
        const val TEXT_SIZE_FRACTION = 0.045f
        const val SIDE_MARGIN_FRACTION = 0.06f

        /** How far the bottom of the text sits above the bottom of the frame. */
        const val BOTTOM_FRACTION = 0.14f
    }
}
