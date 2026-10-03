package com.jawon.app.ui.pocketscene

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import com.google.android.filament.Engine
import com.google.android.filament.Stream
import com.google.android.filament.Texture

/**
 * The pocket player's screen: Compose, drawn by the **GPU** straight into a texture on the device.
 *
 * It replaces SceneView's `ViewNode` for this one surface, for two reasons, both found on the
 * owner's S25 Ultra:
 *
 * - **Cost.** `ViewNode` copies its view into the texture on a *software* canvas
 *   (`Surface.lockCanvas`) on the main thread — about 5–6ms a redraw for a 640×475 screen. The
 *   main menu's slideshow pays that twenty times a second and Cover Flow up to 120: the main
 *   thread went from 0.10 of a core idle to **0.71** while the covers moved. Here the host draws
 *   into `Surface.lockHardwareCanvas()`, so the main thread only records display lists and the
 *   GPU rasterises.
 * - **Trap 33, fixed at the root.** `ViewNode`'s own container only redraws when it is itself
 *   invalidated, and it is a final class. This host overrides `onDescendantInvalidated`, so
 *   *anything* that changes beneath it — a recomposition, an animation, a draw-only change —
 *   puts a new frame on the glass.
 *
 * Drawing hosted views into a surface's hardware canvas is not new: Flutter's texture-layer
 * platform views do exactly this in production. The texture plumbing is `ViewNode`'s own,
 * read out of its bytecode: a detached `SurfaceTexture`, a Filament stream from it, an external
 * RGB8 texture on the stream, bound through `MaterialLoader.createViewInstance`.
 *
 * **Teardown order matters** — Filament aborts on a texture destroyed while a material instance
 * still samples it, which is trap 1's family. The room declares this panel's disposal *before*
 * the `PlaneNode` that shows it, so Compose, forgetting in reverse, destroys the plane and its
 * material first and only then [release] here.
 */
class LcdPanel(
    context: Context,
    engine: Engine,
    private val widthPx: Int,
    private val heightPx: Int,
    /**
     * Texture pixels per layout pixel. The content is laid out at [widthPx] × [heightPx] whatever
     * this is, and drawn [supersample] times finer: the hardware canvas replays its display lists
     * scaled, so text and shapes are rasterised at the finer size rather than magnified after.
     * The pocket player's face shows its picture larger than the 640 it is laid out at, and needs
     * it; the showroom's exhibit does not.
     */
    private val supersample: Float = 1f
) {
    private val surfaceTexture = SurfaceTexture(0).apply {
        detachFromGLContext()
        setDefaultBufferSize((widthPx * supersample).toInt(), (heightPx * supersample).toInt())
    }
    private val surface = Surface(surfaceTexture)
    private var stream: Stream? = Stream.Builder().stream(surfaceTexture).build(engine)

    /** What the device's screen plane samples. */
    val texture: Texture = Texture.Builder()
        .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
        .format(Texture.InternalFormat.RGB8)
        .build(engine)
        .also { it.setExternalStream(engine, stream!!) }

    /** Where the screen's content is composed. Set its content once; state read inside redraws it. */
    val composeView = ComposeView(context)

    /** The view to add to SceneView's view window — it is what lends the content its owners. */
    val host: View = Host(context).apply {
        addView(composeView, FrameLayout.LayoutParams(widthPx, heightPx))
    }

    private var released = false

    fun release(engine: Engine) {
        if (released) return
        released = true
        engine.destroyTexture(texture)
        stream?.let { engine.destroyStream(it) }
        stream = null
        surface.release()
        surfaceTexture.release()
    }

    private inner class Host(context: Context) : FrameLayout(context) {

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // The screen is its own size whatever window it sits in.
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.EXACTLY)
            )
        }

        override fun dispatchDraw(canvas: Canvas) {
            if (released || !isAttachedToWindow || !surface.isValid) return
            val target = runCatching { surface.lockHardwareCanvas() }.getOrNull() ?: return
            try {
                target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                if (supersample != 1f) target.scale(supersample, supersample)
                super.dispatchDraw(target)
            } finally {
                surface.unlockCanvasAndPost(target)
            }
        }

        override fun onDescendantInvalidated(child: View, target: View) {
            super.onDescendantInvalidated(child, target)
            invalidate()
        }
    }
}
