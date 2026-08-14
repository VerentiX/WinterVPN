package com.v2ray.ang.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mechanical connect ceremony:
 * docking bay waits → shield launches → slo-mo split with gears →
 * Хоттабыч emerges and winks → shield reassembles → slam into cradle → bay powers on.
 */
class ShieldLaunchOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun interface Callback {
        fun invoke()
    }

    private data class Spark(
        val angle: Float,
        val dist: Float,
        val size: Float,
        val phase: Float,
        val cool: Boolean
    )

    private val camera = Camera()
    private val matrix3d = Matrix()
    private val shieldPath = Path()
    private val checkPath = Path()
    private val gearPathA = Path()
    private val gearPathB = Path()
    private val gearPathC = Path()
    private val panelClip = Path()
    private val chassisPath = Path()
    private val dockShieldPath = Path()
    private val tmpMatrix = Matrix()
    private val tmpRect = RectF()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        color = 0xEEFFFFFF.toInt()
    }
    private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x99B8F5FF.toInt()
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val socketPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gearPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gearStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        color = 0xCC9ED8FF.toInt()
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var originX = 0f
    private var originY = 0f
    private var buttonRadius = 0f
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var playing = false
    private var paused = false

    private var onImpact: Callback? = null
    private var onEnd: Callback? = null
    private var impactFired = false
    /** After impact the real dock is visible — stop painting the flying shield. */
    private var postImpact = false
    private var finishing = false

    private var sparks: List<Spark> = emptyList()
    private val trailSlots = Array(8) { FloatArray(4) }
    private var trailCount = 0
    private var trailSample = 0

    private val dens by lazy { resources.displayMetrics.density }
    private val deg2rad = (Math.PI / 180.0).toFloat()

    private var glowShader: RadialGradient? = null
    private var glowKeyX = Float.NaN
    private var glowKeyY = Float.NaN
    private var glowKeyR = Float.NaN
    private var bodyShader: LinearGradient? = null
    private var bodyShadeKey = -1
    private var sheenShader: LinearGradient? = null
    private var sheenKey = -1
    private var socketShader: RadialGradient? = null
    private var socketKeyR = Float.NaN

    private val emergeEase = DecelerateInterpolator(1.35f)
    private val diveEase = AccelerateInterpolator(2.4f)
    private val seatEase = PathInterpolator(0.1f, 0.9f, 0.2f, 1f)
    private val mechEase = PathInterpolator(0.22f, 0.9f, 0.28f, 1f)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        visibility = INVISIBLE
        alpha = 1f
        buildShieldGeometry()
        buildCheckGeometry()
        buildChassisGeometry()
        buildGear(gearPathA, teeth = 10, outer = 18f, inner = 12f, hub = 4.5f)
        buildGear(gearPathB, teeth = 8, outer = 13f, inner = 8.5f, hub = 3.2f)
        buildGear(gearPathC, teeth = 7, outer = 9.5f, inner = 6.2f, hub = 2.4f)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    val isPlaying: Boolean get() = playing

    fun cancel() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        playing = false
        paused = false
        finishing = false
        progress = 0f
        impactFired = false
        postImpact = false
        trailCount = 0
        setLayerType(LAYER_TYPE_NONE, null)
        visibility = INVISIBLE
        onImpact = null
        onEnd = null
    }

    fun pause() {
        if (!playing || paused) return
        paused = true
        animator?.pause()
    }

    fun resume() {
        if (!playing || !paused) return
        paused = false
        animator?.resume()
    }

    fun play(source: View, onImpact: Callback? = null, onEnd: Callback? = null) {
        val start = {
            updateOrigin(source)
            this.onImpact = onImpact
            this.onEnd = onEnd
            impactFired = false
            postImpact = false
            finishing = false
            trailCount = 0
            trailSample = 0
            sparks = buildSparks()
            playing = true
            paused = false
            setLayerType(LAYER_TYPE_NONE, null)
            visibility = VISIBLE
            progress = 0f
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3_200L
                interpolator = LinearNoOp
                var lastDrawNs = 0L
                addUpdateListener {
                    if (paused) return@addUpdateListener
                    progress = it.animatedValue as Float
                    maybeFireImpact()
                    // Once the dock handoff is done, end quickly — no need to spin to t=1.
                    if (postImpact && progress >= 0.84f) {
                        finishCeremony(completed = true)
                        return@addUpdateListener
                    }
                    val now = System.nanoTime()
                    val interval = when {
                        progress >= 0.70f -> 33_333_333L // ~30fps on final approach
                        progress < 0.18f || progress >= 0.64f -> 16_666_667L
                        else -> 20_833_333L
                    }
                    if (lastDrawNs == 0L || now - lastDrawNs >= interval) {
                        lastDrawNs = now
                        invalidate()
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        finishCeremony(completed = true)
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        finishCeremony(completed = false)
                    }
                })
                start()
            }
        }
        if (width == 0 || height == 0 || !source.isLaidOut) {
            post {
                if (!source.isLaidOut) source.post(start) else start()
            }
        } else {
            start()
        }
    }

    private object LinearNoOp : android.view.animation.Interpolator {
        override fun getInterpolation(input: Float): Float = input
    }

    private fun finishCeremony(completed: Boolean) {
        if (finishing) return
        finishing = true
        val anim = animator
        animator = null
        anim?.removeAllListeners()
        anim?.cancel()

        playing = false
        paused = false
        setLayerType(LAYER_TYPE_NONE, null)
        visibility = INVISIBLE
        progress = 0f
        postImpact = false
        trailCount = 0
        val end = onEnd
        val impact = onImpact
        onEnd = null
        onImpact = null
        if (completed && !impactFired) {
            impactFired = true
            impact?.invoke()
        }
        if (completed) end?.invoke()
        impactFired = false
        finishing = false
    }

    private fun maybeFireImpact() {
        if (impactFired || progress < 0.78f) return
        impactFired = true
        postImpact = true
        visibility = INVISIBLE
        onImpact?.invoke()
    }

    private fun updateOrigin(source: View) {
        val sourceLoc = IntArray(2)
        val selfLoc = IntArray(2)
        source.getLocationInWindow(sourceLoc)
        getLocationInWindow(selfLoc)
        originX = sourceLoc[0] - selfLoc[0] + source.width / 2f
        originY = sourceLoc[1] - selfLoc[1] + source.height / 2f
        buttonRadius = min(source.width, source.height) * 0.5f
    }

    private fun dp(v: Float): Float = v * dens

    private fun buildShieldGeometry() {
        // Soft security-shield: rounded crown, clean taper to a tip.
        shieldPath.reset()
        shieldPath.moveTo(0f, -54f)
        shieldPath.cubicTo(20f, -54f, 41f, -48f, 42f, -26f)
        shieldPath.lineTo(42f, 2f)
        shieldPath.cubicTo(42f, 24f, 24f, 46f, 0f, 58f)
        shieldPath.cubicTo(-24f, 46f, -42f, 24f, -42f, 2f)
        shieldPath.lineTo(-42f, -26f)
        shieldPath.cubicTo(-41f, -48f, -20f, -54f, 0f, -54f)
        shieldPath.close()
    }

    private fun buildCheckGeometry() {
        checkPath.reset()
        checkPath.moveTo(-14f, -1f)
        checkPath.lineTo(-4f, 11f)
        checkPath.lineTo(16f, -13f)
        checkPath.lineTo(12f, -17f)
        checkPath.lineTo(-4f, 3f)
        checkPath.lineTo(-10f, -5f)
        checkPath.close()
    }

    /** Inner tech chassis revealed when armor panels peel away. */
    private fun buildChassisGeometry() {
        chassisPath.reset()
        chassisPath.moveTo(0f, -40f)
        chassisPath.cubicTo(22f, -40f, 34f, -32f, 34f, -16f)
        chassisPath.lineTo(34f, 4f)
        chassisPath.cubicTo(34f, 22f, 18f, 36f, 0f, 42f)
        chassisPath.cubicTo(-18f, 36f, -34f, 22f, -34f, 4f)
        chassisPath.lineTo(-34f, -16f)
        chassisPath.cubicTo(-34f, -32f, -22f, -40f, 0f, -40f)
        chassisPath.close()
    }

    private fun buildGear(out: Path, teeth: Int, outer: Float, inner: Float, hub: Float) {
        out.reset()
        val step = (Math.PI * 2.0 / teeth)
        for (i in 0 until teeth) {
            val a0 = i * step
            val a1 = a0 + step * 0.28
            val a2 = a0 + step * 0.50
            val a3 = a0 + step * 0.72
            fun pt(r: Float, a: Double) = (r * cos(a)).toFloat() to (r * sin(a)).toFloat()
            val (x0, y0) = pt(inner, a0)
            if (i == 0) out.moveTo(x0, y0) else out.lineTo(x0, y0)
            val (x1, y1) = pt(outer, a1)
            out.lineTo(x1, y1)
            val (x2, y2) = pt(outer, a2)
            out.lineTo(x2, y2)
            val (x3, y3) = pt(inner, a3)
            out.lineTo(x3, y3)
        }
        out.close()
        out.addCircle(0f, 0f, hub, Path.Direction.CW)
    }

    private fun buildSparks(): List<Spark> {
        val rnd = Random(System.nanoTime())
        return List(10) {
            Spark(
                angle = rnd.nextFloat() * (Math.PI * 2).toFloat(),
                dist = 18f + rnd.nextFloat() * 70f,
                size = 1.6f + rnd.nextFloat() * 3.4f,
                phase = rnd.nextFloat(),
                cool = rnd.nextBoolean()
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (postImpact || (!playing && progress <= 0f)) return

        val t = progress
        val pose = computePose(t)

        // Real dock stays on the FAB during flight — don't paint a second bay over it.

        val x = pose.x
        val y = pose.y
        val pathScale = pose.scale * dens
        val bodyAlpha = pose.alpha
        val rotY = pose.rotY
        val impactFlash = pose.impactFlash
        val seatT = pose.seatT
        val trailStrength = pose.trailStrength

        if (trailStrength > 0.05f && seatT < 0.15f) {
            trailSample++
            if (trailSample % 2 == 0) {
                pushTrail(x, y, pathScale * 0.42f, trailStrength)
            }
        } else if (seatT > 0.1f) {
            trailCount = 0
        }
        if (trailCount > 0 && seatT < 0.2f) {
            drawTrail(canvas)
        }

        val glowR = 78f * dens * (0.55f + pose.glow) * (1f - seatT * 0.35f)
        if (glowR > 4f && bodyAlpha > 0.05f) {
            ensureGlowShader(x, y, max(1f, glowR))
            glowPaint.shader = glowShader
            glowPaint.alpha = (255 * bodyAlpha).toInt()
            canvas.drawCircle(x, y, max(1f, glowR), glowPaint)
        }

        // After impact, one ring is enough — frost + dock are competing for the frame.
        if (impactFlash > 0.01f || seatT > 0f) {
            drawShockwaves(canvas, x, y, dens, impactFlash, seatT, lite = seatT > 0.05f)
        }
        if (impactFlash > 0.02f && seatT < 0.35f) {
            drawImpactSparks(canvas, dens, impactFlash)
        }

        if (bodyAlpha < 0.02f) {
            if (impactFlash > 0.01f) {
                flashPaint.color = (0x00E8FBFF) or (((110 * impactFlash).toInt().coerceIn(0, 255)) shl 24)
                canvas.drawCircle(originX, originY, dp(118f) * (0.55f + impactFlash), flashPaint)
            }
            return
        }

        val save = canvas.save()
        if (seatT > 0.25f) {
            // Face-on seat: skip Camera/Matrix3D once the shield is almost docked.
            canvas.translate(x, y)
            canvas.scale(pathScale, pathScale)
            ensureBodyShader(40, 1f)
            drawSplitShield(canvas, 0f, bodyAlpha)
        } else {
            camera.save()
            camera.setLocation(0f, 0f, -8f)
            camera.rotateY(rotY)
            camera.getMatrix(matrix3d)
            camera.restore()
            matrix3d.preScale(pathScale, pathScale)
            matrix3d.postTranslate(x, y)
            canvas.concat(matrix3d)

            val face = kotlin.math.abs(cos(rotY * deg2rad))
            val faceShade = 0.72f + 0.28f * face
            val shadeKey = (faceShade * 40f).toInt()
            ensureBodyShader(shadeKey, faceShade)

            if (pose.shieldSplit > 0.02f) {
                drawChassis(canvas, pose.shieldSplit, bodyAlpha)
                drawClockwork(canvas, pose.gearAngle, pose.shieldSplit * bodyAlpha)
                if (pose.hottabychReveal > 0.02f) {
                    drawEmergingHottabych(canvas, pose.hottabychReveal, pose.hottabychWink, bodyAlpha)
                }
            }
            drawSplitShield(canvas, pose.shieldSplit, bodyAlpha)
        }

        canvas.restoreToCount(save)

        if (impactFlash > 0.01f) {
            flashPaint.color = (0x00E8FBFF) or (((110 * impactFlash).toInt().coerceIn(0, 255)) shl 24)
            canvas.drawCircle(originX, originY, dp(118f) * (0.55f + impactFlash), flashPaint)
        }
    }

    private fun drawButtonShell(canvas: Canvas, open: Float, socketGlow: Float) {
        if (open <= 0.01f && socketGlow <= 0.01f) return
        val r = buttonRadius.coerceAtLeast(dp(88f))
        val peel = mechEase.getInterpolation(open.coerceIn(0f, 1f))
        // Technological docking bay only — no petal petals / rainbow arcs.
        drawTechDock(canvas, r, peel, socketGlow)
    }

    /** Open button core while the shield is airborne — gear bay + empty shield cradle. */
    private fun drawTechDock(canvas: Canvas, r: Float, peel: Float, socketGlow: Float) {
        val open = peel.coerceIn(0f, 1f)
        if (open < 0.02f) return

        val pulse = 0.55f + 0.45f * sin(progress * Math.PI.toFloat() * 5f).let { (it + 1f) * 0.5f }
        val gearSpin = progress * 280f

        // Dark circular bay plate.
        ensureSocketShader(r * (0.52f + open * 0.06f))
        socketPaint.shader = socketShader
        socketPaint.alpha = ((210 + 40 * open) * (0.5f + 0.5f * open)).toInt().coerceIn(0, 255)
        canvas.drawCircle(originX, originY, r * (0.46f + open * 0.04f), socketPaint)

        corePaint.shader = RadialGradient(
            originX, originY - r * 0.04f, r * 0.40f,
            intArrayOf(0xFF071018.toInt(), 0xFF122033.toInt(), 0xFF1A1028.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        corePaint.alpha = (225 * open).toInt().coerceIn(0, 255)
        canvas.drawCircle(originX, originY, r * 0.40f, corePaint)

        // Side gear wells — technological mechanism around the cradle.
        fun dockGear(path: Path, cx: Float, cy: Float, ang: Float, scale: Float, color: Int, a: Float) {
            val s = canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(ang)
            canvas.scale(scale, scale)
            val alpha = (a * 255f).toInt().coerceIn(0, 255)
            gearPaint.color = (color and 0x00FFFFFF) or (alpha shl 24)
            canvas.drawPath(path, gearPaint)
            gearStroke.strokeWidth = 1.2f
            gearStroke.alpha = (alpha * 0.9f).toInt()
            canvas.drawPath(path, gearStroke)
            canvas.restoreToCount(s)
        }

        val ga = (open * (0.55f + pulse * 0.35f)).coerceIn(0f, 1f)
        // Soft wells behind gears.
        corePaint.shader = null
        corePaint.color = (0x00050A12) or (((180 * open).toInt().coerceIn(0, 255)) shl 24)
        canvas.drawCircle(originX - r * 0.32f, originY - r * 0.06f, r * 0.12f, corePaint)
        canvas.drawCircle(originX + r * 0.32f, originY - r * 0.06f, r * 0.12f, corePaint)
        canvas.drawCircle(originX - r * 0.28f, originY + r * 0.16f, r * 0.11f, corePaint)
        canvas.drawCircle(originX + r * 0.28f, originY + r * 0.16f, r * 0.11f, corePaint)
        canvas.drawCircle(originX, originY + r * 0.32f, r * 0.10f, corePaint)
        canvas.drawCircle(originX - r * 0.10f, originY - r * 0.30f, r * 0.09f, corePaint)
        canvas.drawCircle(originX + r * 0.10f, originY - r * 0.30f, r * 0.09f, corePaint)

        val gs = dens * 0.9f
        dockGear(gearPathB, originX - r * 0.10f, originY - r * 0.30f, gearSpin * 1.5f, gs * 0.68f, 0xFFA8DCFF.toInt(), ga * 0.9f)
        dockGear(gearPathB, originX + r * 0.10f, originY - r * 0.30f, -gearSpin * 1.5f, gs * 0.68f, 0xFFA8DCFF.toInt(), ga * 0.9f)
        dockGear(gearPathC, originX, originY - r * 0.24f, gearSpin * 2.1f, gs * 0.52f, 0xFF7AB8FF.toInt(), ga * 0.85f)
        dockGear(gearPathA, originX - r * 0.32f, originY - r * 0.06f, gearSpin, gs * 1.05f, 0xFF7AB8FF.toInt(), ga)
        dockGear(gearPathB, originX - r * 0.24f, originY + r * 0.06f, -gearSpin * 1.35f, gs * 0.7f, 0xFF9AD0FF.toInt(), ga * 0.9f)
        dockGear(gearPathA, originX + r * 0.32f, originY - r * 0.06f, -gearSpin, gs * 1.05f, 0xFF7AB8FF.toInt(), ga)
        dockGear(gearPathB, originX + r * 0.24f, originY + r * 0.06f, gearSpin * 1.35f, gs * 0.7f, 0xFF9AD0FF.toInt(), ga * 0.9f)
        dockGear(gearPathB, originX - r * 0.28f, originY + r * 0.16f, gearSpin * 1.7f, gs * 0.75f, 0xFFA8DCFF.toInt(), ga * 0.9f)
        dockGear(gearPathB, originX + r * 0.28f, originY + r * 0.16f, -gearSpin * 1.7f, gs * 0.75f, 0xFFA8DCFF.toInt(), ga * 0.9f)
        dockGear(gearPathC, originX - r * 0.16f, originY + r * 0.28f, -gearSpin * 2f, gs * 0.55f, 0xFF6AA8FF.toInt(), ga * 0.85f)
        dockGear(gearPathC, originX + r * 0.16f, originY + r * 0.28f, gearSpin * 2f, gs * 0.55f, 0xFF6AA8FF.toInt(), ga * 0.85f)
        dockGear(gearPathA, originX, originY + r * 0.32f, gearSpin * 1.15f, gs * 0.8f, 0xFF9AD0FF.toInt(), ga * 0.85f)

        // Empty shield cradle — silhouette the returning shield seats into.
        val socketScale = (r * 0.72f) / 108f
        dockShieldPath.set(shieldPath)
        tmpMatrix.reset()
        tmpMatrix.setScale(socketScale, socketScale)
        tmpMatrix.postTranslate(originX, originY)
        dockShieldPath.transform(tmpMatrix)

        // Deep recessed cavity.
        corePaint.shader = LinearGradient(
            originX, originY - r * 0.35f,
            originX, originY + r * 0.38f,
            intArrayOf(0xFF03080F.toInt(), 0xFF0A1524.toInt(), 0xFF06101A.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        corePaint.alpha = (245 * open).toInt().coerceIn(0, 255)
        canvas.drawPath(dockShieldPath, corePaint)

        // Soft inner glow waiting for payload.
        if (socketGlow > 0.02f) {
            val g = socketGlow * (0.7f + pulse * 0.3f)
            corePaint.shader = RadialGradient(
                originX, originY, r * 0.28f,
                intArrayOf(0x5540C4FF.toInt(), 0x2240A8FF.toInt(), 0x00000000),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            corePaint.alpha = (g * 180).toInt().coerceIn(0, 255)
            canvas.drawPath(dockShieldPath, corePaint)
        }

        // Tech rim of the empty socket.
        ringPaint.strokeWidth = dp(2.4f)
        ringPaint.color = (0x00B8F5FF) or
            (((170 * open * (0.75f + pulse * 0.25f)).toInt().coerceIn(0, 255)) shl 24)
        canvas.drawPath(dockShieldPath, ringPaint)
        ringPaint.strokeWidth = dp(1.2f)
        ringPaint.color = (0x00E8FBFF) or (((110 * open).toInt().coerceIn(0, 255)) shl 24)
        canvas.save()
        canvas.scale(0.86f, 0.86f, originX, originY)
        canvas.drawPath(dockShieldPath, ringPaint)
        canvas.restore()

        // Tiny alignment rivets along the socket edge.
        corePaint.shader = null
        corePaint.color = (0x00D6F0FF) or (((140 * open * pulse).toInt().coerceIn(0, 255)) shl 24)
        val rivetR = socketScale
        val rivets = floatArrayOf(
            0f, -48f,
            -28f, -30f,
            28f, -30f,
            -36f, 4f,
            36f, 4f,
            -18f, 36f,
            18f, 36f,
            0f, 48f
        )
        var i = 0
        while (i < rivets.size) {
            canvas.drawCircle(
                originX + rivets[i] * rivetR,
                originY + rivets[i + 1] * rivetR,
                dp(1.6f),
                corePaint
            )
            i += 2
        }
    }

    private fun drawClockwork(canvas: Canvas, gearAngle: Float, alpha: Float) {
        if (alpha < 0.02f) return
        val vis = (0.55f + 0.45f * alpha).coerceIn(0f, 1f)
        val a = (vis * 255).toInt().coerceIn(0, 255)

        fun gear(path: Path, cx: Float, cy: Float, ang: Float, color: Int, scale: Float) {
            val s = canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(ang)
            canvas.scale(scale, scale)
            gearPaint.color = (color and 0x00FFFFFF) or (a shl 24)
            canvas.drawPath(path, gearPaint)
            gearStroke.alpha = (a * 0.92f).toInt()
            canvas.drawPath(path, gearStroke)
            canvas.restoreToCount(s)
        }

        fun well(cx: Float, cy: Float, radius: Float) {
            corePaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(0xFF050A12.toInt(), 0xFF152438.toInt(), 0x00000000),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            corePaint.alpha = (a * 0.95f).toInt()
            canvas.drawCircle(cx, cy, radius, corePaint)
            ringPaint.strokeWidth = 1.4f
            ringPaint.color = (0x00A8D8FF) or ((a * 0.55f).toInt().coerceIn(0, 255) shl 24)
            canvas.drawCircle(cx, cy, radius * 0.92f, ringPaint)
        }

        // Center bay — main clockwork.
        well(0f, 4f, 30f)
        gear(gearPathA, -9f, 3f, gearAngle, 0xFF8EC8FF.toInt(), 1.35f)
        gear(gearPathB, 14f, -8f, -gearAngle * 1.35f, 0xFFB8F0FF.toInt(), 1.15f)
        gear(gearPathC, 5f, 16f, gearAngle * 1.8f, 0xFF6AA8FF.toInt(), 1.05f)
        gear(gearPathC, -13f, 16f, -gearAngle * 1.1f, 0xFF9AD0FF.toInt(), 0.9f)
        corePaint.shader = null
        corePaint.color = (0x00E8FBFF) or ((a * 0.95f).toInt().coerceIn(0, 255) shl 24)
        canvas.drawCircle(0f, 4f, 3.6f, corePaint)
        ringPaint.strokeWidth = 1.5f
        ringPaint.color = (0x00B8F5FF) or ((a * 0.75f).toInt().coerceIn(0, 255) shl 24)
        canvas.drawCircle(0f, 4f, 9f, ringPaint)

        // Upper side bays — peek through cheek cuts.
        well(-27f, -24f, 14f)
        gear(gearPathB, -28f, -25f, gearAngle * 1.6f, 0xFFA8DCFF.toInt(), 0.92f)
        gear(gearPathC, -21f, -16f, -gearAngle * 2f, 0xFF7AB8FF.toInt(), 0.72f)

        well(27f, -24f, 14f)
        gear(gearPathB, 28f, -25f, -gearAngle * 1.6f, 0xFFA8DCFF.toInt(), 0.92f)
        gear(gearPathC, 21f, -16f, gearAngle * 2f, 0xFF7AB8FF.toInt(), 0.72f)
    }

    /** Tech chassis under the armor — drawn before gears so they sit in the wells. */
    private fun drawChassis(canvas: Canvas, split: Float, bodyAlpha: Float) {
        val a = ((210 * split) * bodyAlpha).toInt().coerceIn(0, 255)
        corePaint.shader = LinearGradient(
            -30f, -40f, 30f, 40f,
            intArrayOf(0xFF0A1422.toInt(), 0xFF1A2E44.toInt(), 0xFF0E1828.toInt()),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        corePaint.alpha = a
        canvas.drawPath(chassisPath, corePaint)

        ringPaint.strokeWidth = 1.7f
        ringPaint.color = (0x00A8D8FF) or (((160 * split * bodyAlpha).toInt().coerceIn(0, 255)) shl 24)
        canvas.drawPath(chassisPath, ringPaint)

        ringPaint.strokeWidth = 1.15f
        ringPaint.alpha = ((130 * split) * bodyAlpha).toInt().coerceIn(0, 255)
        canvas.drawLine(-18f, -10f, 18f, -10f, ringPaint)
        canvas.drawLine(0f, -30f, 0f, 30f, ringPaint)
        canvas.drawLine(-22f, 14f, 22f, 14f, ringPaint)
        canvas.drawLine(-16f, -10f, -6f, 14f, ringPaint)
        canvas.drawLine(16f, -10f, 6f, 14f, ringPaint)

        corePaint.shader = null
        corePaint.color = (0x00D6F0FF) or
            (((140 * split * bodyAlpha).toInt().coerceIn(0, 255)) shl 24)
        for (i in 0..4) {
            val y = lerp(-26f, 26f, i / 4f)
            canvas.drawCircle(0f, y, 1.55f, corePaint)
        }
        canvas.drawCircle(-14f, -8f, 1.4f, corePaint)
        canvas.drawCircle(14f, -8f, 1.4f, corePaint)
        canvas.drawCircle(-10f, 12f, 1.4f, corePaint)
        canvas.drawCircle(10f, 12f, 1.4f, corePaint)
    }

    private fun drawEmergingHottabych(canvas: Canvas, reveal: Float, wink: Float, bodyAlpha: Float) {
        canvas.save()
        canvas.translate(0f, -6f)
        HottabychFace.draw(
            canvas,
            reveal,
            wink,
            bodyAlpha,
            bodyPaint,
            gearStroke,
            glowPaint
        )
        canvas.restore()
    }

    private fun drawSplitShield(canvas: Canvas, split: Float, bodyAlpha: Float) {
        val gap = split * 28f
        val flap = split * 16f
        val cheek = split * 24f
        val cheekRot = split * 22f
        val crestLift = split * 20f

        fun drawArmorFace(alphaMul: Float = 1f) {
            bodyPaint.shader = bodyShader
            bodyPaint.alpha = (255 * bodyAlpha * alphaMul).toInt().coerceIn(0, 255)
            canvas.drawPath(shieldPath, bodyPaint)
            val sheenBucket = ((split * 40f) / 12f).toInt()
            ensureSheenShader(sheenBucket, ((split * 40f) % 360f) / 360f)
            sheenPaint.shader = sheenShader
            sheenPaint.alpha = (175 * bodyAlpha * alphaMul).toInt().coerceIn(0, 255)
            canvas.drawPath(shieldPath, sheenPaint)
            rimPaint.strokeWidth = 4.8f
            rimPaint.alpha = (235 * bodyAlpha * alphaMul).toInt().coerceIn(0, 255)
            canvas.drawPath(shieldPath, rimPaint)
            canvas.save()
            canvas.scale(0.78f, 0.78f)
            innerRimPaint.alpha = (150 * bodyAlpha * alphaMul).toInt().coerceIn(0, 255)
            canvas.drawPath(shieldPath, innerRimPaint)
            canvas.restore()
            if (split < 0.12f) {
                corePaint.shader = null
                corePaint.color = (0x00FFFFFF) or
                    (((210 * bodyAlpha * alphaMul * (1f - split * 4f)).toInt().coerceIn(0, 255)) shl 24)
                canvas.drawPath(checkPath, corePaint)
            }
        }

        fun rivets(x: Float, y0: Float, y1: Float, count: Int) {
            if (split < 0.08f) return
            corePaint.shader = null
            corePaint.color = (0x00D6F0FF) or
                (((130 * split * bodyAlpha).toInt().coerceIn(0, 255)) shl 24)
            for (i in 0 until count) {
                val t = if (count == 1) 0.5f else i / (count - 1f)
                canvas.drawCircle(x, lerp(y0, y1, t), 1.65f, corePaint)
            }
        }

        fun techSeam(x0: Float, y0: Float, x1: Float, y1: Float) {
            if (split < 0.05f) return
            rimPaint.strokeWidth = 1.9f
            rimPaint.alpha = ((220 * split) * bodyAlpha).toInt().coerceIn(0, 255)
            canvas.drawLine(x0, y0, x1, y1, rimPaint)
            rimPaint.strokeWidth = 3.6f
            rimPaint.alpha = ((75 * split) * bodyAlpha).toInt().coerceIn(0, 255)
            canvas.drawLine(x0, y0, x1, y1, rimPaint)
        }

        fun plate(clip: Path, tx: Float, ty: Float, rot: Float) {
            val s = canvas.save()
            canvas.translate(tx, ty)
            canvas.rotate(rot)
            canvas.clipPath(clip)
            drawArmorFace()
            canvas.restoreToCount(s)
        }

        if (split < 0.02f) {
            drawArmorFace()
            return
        }

        // Crest — lifts to reveal the upper gear wells.
        panelClip.reset()
        panelClip.moveTo(-14f, -56f)
        panelClip.lineTo(14f, -56f)
        panelClip.lineTo(10f, -28f)
        panelClip.lineTo(0f, -22f)
        panelClip.lineTo(-10f, -28f)
        panelClip.close()
        plate(panelClip, 0f, -crestLift, 0f)

        // Upper-left cheek — diagonal tech cut toward the center bay.
        panelClip.reset()
        panelClip.moveTo(-50f, -56f)
        panelClip.lineTo(-12f, -56f)
        panelClip.lineTo(-8f, -30f)
        panelClip.lineTo(-12f, -6f)
        panelClip.lineTo(-50f, -12f)
        panelClip.close()
        plate(panelClip, -cheek * 0.8f, -cheek * 0.85f, -cheekRot)

        // Upper-right cheek.
        panelClip.reset()
        panelClip.moveTo(12f, -56f)
        panelClip.lineTo(50f, -56f)
        panelClip.lineTo(50f, -12f)
        panelClip.lineTo(12f, -6f)
        panelClip.lineTo(8f, -30f)
        panelClip.close()
        plate(panelClip, cheek * 0.8f, -cheek * 0.85f, cheekRot)

        // Left main armor — clean center seam + chevron shoulder.
        panelClip.reset()
        panelClip.moveTo(-50f, -14f)
        panelClip.lineTo(-14f, -8f)
        panelClip.lineTo(-1.15f, -2f)
        panelClip.lineTo(-1.15f, 58f)
        panelClip.lineTo(-50f, 22f)
        panelClip.close()
        plate(panelClip, -gap, -split * 2.5f, -flap)

        // Right main armor.
        panelClip.reset()
        panelClip.moveTo(1.15f, -2f)
        panelClip.lineTo(14f, -8f)
        panelClip.lineTo(50f, -14f)
        panelClip.lineTo(50f, 22f)
        panelClip.lineTo(1.15f, 58f)
        panelClip.close()
        plate(panelClip, gap, -split * 2.5f, flap)

        // Seam glow along the cuts (world space — sits in the gaps).
        techSeam(0f, -20f, 0f, 50f)
        techSeam(-10f, -50f, -12f, -6f)
        techSeam(10f, -50f, 12f, -6f)
        techSeam(-8f, -28f, 0f, -20f)
        techSeam(8f, -28f, 0f, -20f)
        rivets(0f, -16f, 42f, 7)
        rivets(-11f, -46f, -10f, 4)
        rivets(11f, -46f, -10f, 4)
    }

    private fun pushTrail(x: Float, y: Float, r: Float, strength: Float) {
        for (i in trailSlots.lastIndex downTo 1) {
            val src = trailSlots[i - 1]
            val dst = trailSlots[i]
            dst[0] = src[0]; dst[1] = src[1]; dst[2] = src[2]; dst[3] = src[3]
        }
        trailSlots[0][0] = x
        trailSlots[0][1] = y
        trailSlots[0][2] = r
        trailSlots[0][3] = strength
        trailCount = min(trailCount + 1, trailSlots.size)
    }

    private fun ensureGlowShader(x: Float, y: Float, r: Float) {
        if (glowShader != null &&
            absDiff(glowKeyX, x) < 3f &&
            absDiff(glowKeyY, y) < 3f &&
            absDiff(glowKeyR, r) < 4f
        ) return
        glowKeyX = x; glowKeyY = y; glowKeyR = r
        glowShader = RadialGradient(
            x, y, r,
            intArrayOf(0x66A8F7FF.toInt(), 0x3340C4FF.toInt(), 0x00000000),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun ensureSocketShader(r: Float) {
        if (socketShader != null && absDiff(socketKeyR, r) < 1.5f) return
        socketKeyR = r
        socketShader = RadialGradient(
            originX, originY, max(1f, r),
            intArrayOf(0xFF0B1524.toInt(), 0xFF162033.toInt(), 0xFF1A1028.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun ensureBodyShader(key: Int, faceShade: Float) {
        if (bodyShader != null && bodyShadeKey == key) return
        bodyShadeKey = key
        bodyShader = LinearGradient(
            -42f, -54f, 42f, 58f,
            intArrayOf(
                shade(0xFFFAFFFE.toInt(), faceShade),
                shade(0xFF5AD4C4.toInt(), faceShade),
                shade(0xFF2F6FA0.toInt(), faceShade)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun ensureSheenShader(key: Int, sheen01: Float) {
        if (sheenShader != null && sheenKey == key) return
        sheenKey = key
        sheenShader = LinearGradient(
            -50f + sheen01 * 40f, -60f,
            20f + sheen01 * 40f, 20f,
            intArrayOf(0x88FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun absDiff(a: Float, b: Float): Float = kotlin.math.abs(a - b)

    private fun shade(color: Int, factor: Float): Int {
        val a = color ushr 24
        val r = ((color shr 16) and 0xFF) * factor
        val g = ((color shr 8) and 0xFF) * factor
        val b = (color and 0xFF) * factor
        return (a shl 24) or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)
    }

    private data class Pose(
        val x: Float,
        val y: Float,
        val scale: Float,
        val alpha: Float,
        val rotY: Float,
        val impactFlash: Float,
        val seatT: Float,
        val trailStrength: Float,
        val glow: Float,
        val shellOpen: Float,
        val socketGlow: Float,
        val shieldSplit: Float,
        val gearAngle: Float,
        val hottabychReveal: Float,
        val hottabychWink: Float
    )

    private fun computePose(t: Float): Pose {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        val offTopY = -dp(150f)
        val cruiseStartX = w * 0.82f
        // Constant altitude across the screen; lift + exit only at the end.
        val cruiseY = h * 0.56f
        val leftExitX = -dp(160f)
        val leftExitY = cruiseY - dp(64f)

        val shellOpenEnd = 0.10f
        val launchEnd = 0.30f
        val reenterEnd = 0.38f
        val flyEnd = 0.70f
        val slamEnd = 0.84f

        // Overlay bay stays until impact, then yields to the real dock underneath.
        val shellOpen = when {
            t < shellOpenEnd -> mechEase.getInterpolation(segment(t, 0f, shellOpenEnd))
            t < 0.76f -> 1f
            else -> 1f - mechEase.getInterpolation(segment(t, 0.76f, 0.84f))
        }
        val socketGlow = when {
            t < shellOpenEnd -> shellOpen
            t < launchEnd -> 1f
            t < 0.76f -> 0.85f + 0.15f * sin(t * 18f)
            else -> shellOpen * 0.25f
        }

        val launchT = emergeEase.getInterpolation(segment(t, 0.04f, launchEnd))
        val reenterT = segment(t, launchEnd, reenterEnd)
        val flyRaw = segment(t, reenterEnd, flyEnd)
        val slamRaw = segment(t, flyEnd, slamEnd)
        val slamT = diveEase.getInterpolation(slamRaw)
        val seatT = seatEase.getInterpolation(segment(t, 0.76f, 0.93f))
        val impactFlash = pulse(0.76f, 0.92f, t)
        val faceSettle = seatEase.getInterpolation(segment(t, flyEnd, 0.90f))

        // Split while in the longer cruise window after the slower launch.
        val splitOpen = mechEase.getInterpolation(segment(t, 0.39f, 0.46f))
        val splitClose = 1f - mechEase.getInterpolation(segment(t, 0.62f, 0.70f))
        val shieldSplit = when {
            t < 0.39f -> 0f
            t < 0.46f -> splitOpen
            t < 0.62f -> 1f
            t < 0.70f -> splitClose
            else -> 0f
        }.coerceIn(0f, 1f)

        val emergeIn = mechEase.getInterpolation(segment(t, 0.40f, 0.50f))
        val emergeOut = 1f - mechEase.getInterpolation(segment(t, 0.58f, 0.68f))
        val hottabychReveal = (shieldSplit * emergeIn * emergeOut).coerceIn(0f, 1f)
        val winkWave = sin((t - 0.47f) * 38f)
        val hottabychWink = if (hottabychReveal > 0.55f && t in 0.47f..0.64f && winkWave > 0.62f) {
            ((winkWave - 0.62f) / 0.38f).coerceIn(0f, 1f)
        } else {
            0f
        }

        val gearAngle = t * 520f + shieldSplit * 180f

        val x: Float
        val y: Float
        when {
            t < 0.04f -> {
                x = originX
                y = originY
            }
            t < launchEnd -> {
                x = lerp(originX, originX + dp(56f), launchT)
                y = lerp(originY, offTopY, launchT)
            }
            t < reenterEnd -> {
                val fromX = w + dp(70f)
                val fromY = cruiseY - dp(72f) // appear a bit higher from the right edge
                x = lerp(fromX, cruiseStartX, reenterT)
                y = lerp(fromY, cruiseY, reenterT)
            }
            t < flyEnd -> {
                // Same level almost to the left; last ~22% lifts and goes past the edge.
                val flatEndX = dp(40f)
                if (flyRaw < 0.78f) {
                    val across = flyRaw / 0.78f
                    x = lerp(cruiseStartX, flatEndX, across)
                    y = cruiseY
                } else {
                    val exitT = seatEase.getInterpolation((flyRaw - 0.78f) / 0.22f)
                    x = lerp(flatEndX, leftExitX, exitT)
                    y = lerp(cruiseY, leftExitY, exitT)
                }
            }
            else -> {
                // Come back from beyond the left edge into the button.
                val approachX = lerp(leftExitX, originX, slamT)
                val approachY = lerp(leftExitY, originY, slamT)
                x = lerp(approachX, originX, seatT)
                y = lerp(approachY, originY, seatT)
            }
        }

        val launchScale = lerp(0.18f, 1.35f, launchT)
        val closeScale = 1.72f
        val growEnd = reenterEnd + 0.08f
        val sloMoHoldEnd = reenterEnd + 0.24f
        // Match the real dock shield size so the handoff doesn't pop/vanish.
        val dockMatchScale = ((buttonRadius * 0.72f) / (108f * dens)).coerceIn(0.48f, 0.78f)
        val flightScale = when {
            t < 0.04f -> 0.12f
            t < launchEnd -> launchScale
            t < reenterEnd -> lerp(1.05f, 1.38f, reenterT)
            t < growEnd -> {
                val grow = seatEase.getInterpolation(segment(t, reenterEnd, growEnd))
                lerp(1.38f, closeScale, grow)
            }
            t < sloMoHoldEnd -> closeScale
            t < flyEnd -> {
                val recede = if (flyRaw < 0.78f) 0f else (flyRaw - 0.78f) / 0.22f
                lerp(closeScale, 1.05f, recede)
            }
            else -> {
                val approach = lerp(1.05f, 0.78f, slamT)
                val settle = lerp(approach, dockMatchScale, seatT)
                settle * (1f + 0.18f * impactFlash * (1f - seatT))
            }
        }

        val approachSpinEnd = reenterEnd * 820f
        val sloMoSpin = 200f
        val whipSpin = 380f
        // Milder slam twist — then unwind to a flat face-on landing.
        val slamTwist = 420f
        val spin = when {
            t < reenterEnd -> t * 820f
            t < sloMoHoldEnd -> {
                val crawl = segment(t, reenterEnd, sloMoHoldEnd)
                approachSpinEnd + crawl * sloMoSpin
            }
            t < flyEnd -> {
                val wind = if (flyRaw < 0.78f) {
                    (flyRaw / 0.78f) * 0.55f
                } else {
                    0.55f + ((flyRaw - 0.78f) / 0.22f) * 0.45f
                }
                approachSpinEnd + sloMoSpin + wind * wind * whipSpin
            }
            else -> approachSpinEnd + sloMoSpin + whipSpin + slamT * slamTwist
        }

        val faceBias = when {
            shieldSplit > 0.05f -> {
                val target = 360f * kotlin.math.round(spin / 360f)
                lerp(spin, target, shieldSplit * 0.85f)
            }
            t >= flyEnd -> {
                val flat = 360f * kotlin.math.round(spin / 360f)
                lerp(spin, flat, faceSettle)
            }
            else -> spin
        }

        // Keep the flying shield solid until the dock is already showing underneath.
        val alpha = when {
            t < 0.03f -> 0f
            t < 0.06f -> smoothstep(0.03f, 0.06f, t)
            t < 0.80f -> 1f
            else -> 1f - smoothstep(0.80f, 0.92f, t)
        }

        return Pose(
            x = x,
            y = y,
            scale = flightScale,
            alpha = alpha.coerceIn(0f, 1f),
            rotY = faceBias,
            impactFlash = impactFlash,
            seatT = seatT,
            trailStrength = when {
                t < launchEnd -> launchT * 0.8f
                t in reenterEnd..sloMoHoldEnd -> 0.28f
                t in sloMoHoldEnd..flyEnd -> 1f
                t < 0.88f -> 0.35f * (1f - seatT)
                else -> 0f
            },
            glow = when {
                t < launchEnd -> launchT
                t in reenterEnd..sloMoHoldEnd -> 1.25f + shieldSplit * 0.35f
                t in flyEnd..0.92f -> 1f
                else -> 0.55f
            },
            shellOpen = shellOpen.coerceIn(0f, 1f),
            socketGlow = socketGlow.coerceIn(0f, 1f),
            shieldSplit = shieldSplit,
            gearAngle = gearAngle,
            hottabychReveal = hottabychReveal,
            hottabychWink = hottabychWink
        )
    }

    private fun segment(t: Float, start: Float, end: Float): Float {
        if (end <= start) return if (t >= end) 1f else 0f
        return ((t - start) / (end - start)).coerceIn(0f, 1f)
    }

    private fun drawTrail(canvas: Canvas) {
        for (i in 0 until trailCount) {
            val p = trailSlots[i]
            val age = i / trailSlots.size.toFloat()
            val a = ((0.32f - age * 0.32f) * p[3]).coerceAtLeast(0f)
            if (a < 0.02f) continue
            trailPaint.shader = null
            trailPaint.color = (0x00B8F5FF) or ((a * 200).toInt() shl 24)
            canvas.drawCircle(p[0], p[1], max(6f, p[2]), trailPaint)
        }
    }

    private fun drawShockwaves(
        canvas: Canvas,
        x: Float,
        y: Float,
        dens: Float,
        flash: Float,
        seat: Float,
        lite: Boolean = false
    ) {
        val rings = if (lite) 1 else 2
        for (i in 0 until rings) {
            val local = (flash * 0.7f + seat * 0.9f - i * 0.22f).coerceIn(0f, 1f)
            if (local <= 0.01f) continue
            val radius = (36f + local * 110f + i * 22f) * dens
            val a = ((1f - local) * (0.85f - i * 0.22f)).coerceIn(0f, 1f)
            ringPaint.strokeWidth = (3.2f - i * 0.6f) * dens
            ringPaint.color = (0x00D6F7FF) or ((a * 200).toInt() shl 24)
            canvas.drawCircle(x, y, radius, ringPaint)
        }
    }

    private fun drawImpactSparks(canvas: Canvas, dens: Float, flash: Float) {
        for (s in sparks) {
            val life = (flash * 1.2f - s.phase * 0.35f).coerceIn(0f, 1f)
            if (life <= 0.02f) continue
            val d = (s.dist + flash * 48f) * dens
            val px = originX + cos(s.angle) * d
            val py = originY + sin(s.angle) * d
            val sz = s.size * dens * (0.55f + life)
            trailPaint.shader = null
            trailPaint.color = if (s.cool) {
                (0x00B8F5FF) or ((life * 230).toInt() shl 24)
            } else {
                (0x00FFFFFF) or ((life * 255).toInt() shl 24)
            }
            canvas.drawCircle(px, py, sz, trailPaint)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
        val tt = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return tt * tt * (3f - 2f * tt)
    }

    private fun pulse(start: Float, end: Float, t: Float): Float {
        if (t < start || t > end) return 0f
        val m = (start + end) * 0.5f
        val half = (end - start) * 0.5f
        return (1f - kotlin.math.abs(t - m) / half).coerceIn(0f, 1f)
    }

    override fun onDetachedFromWindow() {
        cancel()
        super.onDetachedFromWindow()
    }
}
