package com.v2ray.ang.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFF8FDFF.toInt()
    }
    private val crackMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0x66101828.toInt()
    }
    private val crackRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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

    /** Cracks must stay above this Y (top of connection-check card). */
    private var crackBottomY = 0f
    private val crackZone = RectF()
    private var edgePadding = 0f
    private val tmpPath = Path()
    private val starPath = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        alpha = 0f
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    fun setFrozenImmediate(frozen: Boolean, source: View? = null, clipTo: View? = null) {
        animator?.cancel()
        if (source != null) updateBounds(source, clipTo)
        progress = if (frozen) 1f else 0f
        alpha = if (frozen) 1f else 0f
        visibility = if (frozen) VISIBLE else INVISIBLE
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
        animateTo(0f, freeze = false)
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

        originX = originX.coerceIn(edgePadding, width - edgePadding)
        originY = originY.coerceIn(edgePadding, crackBottomY - edgePadding)
        crackZone.set(0f, 0f, width.toFloat(), crackBottomY)

        if (width > 0) rebuildPattern()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun maxDistInDirection(angle: Float): Float {
        val dx = cos(angle)
        val dy = sin(angle)
        var limit = Float.POSITIVE_INFINITY
        val left = edgePadding
        val right = width - edgePadding
        val top = edgePadding
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
        val left = edgePadding
        val right = width - edgePadding
        val top = edgePadding
        val bottom = crackBottomY - edgePadding
        if (dx > 1e-4f) limit = min(limit, (right - x) / dx)
        if (dx < -1e-4f) limit = min(limit, (left - x) / dx)
        if (dy > 1e-4f) limit = min(limit, (bottom - y) / dy)
        if (dy < -1e-4f) limit = min(limit, (top - y) / dy)
        return limit.coerceAtLeast(0f)
    }

    private fun animateTo(target: Float, freeze: Boolean) {
        animator?.cancel()
        if (width == 0 || height == 0) {
            post { animateTo(target, freeze) }
            return
        }
        if (cracks.isEmpty()) rebuildPattern()

        visibility = VISIBLE
        val start = progress
        animator = ValueAnimator.ofFloat(start, target).apply {
            duration = if (freeze) 1_850L else 1_000L
            interpolator = if (freeze) DecelerateInterpolator(1.2f) else AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                alpha = if (freeze) min(1f, 0.2f + progress * 0.9f) else progress
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (target <= 0.01f) {
                        visibility = INVISIBLE
                        progress = 0f
                        alpha = 0f
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
        crackZone.set(0f, 0f, w.toFloat(), crackBottomY)
        rebuildPattern()
    }

    private fun rebuildPattern() {
        if (width <= 0 || crackBottomY <= edgePadding * 2) return
        val rnd = Random(131)
        val built = buildCracks(rnd)
        cracks = built.first
        junctions = built.second
        sparkles = buildSparkles(rnd)
    }

    private fun buildSparkles(rnd: Random): List<CrackPoint> {
        val list = ArrayList<CrackPoint>(32)
        repeat(32) {
            val a = rnd.nextFloat() * Math.PI.toFloat() * 2f
            val maxLen = maxDistInDirection(a)
            if (maxLen < dp(20f)) return@repeat
            val d = maxLen * (0.15f + rnd.nextFloat() * 0.75f)
            list += CrackPoint(originX + cos(a) * d, originY + sin(a) * d)
        }
        return list
    }

    private fun buildCracks(rnd: Random): Pair<List<Crack>, List<Junction>> {
        val result = ArrayList<Crack>(56)
        val nodes = ArrayList<Junction>(28)
        val mainCount = 11

        // Tiny star burst at origin — sells the shatter epicenter
        val burst = 6
        for (i in 0 until burst) {
            val a = i * (Math.PI.toFloat() * 2f / burst) + 0.2f
            val len = dp(18f) + rnd.nextFloat() * dp(14f)
            val pts = buildCrystallineRay(originX, originY, a, len, 3, rnd, sharp = true)
            if (pts.size >= 2) {
                result += Crack(
                    points = pts,
                    width = dp(1.8f) + rnd.nextFloat() * dp(0.8f),
                    delay = i * 0.012f,
                    branch = false,
                    taper = 0.25f
                )
            }
        }
        nodes += Junction(originX, originY, 0f, dp(3.2f))

        for (i in 0 until mainCount) {
            // Even radial layout with crystalline jitter
            val baseAngle = (i + 0.5f) / mainCount * (Math.PI.toFloat() * 2f) +
                (rnd.nextFloat() - 0.5f) * 0.1f
            val maxLen = maxDistInDirection(baseAngle)
            if (maxLen < dp(40f)) continue

            val targetLen = maxLen * (0.86f + rnd.nextFloat() * 0.12f)
            val segments = 8 + rnd.nextInt(5)
            val points = buildCrystallineRay(
                originX, originY, baseAngle, targetLen, segments, rnd, sharp = true
            )
            if (points.size < 3) continue

            val mainWidth = dp(2.6f) + rnd.nextFloat() * dp(2.2f)
            result += Crack(
                points = points,
                width = mainWidth,
                delay = 0.03f + i * 0.02f,
                branch = false,
                taper = 0.32f + rnd.nextFloat() * 0.18f
            )

            // Primary forks — prefer ~60° crystalline angles
            val forks = if (points.size > 6) 2 else 1
            repeat(forks) { fork ->
                val fromIndex = ((0.32f + fork * 0.28f) * (points.size - 1)).toInt()
                    .coerceIn(2, points.size - 2)
                val from = points[fromIndex]
                val side = if ((i + fork) % 2 == 0) 1f else -1f
                val crystal = (0.95f + rnd.nextFloat() * 0.25f) * side // ~55–70°
                val dir = baseAngle + crystal + (rnd.nextFloat() - 0.5f) * 0.18f
                val branchMax = remainingDistFrom(from.x, from.y, dir)
                if (branchMax < dp(22f)) return@repeat
                val branchLen = branchMax * (0.48f + rnd.nextFloat() * 0.38f)
                val branchPts = buildCrystallineRay(
                    from.x, from.y, dir, branchLen, 5 + rnd.nextInt(3), rnd, sharp = true
                )
                if (branchPts.size < 2) return@repeat

                val branchDelay = 0.07f + i * 0.022f + fork * 0.035f
                val branchWidth = mainWidth * (0.45f + rnd.nextFloat() * 0.25f)
                result += Crack(
                    points = listOf(from) + branchPts.drop(1),
                    width = branchWidth,
                    delay = branchDelay,
                    branch = true,
                    taper = 0.28f
                )
                nodes += Junction(from.x, from.y, branchDelay, branchWidth * 0.55f)

                // Tiny twig off the fork
                if (branchPts.size > 3 && rnd.nextFloat() > 0.35f) {
                    val twigFrom = branchPts[branchPts.size / 2]
                    val twigDir = dir + side * -(0.7f + rnd.nextFloat() * 0.35f)
                    val twigMax = remainingDistFrom(twigFrom.x, twigFrom.y, twigDir)
                    if (twigMax > dp(16f)) {
                        val twigLen = twigMax * (0.3f + rnd.nextFloat() * 0.3f)
                        val twigPts = buildCrystallineRay(
                            twigFrom.x, twigFrom.y, twigDir, twigLen, 3 + rnd.nextInt(2), rnd, sharp = false
                        )
                        if (twigPts.size >= 2) {
                            result += Crack(
                                points = listOf(twigFrom) + twigPts.drop(1),
                                width = branchWidth * 0.55f,
                                delay = branchDelay + 0.05f,
                                branch = true,
                                taper = 0.22f
                            )
                            nodes += Junction(
                                twigFrom.x, twigFrom.y, branchDelay + 0.05f, branchWidth * 0.35f
                            )
                        }
                    }
                }
            }
        }

        // Fine hairlines for ice grain
        val fine = 12
        for (i in 0 until fine) {
            val angle = (i + 0.35f) / fine * (Math.PI.toFloat() * 2f) + 0.11f
            val maxLen = maxDistInDirection(angle)
            if (maxLen < dp(34f)) continue
            val startD = maxLen * (0.16f + rnd.nextFloat() * 0.18f)
            val len = maxLen * (0.32f + rnd.nextFloat() * 0.28f) - startD
            if (len < dp(16f)) continue
            val start = CrackPoint(originX + cos(angle) * startD, originY + sin(angle) * startD)
            val pts = buildCrystallineRay(start.x, start.y, angle, len, 4 + rnd.nextInt(2), rnd, sharp = false)
            if (pts.size >= 2) {
                result += Crack(
                    points = pts,
                    width = dp(0.9f) + rnd.nextFloat() * dp(0.85f),
                    delay = 0.16f + i * 0.025f,
                    branch = true,
                    taper = 0.35f
                )
            }
        }
        return result to nodes
    }

    /**
     * Ice-like ray: prefers crystalline turn angles (~60°) with sharp zig-zag.
     */
    private fun buildCrystallineRay(
        startX: Float,
        startY: Float,
        startAngle: Float,
        length: Float,
        segments: Int,
        rnd: Random,
        sharp: Boolean
    ): List<CrackPoint> {
        val points = ArrayList<CrackPoint>(segments + 1)
        points += CrackPoint(startX, startY)
        var x = startX
        var y = startY
        var angle = startAngle
        val step = length / segments
        // Preferred fracture turns (radians) — hexagonal ice lattice feel
        val turns = floatArrayOf(-1.05f, -0.62f, 0.62f, 1.05f)
        repeat(segments) { s ->
            val turnAmp = if (sharp) 0.55f + rnd.nextFloat() * 0.35f else 0.35f + rnd.nextFloat() * 0.25f
            val crystal = turns[rnd.nextInt(turns.size)] * turnAmp
            val noise = (rnd.nextFloat() - 0.5f) * 0.14f
            // Alternate bias keeps a lively zig-zag without scribbling
            val zig = if (s % 2 == 0) 0.08f else -0.08f
            angle += crystal * 0.42f + noise + zig

            val allowed = remainingDistFrom(x, y, angle)
            // Slightly uneven segment lengths → more organic shatter
            val uneven = 0.72f + rnd.nextFloat() * 0.5f
            val move = min(step * uneven, allowed * 0.96f)
            if (move < dp(1.4f)) return@repeat
            x += cos(angle) * move
            y += sin(angle) * move
            x = x.coerceIn(edgePadding, width - edgePadding)
            y = y.coerceIn(edgePadding, crackBottomY - edgePadding)
            points += CrackPoint(x, y)
        }
        return points
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0.001f || width == 0 || height == 0) return

        val spread = easeOutQuart(progress)

        // ---- Full-screen frost wash (balanced icy veil) ----
        val fullRadius = hypot(width.toDouble(), height.toDouble()).toFloat() * 1.08f
        val radius = fullRadius * spread

        // Soft cool haze — slightly darker freeze mood
        washPaint.shader = null
        washPaint.color = 0xFF141C2E.toInt()
        washPaint.alpha = (spread * 112).toInt().coerceIn(0, 112)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), washPaint)
        washPaint.alpha = 255

        // Cool bloom from the button
        washPaint.shader = RadialGradient(
            originX,
            originY,
            max(1f, radius),
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
        canvas.drawCircle(originX, originY, radius, washPaint)

        // Soft icy core near the button
        washPaint.shader = RadialGradient(
            originX,
            originY,
            max(1f, radius * 0.38f),
            intArrayOf(0x4488B0C8.toInt(), 0x28385870.toInt(), 0x00000000),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(originX, originY, radius * 0.38f, washPaint)

        // Moderate cool vignette
        washPaint.shader = RadialGradient(
            width / 2f,
            height / 2f,
            hypot(width / 2.0, height / 2.0).toFloat(),
            intArrayOf(0x00000000, 0x30101828.toInt(), 0x60100818.toInt()),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        washPaint.alpha = (spread * 195).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), washPaint)
        washPaint.alpha = 255

        // ---- Cracks only above connection check ----
        canvas.save()
        canvas.clipRect(crackZone)

        for (crack in cracks) {
            drawCrack(canvas, crack, spread)
        }

        // Glints at fracture junctions
        for (node in junctions) {
            val local = ((progress - node.delay) / (1f - node.delay).coerceAtLeast(0.25f))
                .coerceIn(0f, 1f)
            if (local < 0.15f) continue
            val pulse = 0.55f + 0.45f * sin(progress * 7f + node.x * 0.04f)
            junctionPaint.alpha = (local * spread * pulse * 160).toInt().coerceIn(0, 180)
            canvas.drawCircle(node.x, node.y, node.size * (0.7f + pulse * 0.35f), junctionPaint)
            drawStarSpark(canvas, node.x, node.y, node.size * 1.8f, junctionPaint.alpha)
        }

        for (spark in sparkles) {
            if (spark.y > crackBottomY) continue
            val dist = hypot((spark.x - originX).toDouble(), (spark.y - originY).toDouble()).toFloat()
            if (dist > radius * 0.9f) continue
            val twinkle = 0.35f + 0.65f * sin(progress * 9f + spark.x * 0.035f + spark.y * 0.02f)
            sparkPaint.alpha = (twinkle * spread * 115).toInt().coerceIn(0, 130)
            canvas.drawCircle(spark.x, spark.y, 1.1f + twinkle * 1.0f, sparkPaint)
        }

        canvas.restore()
    }

    private fun drawStarSpark(canvas: Canvas, cx: Float, cy: Float, size: Float, alpha: Int) {
        if (alpha < 8) return
        starPath.rewind()
        val r = size
        val ir = size * 0.28f
        for (i in 0 until 4) {
            val a = i * (Math.PI.toFloat() / 2f) - Math.PI.toFloat() / 4f
            val ox = cos(a) * r
            val oy = sin(a) * r
            val ix = cos(a + Math.PI.toFloat() / 4f) * ir
            val iy = sin(a + Math.PI.toFloat() / 4f) * ir
            if (i == 0) starPath.moveTo(cx + ox, cy + oy) else starPath.lineTo(cx + ox, cy + oy)
            starPath.lineTo(cx + ix, cy + iy)
        }
        starPath.close()
        sparkPaint.alpha = (alpha * 0.85f).toInt().coerceIn(0, 200)
        canvas.drawPath(starPath, sparkPaint)
    }

    private fun drawCrack(canvas: Canvas, crack: Crack, spread: Float) {
        if (crack.points.size < 2) return

        val localProgress = ((progress - crack.delay) / (1f - crack.delay).coerceAtLeast(0.28f))
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

        tmpPath.rewind()
        tmpPath.moveTo(crack.points[0].x, crack.points[0].y)
        when {
            whole <= 0 -> tmpPath.lineTo(endX, endY)
            whole == 1 -> tmpPath.quadTo(crack.points[1].x, crack.points[1].y, endX, endY)
            else -> {
                val p1 = crack.points[1]
                tmpPath.lineTo(
                    (crack.points[0].x + p1.x) * 0.5f,
                    (crack.points[0].y + p1.y) * 0.5f
                )
                for (i in 1 until whole) {
                    val cur = crack.points[i]
                    val next = crack.points[i + 1]
                    tmpPath.quadTo(
                        cur.x, cur.y,
                        (cur.x + next.x) * 0.5f,
                        (cur.y + next.y) * 0.5f
                    )
                }
                tmpPath.quadTo(crack.points[whole].x, crack.points[whole].y, endX, endY)
            }
        }

        val alphaScale = if (crack.branch) 0.8f else 1f
        val tipBoost = 0.62f + 0.38f * grow
        val avgTaper = 0.55f + crack.taper * 0.45f

        crackGlowPaint.color = 0x77C4F0FF.toInt()
        crackGlowPaint.strokeWidth = crack.width * 4.6f * avgTaper
        crackGlowPaint.alpha = (grow * spread * tipBoost * 92 * alphaScale).toInt().coerceIn(0, 118)
        canvas.drawPath(tmpPath, crackGlowPaint)

        crackDeepPaint.strokeWidth = crack.width * 1.75f * avgTaper
        crackDeepPaint.alpha = (grow * spread * 155 * alphaScale).toInt().coerceIn(0, 175)
        canvas.drawPath(tmpPath, crackDeepPaint)

        crackMidPaint.color = 0xE0D8F2FF.toInt()
        crackMidPaint.strokeWidth = crack.width * 0.95f * avgTaper
        crackMidPaint.alpha = (grow * spread * 230 * alphaScale).toInt().coerceIn(0, 240)
        canvas.drawPath(tmpPath, crackMidPaint)

        crackCorePaint.color = 0xFFFCFEFF.toInt()
        crackCorePaint.strokeWidth = crack.width * 0.32f * avgTaper
        crackCorePaint.alpha = (grow * spread * 255 * alphaScale).toInt().coerceIn(0, 255)
        canvas.drawPath(tmpPath, crackCorePaint)

        drawTaperedSegments(canvas, crack, whole, frac, endX, endY, grow, spread, alphaScale)

        if (!crack.branch && grow > 0.28f && whole >= 2) {
            val chipCount = min(whole, 5)
            for (i in 1..chipCount) {
                val t = i.toFloat() / (chipCount + 1)
                val idx = (t * whole).toInt().coerceIn(1, whole)
                val p = crack.points[idx]
                val prev = crack.points[idx - 1]
                val dx = p.x - prev.x
                val dy = p.y - prev.y
                val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
                val nx = -dy / len
                val ny = dx / len
                val side = if (i % 2 == 0) 1f else -1f
                val wFade = 1f - t * (1f - crack.taper)
                val chipLen = crack.width * (2.1f + i * 0.4f) * wFade
                val chipAlpha = (grow * spread * (150 - i * 12) * alphaScale).toInt().coerceIn(0, 170)

                crackRimPaint.alpha = (chipAlpha * 0.45f).toInt()
                crackRimPaint.strokeWidth = crack.width * 0.45f
                canvas.drawLine(
                    p.x, p.y,
                    p.x + nx * side * chipLen * 0.85f,
                    p.y + ny * side * chipLen * 0.85f,
                    crackRimPaint
                )
                crackCorePaint.alpha = chipAlpha
                crackCorePaint.strokeWidth = crack.width * 0.2f
                canvas.drawLine(
                    p.x, p.y,
                    p.x + nx * side * chipLen,
                    p.y + ny * side * chipLen,
                    crackCorePaint
                )
            }
        }

        if (grow in 0.05f..0.97f) {
            val tipA = ((1f - abs(grow - 0.7f)) * spread * 230).toInt().coerceIn(0, 235)
            sparkPaint.alpha = tipA
            canvas.drawCircle(endX, endY, crack.width * 0.55f + dp(0.6f), sparkPaint)
            drawStarSpark(canvas, endX, endY, crack.width * 1.1f + dp(1.2f), tipA)
        }
    }

    private fun drawTaperedSegments(
        canvas: Canvas,
        crack: Crack,
        whole: Int,
        frac: Float,
        endX: Float,
        endY: Float,
        grow: Float,
        spread: Float,
        alphaScale: Float
    ) {
        val n = crack.points.size - 1
        if (n < 1 || whole < 1) return
        val segs = if (frac > 0.01f && whole < n) whole else whole.coerceAtLeast(1)
        for (i in 0 until segs) {
            val a = crack.points[i]
            val b = if (i == whole && frac > 0.01f && whole < n) {
                CrackPoint(endX, endY)
            } else {
                crack.points[(i + 1).coerceAtMost(crack.points.lastIndex)]
            }
            val t = (i + 1f) / n
            val w = crack.width * (1f - t * (1f - crack.taper))
            crackCorePaint.strokeWidth = w * 0.28f
            crackCorePaint.alpha = (grow * spread * (210 - t * 40) * alphaScale).toInt().coerceIn(0, 230)
            canvas.drawLine(a.x, a.y, b.x, b.y, crackCorePaint)
        }
    }

    /** Fast crack, soft landing. */
    private fun fractureEase(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x) * (1f - x) * (1f - x)
    }

    private fun easeOutQuart(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u * u
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
