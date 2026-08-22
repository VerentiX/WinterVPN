package com.v2ray.ang.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen frost wash; ice cracks stay above the connection-check card.
 */
class FrostOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class CrackPoint(val x: Float, val y: Float)

    private data class Crack(
        val points: List<CrackPoint>,
        val width: Float,
        val delay: Float,
        val branch: Boolean,
        /** End width as fraction of start — taper toward tip. */
        val taper: Float = 0.4f
    )

    private data class Junction(
        val x: Float,
        val y: Float,
        val delay: Float,
        val size: Float
    )

    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crackCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
        strokeMiter = 3.5f
        color = 0xFFF8FDFF.toInt()
    }
    private val crackMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
        strokeMiter = 3.5f
        color = 0xD8D4EEFF.toInt()
    }
    private val crackGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0x66B8ECFF.toInt()
    }
    private val crackDeepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
        strokeMiter = 3.5f
        color = 0x66101828.toInt()
    }
    private val crackRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
        strokeMiter = 3.5f
        color = 0xAAE8F6FF.toInt()
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val junctionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    private var cracks: List<Crack> = emptyList()
    private var sparkles: List<CrackPoint> = emptyList()
    private var junctions: List<Junction> = emptyList()

    private var originX = 0f
    private var originY = 0f
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var paused = false

    /** Cracks must stay above this Y (top of connection-check card). */
    private var crackBottomY = 0f
    private val crackZone = RectF()
    private var edgePadding = 0f
    private val tmpPath = Path()
    private val rimPath = Path()

    // Cached wash shaders — rebuilt only when origin/size change.
    private var bloomShader: RadialGradient? = null
    private var coreShader: RadialGradient? = null
    private var vignetteShader: RadialGradient? = null
    private var washCacheW = -1
    private var washCacheH = -1
    private var washCacheOx = Float.NaN
    private var washCacheOy = Float.NaN
    private var washCacheRadius = Float.NaN

    /** Frozen plate baked once — idle connected state costs one blit, not hundreds of paths. */
    private var snapshot: Bitmap? = null
    private val snapshotPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val snapshotDst = RectF()
    private val snapshotSrc = Rect()
    /** Half-res bake — ~4× less pixels, FILTER upscale still looks soft/icy. */
    private val bakeScale = 0.5f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        alpha = 0f
        // No permanent HW layer — it held a huge GraphicBuffer while snow composited on top.
        setLayerType(LAYER_TYPE_NONE, null)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    fun setFrozenImmediate(frozen: Boolean, source: View? = null, clipTo: View? = null) {
        animator?.cancel()
        paused = false
        if (source != null) updateBounds(source, clipTo)
        progress = if (frozen) 1f else 0f
        alpha = if (frozen) 1f else 0f
        visibility = if (frozen) VISIBLE else INVISIBLE
        if (frozen) {
            post { captureSnapshot() }
        } else {
            clearSnapshot()
        }
        invalidate()
    }

    fun freezeFrom(source: View, clipTo: View? = null) {
        val run = {
            updateBounds(source, clipTo)
            animateTo(1f, freeze = true)
        }
        if (width == 0 || (clipTo != null && !clipTo.isLaidOut)) post(run) else run()
    }

    fun melt() {
        clearSnapshot()
        animateTo(0f, freeze = false)
    }

    fun pause() {
        if (paused) return
        paused = true
        animator?.pause()
    }

    fun resume() {
        if (!paused) return
        paused = false
        animator?.resume()
    }

    private fun updateBounds(source: View, clipTo: View?) {
        val sourceLoc = IntArray(2)
        val selfLoc = IntArray(2)
        source.getLocationInWindow(sourceLoc)
        getLocationInWindow(selfLoc)
        originX = sourceLoc[0] - selfLoc[0] + source.width / 2f
        originY = sourceLoc[1] - selfLoc[1] + source.height / 2f

        edgePadding = dp(12f)

        // Stop cracks at the TOP of the connection-check card (do not cover it).
        crackBottomY = if (clipTo != null && clipTo.isLaidOut) {
            val clipLoc = IntArray(2)
            clipTo.getLocationInWindow(clipLoc)
            (clipLoc[1] - selfLoc[1]).toFloat() - dp(6f)
        } else {
            height * 0.42f
        }
        crackBottomY = crackBottomY.coerceIn(originY + dp(40f), height * 0.55f)

        // Keep the epicenter on-screen; cracks themselves may run past the edges.
        originX = originX.coerceIn(0f, width.toFloat())
        originY = originY.coerceIn(0f, crackBottomY)
        // Clip only below the card — left/right/top are open so fissures can exit the screen.
        crackZone.set(-dp(120f), -dp(120f), width + dp(120f), crackBottomY)

        if (width > 0) rebuildPattern()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /** How far past the visible screen cracks may continue (then get clipped by the view). */
    private fun edgeOverflow(): Float = dp(110f)

    private fun maxDistInDirection(angle: Float): Float {
        val dx = cos(angle)
        val dy = sin(angle)
        var limit = Float.POSITIVE_INFINITY
        val overflow = edgeOverflow()
        // Allow left / right / top to run past the screen; keep the card clear below.
        val left = -overflow
        val right = width + overflow
        val top = -overflow
        val bottom = crackBottomY - edgePadding
        if (dx > 1e-4f) limit = min(limit, (right - originX) / dx)
        if (dx < -1e-4f) limit = min(limit, (left - originX) / dx)
        if (dy > 1e-4f) limit = min(limit, (bottom - originY) / dy)
        if (dy < -1e-4f) limit = min(limit, (top - originY) / dy)
        return limit.coerceAtLeast(0f)
    }

    private fun remainingDistFrom(x: Float, y: Float, angle: Float): Float {
        val dx = cos(angle)
        val dy = sin(angle)
        var limit = Float.POSITIVE_INFINITY
        val overflow = edgeOverflow()
        val left = -overflow
        val right = width + overflow
        val top = -overflow
        val bottom = crackBottomY - edgePadding
        if (dx > 1e-4f) limit = min(limit, (right - x) / dx)
        if (dx < -1e-4f) limit = min(limit, (left - x) / dx)
        if (dy > 1e-4f) limit = min(limit, (bottom - y) / dy)
        if (dy < -1e-4f) limit = min(limit, (top - y) / dy)
        return limit.coerceAtLeast(0f)
    }

    private fun animateTo(target: Float, freeze: Boolean) {
        animator?.cancel()
        paused = false
        if (freeze) clearSnapshot()
        if (width == 0 || height == 0) {
            post { animateTo(target, freeze) }
            return
        }
        if (cracks.isEmpty()) rebuildPattern()

        visibility = VISIBLE
        val start = progress
        animator = ValueAnimator.ofFloat(start, target).apply {
            // Slightly snappier freeze — fewer frames of heavy crack draw.
            duration = if (freeze) 1_800L else 850L
            interpolator = if (freeze) {
                PathInterpolator(0.12f, 0.7f, 0.2f, 1f)
            } else {
                AccelerateDecelerateInterpolator()
            }
            var lastDrawNs = 0L
            addUpdateListener {
                if (paused) return@addUpdateListener
                progress = it.animatedValue as Float
                alpha = if (freeze) min(1f, 0.2f + progress * 0.9f) else progress
                // ~30 FPS is enough for growing cracks — still reads as smooth ice.
                val now = System.nanoTime()
                if (lastDrawNs == 0L || now - lastDrawNs >= 33_333_333L ||
                    progress >= 0.995f || progress <= 0.01f
                ) {
                    lastDrawNs = now
                    invalidate()
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (target <= 0.01f) {
                        visibility = INVISIBLE
                        progress = 0f
                        alpha = 0f
                        clearSnapshot()
                    } else if (freeze && target >= 0.99f) {
                        // Bake the finished ice plate so idle connected state stays free.
                        captureSnapshot()
                    }
                }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (originX == 0f && originY == 0f) {
            originX = w / 2f
            originY = h * 0.2f
        }
        if (crackBottomY <= 0f) crackBottomY = h * 0.42f
        edgePadding = dp(12f)
        crackZone.set(-dp(120f), -dp(120f), w + dp(120f), crackBottomY)
        bloomShader = null
        coreShader = null
        vignetteShader = null
        clearSnapshot()
        rebuildPattern()
    }

    private fun rebuildPattern() {
        if (width <= 0 || crackBottomY <= edgePadding * 2) return
        val rnd = Random(System.nanoTime().toInt() xor 0x5F3759DF)
        val built = buildCracks(rnd)
        cracks = built.first
        junctions = built.second
        sparkles = buildSparkles(rnd)
    }

    private fun buildSparkles(rnd: Random): List<CrackPoint> {
        val list = ArrayList<CrackPoint>(28)
        repeat(28) {
            val a = rnd.nextFloat() * Math.PI.toFloat() * 2f
            val maxLen = maxDistInDirection(a)
            if (maxLen < dp(20f)) return@repeat
            val d = maxLen * (0.12f + rnd.nextFloat() * 0.78f)
            list += CrackPoint(originX + cos(a) * d, originY + sin(a) * d)
        }
        return list
    }

    private fun buildCracks(rnd: Random): Pair<List<Crack>, List<Junction>> {
        val result = ArrayList<Crack>(96)
        val nodes = ArrayList<Junction>(40)

        // Impact burst — short, chaotic shards from the epicenter.
        val burst = 10
        for (i in 0 until burst) {
            val a = i * (Math.PI.toFloat() * 2f / burst) +
                (rnd.nextFloat() - 0.5f) * 0.55f
            val len = dp(18f) + rnd.nextFloat() * dp(22f)
            val pts = buildIceFissure(originX, originY, a, len, 7 + rnd.nextInt(4), rnd)
            if (pts.size >= 2) {
                result += Crack(
                    points = pts,
                    width = dp(1.6f) + rnd.nextFloat() * dp(1.1f),
                    delay = i * 0.006f,
                    branch = false,
                    taper = 0.15f
                )
            }
        }
        nodes += Junction(originX, originY, 0f, dp(4.2f))

        // Long primary fractures — uneven angles, lightning-like wander.
        val mainCount = 8
        var angleCursor = rnd.nextFloat() * 0.4f
        for (i in 0 until mainCount) {
            val span = (Math.PI.toFloat() * 2f / mainCount) * (0.7f + rnd.nextFloat() * 0.7f)
            angleCursor += span
            val baseAngle = angleCursor + (rnd.nextFloat() - 0.5f) * 0.35f
            val maxLen = maxDistInDirection(baseAngle)
            if (maxLen < dp(48f)) continue

            val targetLen = maxLen * (0.78f + rnd.nextFloat() * 0.2f)
            val segments = 16 + rnd.nextInt(10)
            val points = buildIceFissure(
                originX, originY, baseAngle, targetLen, segments, rnd
            )
            if (points.size < 4) continue

            val mainWidth = dp(1.4f) + rnd.nextFloat() * dp(1.8f)
            val mainDelay = 0.015f + i * 0.016f
            result += Crack(
                points = points,
                width = mainWidth,
                delay = mainDelay,
                branch = false,
                taper = 0.18f + rnd.nextFloat() * 0.28f
            )

            // Organic forks — not fixed 60° lattice spokes.
            val forks = 1 + rnd.nextInt(3)
            repeat(forks) { fork ->
                val fromIndex = ((0.22f + fork * 0.2f + rnd.nextFloat() * 0.12f) * (points.size - 1))
                    .toInt()
                    .coerceIn(2, points.size - 2)
                val from = points[fromIndex]
                val prev = points[fromIndex - 1]
                val along = kotlin.math.atan2(from.y - prev.y, from.x - prev.x)
                val side = if ((i + fork) % 2 == 0) 1f else -1f
                val dir = along + side * (0.55f + rnd.nextFloat() * 0.95f) +
                    (rnd.nextFloat() - 0.5f) * 0.25f
                val branchMax = remainingDistFrom(from.x, from.y, dir)
                if (branchMax < dp(20f)) return@repeat
                val branchLen = branchMax * (0.35f + rnd.nextFloat() * 0.45f)
                val branchPts = buildIceFissure(
                    from.x, from.y, dir, branchLen, 10 + rnd.nextInt(6), rnd
                )
                if (branchPts.size < 3) return@repeat

                val branchDelay = mainDelay + 0.04f + fork * 0.035f + rnd.nextFloat() * 0.04f
                val branchWidth = mainWidth * (0.38f + rnd.nextFloat() * 0.28f)
                result += Crack(
                    points = listOf(from) + branchPts.drop(1),
                    width = branchWidth,
                    delay = branchDelay,
                    branch = true,
                    taper = 0.2f
                )
                nodes += Junction(from.x, from.y, branchDelay, branchWidth * 0.65f)

                // Tiny hairline splinter.
                if (branchPts.size > 5 && rnd.nextFloat() > 0.4f) {
                    val twigFrom = branchPts[(branchPts.size * (0.4f + rnd.nextFloat() * 0.35f)).toInt()
                        .coerceAtMost(branchPts.lastIndex)]
                    val twigDir = dir + side * -(0.7f + rnd.nextFloat() * 0.7f)
                    val twigMax = remainingDistFrom(twigFrom.x, twigFrom.y, twigDir)
                    if (twigMax > dp(14f)) {
                        val twigPts = buildIceFissure(
                            twigFrom.x, twigFrom.y, twigDir,
                            twigMax * (0.22f + rnd.nextFloat() * 0.3f),
                            6 + rnd.nextInt(4), rnd
                        )
                        if (twigPts.size >= 2) {
                            result += Crack(
                                points = listOf(twigFrom) + twigPts.drop(1),
                                width = branchWidth * 0.45f,
                                delay = branchDelay + 0.05f,
                                branch = true,
                                taper = 0.25f
                            )
                        }
                    }
                }
            }
        }

        // Fine hairline web in the ice plate.
        for (i in 0 until 14) {
            val angle = rnd.nextFloat() * Math.PI.toFloat() * 2f
            val maxLen = maxDistInDirection(angle)
            if (maxLen < dp(32f)) continue
            val startD = maxLen * (0.15f + rnd.nextFloat() * 0.35f)
            val len = maxLen * (0.22f + rnd.nextFloat() * 0.32f)
            val start = CrackPoint(originX + cos(angle) * startD, originY + sin(angle) * startD)
            val pts = buildIceFissure(start.x, start.y, angle, len, 8 + rnd.nextInt(5), rnd)
            if (pts.size >= 2) {
                result += Crack(
                    points = pts,
                    width = dp(0.55f) + rnd.nextFloat() * dp(0.55f),
                    delay = 0.2f + i * 0.018f,
                    branch = true,
                    taper = 0.35f
                )
            }
        }
        return result to nodes
    }

    /**
     * Lightning / frost fissure: short uneven steps, wander + sharp kinks,
     * lateral jitter — avoids ruler-straight radial spokes.
     */
    private fun buildIceFissure(
        startX: Float,
        startY: Float,
        startAngle: Float,
        length: Float,
        segments: Int,
        rnd: Random
    ): List<CrackPoint> {
        val points = ArrayList<CrackPoint>(segments + 1)
        points += CrackPoint(startX, startY)
        var x = startX
        var y = startY
        var angle = startAngle + (rnd.nextFloat() - 0.5f) * 0.2f
        var wander = 0f
        var kinkCooldown = 1 + rnd.nextInt(2)
        val baseStep = length / segments.coerceAtLeast(1)

        repeat(segments) { s ->
            wander += (rnd.nextFloat() - 0.5f) * 0.55f
            wander *= 0.72f

            kinkCooldown--
            if (kinkCooldown <= 0 && rnd.nextFloat() > 0.55f) {
                val kink = (0.4f + rnd.nextFloat() * 0.95f) * if (rnd.nextBoolean()) 1f else -1f
                angle += kink
                kinkCooldown = 2 + rnd.nextInt(4)
            } else {
                // Soft bias back toward the intended radial so cracks still fan out.
                angle += (startAngle - angle) * 0.07f + wander * 0.35f
            }

            val move = baseStep * (0.55f + rnd.nextFloat() * 0.9f)
            val allowed = remainingDistFrom(x, y, angle)
            val advance = min(move, allowed * 0.96f)
            if (advance < dp(0.9f)) return@repeat

            val px = -sin(angle)
            val py = cos(angle)
            val lateral = (rnd.nextFloat() - 0.5f) * dp(3.2f) * (0.4f + s / segments.toFloat())

            x += cos(angle) * advance + px * lateral
            y += sin(angle) * advance + py * lateral

            if (y > crackBottomY - edgePadding) {
                y = crackBottomY - edgePadding
                points += CrackPoint(x, y)
                return@repeat
            }
            points += CrackPoint(x, y)
        }
        return points
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0.001f || width == 0 || height == 0) return

        val baked = snapshot
        if (baked != null && !baked.isRecycled && progress >= 0.995f) {
            snapshotSrc.set(0, 0, baked.width, baked.height)
            snapshotDst.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(baked, snapshotSrc, snapshotDst, snapshotPaint)
            return
        }

        drawFrostScene(canvas, progress)
    }

    private fun captureSnapshot() {
        if (width <= 0 || height <= 0) return
        clearSnapshot()
        val bw = (width * bakeScale).toInt().coerceAtLeast(1)
        val bh = (height * bakeScale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(bakeScale, bakeScale)
        val old = progress
        progress = 1f
        drawFrostScene(c, 1f)
        progress = old
        snapshot = bmp
        // Static plate — leave the compositing stack as a cheap scaled blit.
        invalidate()
    }

    private fun clearSnapshot() {
        snapshot?.recycle()
        snapshot = null
    }

    private fun drawFrostScene(canvas: Canvas, t: Float) {
        val spread = easeOutQuart(t)

        canvas.save()
        canvas.clipRect(crackZone)

        for (crack in cracks) {
            drawCrack(canvas, crack, spread)
        }

        for (node in junctions) {
            val local = ((t - node.delay) / (1f - node.delay).coerceAtLeast(0.25f))
                .coerceIn(0f, 1f)
            if (local < 0.12f) continue
            val pulse = 0.55f + 0.45f * sin(t * 6.5f + node.x * 0.04f)
            junctionPaint.color = 0xFFF5FBFF.toInt()
            junctionPaint.alpha = (local * spread * pulse * 175).toInt().coerceIn(0, 200)
            canvas.drawCircle(node.x, node.y, node.size * (0.55f + pulse * 0.4f), junctionPaint)
        }

        if (t < 0.995f) {
            val fullRadius = hypot(width.toDouble(), height.toDouble()).toFloat() * 1.08f
            val rLimit = fullRadius * spread * 0.9f
            val rLimitSq = rLimit * rLimit
            for (spark in sparkles) {
                if (spark.y > crackBottomY) continue
                val dx = spark.x - originX
                val dy = spark.y - originY
                if (dx * dx + dy * dy > rLimitSq) continue
                val twinkle = 0.35f + 0.65f * sin(t * 9f + spark.x * 0.035f + spark.y * 0.02f)
                sparkPaint.alpha = (twinkle * spread * 115).toInt().coerceIn(0, 130)
                canvas.drawCircle(spark.x, spark.y, 1.1f + twinkle * 1.0f, sparkPaint)
            }
        }

        canvas.restore()
    }

    private fun ensureWashShaders(radius: Float) {
        val w = width
        val h = height
        val needRebuild = bloomShader == null ||
            washCacheW != w || washCacheH != h ||
            abs(washCacheOx - originX) > 0.5f ||
            abs(washCacheOy - originY) > 0.5f ||
            abs(washCacheRadius - radius) > max(4f, radius * 0.02f)
        if (!needRebuild) return

        washCacheW = w
        washCacheH = h
        washCacheOx = originX
        washCacheOy = originY
        washCacheRadius = radius

        bloomShader = RadialGradient(
            originX, originY, max(1f, radius),
            intArrayOf(
                0x5578A0C0.toInt(),
                0x446088A8.toInt(),
                0x33486078.toInt(),
                0x22283848.toInt(),
                0x00000000
            ),
            floatArrayOf(0f, 0.28f, 0.55f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )
        coreShader = RadialGradient(
            originX, originY, max(1f, radius * 0.38f),
            intArrayOf(0x4488B0C8.toInt(), 0x28385870.toInt(), 0x00000000),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        vignetteShader = RadialGradient(
            w / 2f, h / 2f,
            hypot(w / 2.0, h / 2.0).toFloat(),
            intArrayOf(0x00000000, 0x30101828.toInt(), 0x60100818.toInt()),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun drawCrack(canvas: Canvas, crack: Crack, spread: Float) {
        if (crack.points.size < 2) return

        val window = if (crack.branch) 0.42f else 0.32f
        val localProgress = ((progress - crack.delay) / (1f - crack.delay).coerceAtLeast(window))
            .coerceIn(0f, 1f)
        if (localProgress <= 0.001f) return

        val grow = fractureEase(localProgress)
        val totalSegments = crack.points.size - 1
        val visibleSegments = (totalSegments * grow).coerceAtLeast(0.01f)
        val whole = visibleSegments.toInt().coerceAtMost(totalSegments)
        val frac = visibleSegments - whole

        val endX: Float
        val endY: Float
        if (whole < totalSegments && frac > 0f) {
            val a = crack.points[whole]
            val b = crack.points[whole + 1]
            endX = a.x + (b.x - a.x) * frac
            endY = a.y + (b.y - a.y) * frac
        } else {
            val last = crack.points[whole.coerceAtMost(crack.points.lastIndex)]
            endX = last.x
            endY = last.y
        }

        // Sharp polyline — miter joins keep the lightning corners.
        tmpPath.rewind()
        tmpPath.moveTo(crack.points[0].x, crack.points[0].y)
        for (i in 1..whole) {
            val p = crack.points[i]
            tmpPath.lineTo(p.x, p.y)
        }
        if (whole < totalSegments && frac > 0.01f) {
            tmpPath.lineTo(endX, endY)
        } else if (whole == 0) {
            tmpPath.lineTo(endX, endY)
        }

        val alphaScale = if (crack.branch) 0.78f else 1f
        val tipBoost = 0.7f + 0.3f * grow
        val deepW = crack.width * (0.95f + (1f - crack.taper) * 0.2f)

        // Soft bloom under the fissure.
        crackGlowPaint.color = 0x44A8D8F0.toInt()
        crackGlowPaint.strokeWidth = deepW * (if (crack.branch) 2.4f else 3.1f)
        crackGlowPaint.alpha = (grow * spread * tipBoost * 55 * alphaScale).toInt().coerceIn(0, 80)
        canvas.drawPath(tmpPath, crackGlowPaint)

        // Dark crack body.
        crackDeepPaint.color = 0xFF071420.toInt()
        crackDeepPaint.strokeWidth = deepW
        crackDeepPaint.alpha = (grow * spread * 210 * alphaScale).toInt().coerceIn(0, 230)
        canvas.drawPath(tmpPath, crackDeepPaint)

        // Bright frost slit.
        crackMidPaint.color = 0xFFF2FAFF.toInt()
        crackMidPaint.strokeWidth = deepW * 0.38f
        crackMidPaint.alpha = (grow * spread * 250 * alphaScale).toInt().coerceIn(0, 255)
        canvas.drawPath(tmpPath, crackMidPaint)

        // Refraction rim — offset polyline, not a second parallel "ruler".
        buildOffsetRim(crack, whole, frac, endX, endY, deepW * 0.32f)
        if (!rimPath.isEmpty) {
            crackRimPaint.color = 0xCCEAF6FF.toInt()
            crackRimPaint.strokeWidth = deepW * 0.16f
            crackRimPaint.alpha = (grow * spread * 150 * alphaScale).toInt().coerceIn(0, 180)
            canvas.drawPath(rimPath, crackRimPaint)
        }

        // Hairline core flash near the tip.
        crackCorePaint.color = 0xFFFFFFFF.toInt()
        crackCorePaint.strokeWidth = deepW * 0.14f
        crackCorePaint.alpha = (grow * spread * 180 * alphaScale).toInt().coerceIn(0, 200)
        canvas.drawPath(tmpPath, crackCorePaint)

        if (grow in 0.04f..0.96f) {
            val tipA = ((1f - abs(grow - 0.55f) * 1.4f).coerceIn(0.2f, 1f) * spread * 240).toInt()
                .coerceIn(0, 245)
            sparkPaint.color = 0xFFFFFFFF.toInt()
            sparkPaint.alpha = tipA
            canvas.drawCircle(endX, endY, deepW * 0.4f + dp(0.4f), sparkPaint)
        }
    }

    private fun buildOffsetRim(
        crack: Crack,
        whole: Int,
        frac: Float,
        endX: Float,
        endY: Float,
        offset: Float
    ) {
        rimPath.rewind()
        val n = if (whole < crack.points.size - 1 && frac > 0.01f) whole + 1 else whole
        if (n < 1) return

        var started = false
        for (i in 0..n) {
            val p = if (i == n && whole < crack.points.size - 1 && frac > 0.01f) {
                CrackPoint(endX, endY)
            } else {
                crack.points[i.coerceAtMost(crack.points.lastIndex)]
            }
            val q = if (i == 0) {
                crack.points[min(1, crack.points.lastIndex)]
            } else if (i == n && whole < crack.points.size - 1 && frac > 0.01f) {
                crack.points[whole]
            } else {
                crack.points[(i - 1).coerceAtLeast(0)]
            }
            val dx = p.x - q.x
            val dy = p.y - q.y
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(0.001f)
            val ox = -dy / len * offset
            val oy = dx / len * offset
            if (!started) {
                rimPath.moveTo(p.x + ox, p.y + oy)
                started = true
            } else {
                rimPath.lineTo(p.x + ox, p.y + oy)
            }
        }
    }

    /** Snappy start (ice cracking), soft settle at the tip. */
    private fun fractureEase(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x).let { it * it * it * it * it }
    }

    private fun easeOutQuart(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u * u
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        paused = false
        clearSnapshot()
        super.onDetachedFromWindow()
    }
}
