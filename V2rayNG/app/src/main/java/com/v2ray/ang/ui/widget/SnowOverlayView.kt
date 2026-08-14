package com.v2ray.ang.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lightweight continuous snowfall.
 * Low FPS + sparse flakes keep the loop cheap while still reading as soft snow.
 */
class SnowOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private data class Flake(
        var x: Float,
        var y: Float,
        var size: Float,
        var speed: Float,
        var drift: Float,
        var phase: Float,
        var alpha: Int,
        val soft: Boolean
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    private var flakes: Array<Flake> = emptyArray()
    private var running = false
    private var paused = false
    private var lastFrameNs = 0L
    /** 24 FPS — continuous loop doesn't need 60; motion still reads soft. */
    private val frameIntervalNs = 41_666_667L

    val isAnimating: Boolean get() = running && !paused

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        setLayerType(LAYER_TYPE_NONE, null)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) rebuildFlakes(w, h)
    }

    fun playAnimation() {
        if (width <= 0 || height <= 0) {
            post { playAnimation() }
            return
        }
        if (flakes.isEmpty()) rebuildFlakes(width, height)
        running = true
        paused = false
        visibility = VISIBLE
        lastFrameNs = 0L
        Choreographer.getInstance().removeFrameCallback(this)
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pauseAnimation() {
        if (!running || paused) return
        paused = true
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun resumeAnimation() {
        if (!running || !paused) return
        paused = false
        lastFrameNs = 0L
        Choreographer.getInstance().removeFrameCallback(this)
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun cancelAnimation() {
        running = false
        paused = false
        Choreographer.getInstance().removeFrameCallback(this)
        lastFrameNs = 0L
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || paused || visibility != VISIBLE) return
        if (lastFrameNs == 0L || frameTimeNanos - lastFrameNs >= frameIntervalNs) {
            val dt = if (lastFrameNs == 0L) {
                frameIntervalNs / 1_000_000_000f
            } else {
                ((frameTimeNanos - lastFrameNs).coerceAtMost(80_000_000L)) / 1_000_000_000f
            }
            lastFrameNs = frameTimeNanos
            step(dt)
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun rebuildFlakes(w: Int, h: Int) {
        val dens = resources.displayMetrics.density
        val rnd = Random(42)
        // Sparse but larger flakes — same presence, fewer draws.
        val count = 24
        flakes = Array(count) {
            val size = dens * (1.6f + rnd.nextFloat() * 3.2f)
            Flake(
                x = rnd.nextFloat() * w,
                y = rnd.nextFloat() * h,
                size = size,
                speed = dens * (24f + rnd.nextFloat() * 48f),
                drift = dens * (6f + rnd.nextFloat() * 16f) * if (rnd.nextBoolean()) 1f else -1f,
                phase = rnd.nextFloat() * (Math.PI * 2).toFloat(),
                alpha = 100 + rnd.nextInt(120),
                soft = size > dens * 2.6f
            )
        }
    }

    private fun step(dt: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        for (f in flakes) {
            f.phase += dt * 1.05f
            f.y += f.speed * dt
            f.x += f.drift * dt * 0.35f + sin(f.phase) * f.drift * dt * 0.55f
            if (f.y > h + f.size * 2f) {
                f.y = -f.size * 2f
                f.x = Random.nextFloat() * w
            }
            if (f.x < -f.size * 2f) f.x = w + f.size
            if (f.x > w + f.size * 2f) f.x = -f.size
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running || flakes.isEmpty()) return
        for (f in flakes) {
            paint.alpha = f.alpha
            canvas.drawCircle(f.x, f.y, f.size, paint)
            // Halo only on larger flakes — halves fill calls.
            if (f.soft) {
                paint.alpha = f.alpha / 3
                canvas.drawCircle(f.x, f.y, f.size * 1.85f, paint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            if (running && !paused) pauseAnimation()
        }
    }
}
