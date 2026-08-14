package com.v2ray.ang.ui.widget

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stylized Хоттабыч — emerges from the split shield during slo-mo.
 * Local coords: origin at chest; +Y down.
 */
object HottabychFace {

    private val turbanPath = Path()
    private val beardPath = Path()
    private val tasselPath = Path()

    private fun withAlpha(rgb: Int, alpha: Int): Int = (alpha shl 24) or (rgb and 0xFFFFFF)

    fun draw(
        canvas: Canvas,
        reveal: Float,
        wink: Float,
        alpha: Float,
        fill: Paint,
        stroke: Paint,
        glow: Paint
    ) {
        if (reveal <= 0.02f || alpha <= 0.02f) return

        val r = reveal.coerceIn(0f, 1f)
        val a = (255 * alpha * (0.35f + r * 0.65f)).toInt().coerceIn(0, 255)
        val lift = (1f - r) * 34f
        val bob = sin(r * 3.2f) * 2.5f * r
        val scale = (0.42f + r * 0.58f) * (0.88f + r * 0.12f)

        canvas.save()
        canvas.translate(0f, lift + bob)
        canvas.scale(scale, scale)

        // Magic smoke while rising.
        glow.shader = RadialGradient(
            0f, 16f, 38f,
            intArrayOf(0xAAFFE8A0.toInt(), 0x44FFC860.toInt(), 0x00000000),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        glow.alpha = (a * r * 0.55f).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 14f, 36f, glow)
        glow.shader = null

        // Turban.
        turbanPath.reset()
        turbanPath.moveTo(-26f, -8f)
        turbanPath.cubicTo(-30f, -28f, -8f, -36f, 0f, -38f)
        turbanPath.cubicTo(14f, -40f, 32f, -28f, 26f, -6f)
        turbanPath.cubicTo(30f, 2f, 18f, 10f, 0f, 8f)
        turbanPath.cubicTo(-18f, 6f, -28f, 0f, -26f, -8f)
        turbanPath.close()
        fill.shader = LinearGradient(
            -24f, -36f, 24f, 10f,
            intArrayOf(0xFFFF7043.toInt(), 0xFFE64A19.toInt(), 0xFFBF360C.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        fill.alpha = a
        canvas.drawPath(turbanPath, fill)

        // Gold band.
        fill.shader = null
        fill.color = withAlpha(0xFFD54F, a)
        canvas.drawRect(-22f, -14f, 22f, -8f, fill)

        // Tassel.
        tasselPath.reset()
        tasselPath.moveTo(24f, -10f)
        tasselPath.lineTo(30f, 4f)
        tasselPath.lineTo(26f, 6f)
        tasselPath.lineTo(22f, 0f)
        tasselPath.close()
        fill.color = withAlpha(0xFFAB40, a)
        canvas.drawPath(tasselPath, fill)

        // Face.
        fill.shader = RadialGradient(
            -4f, -2f, 24f,
            intArrayOf(0xFFFFE0B2.toInt(), 0xFFFFCC80.toInt(), 0xFFFFB74D.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        fill.alpha = a
        canvas.drawOval(-18f, -10f, 18f, 18f, fill)
        fill.shader = null

        // Beard.
        beardPath.reset()
        beardPath.moveTo(-16f, 6f)
        beardPath.cubicTo(-20f, 22f, -8f, 34f, 0f, 36f)
        beardPath.cubicTo(8f, 34f, 20f, 22f, 16f, 6f)
        beardPath.cubicTo(10f, 14f, -10f, 14f, -16f, 6f)
        beardPath.close()
        fill.color = withAlpha(0xF5F5F5, a)
        canvas.drawPath(beardPath, fill)

        // Brows.
        stroke.strokeWidth = 2.6f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = withAlpha(0x5D4037, a)
        canvas.drawLine(-12f, -2f, -4f, -4f, stroke)
        canvas.drawLine(4f, -4f, 12f, -2f, stroke)

        // Eyes — left winks.
        fill.color = withAlpha(0xFFFFFF, a)
        canvas.drawOval(-13f, 0f, -5f, 8f, fill)
        canvas.drawOval(5f, 0f, 13f, 8f, fill)
        fill.color = withAlpha(0x3E2723, a)
        if (wink > 0.35f) {
            stroke.strokeWidth = 2.4f
            stroke.color = withAlpha(0x3E2723, a)
            canvas.drawLine(-13f, 4f, -5f, 4f, stroke)
        } else {
            canvas.drawCircle(-9f, 5f, 2.2f, fill)
        }
        canvas.drawCircle(9f, 5f, 2.2f, fill)

        // Cheeky smile.
        stroke.strokeWidth = 2.2f
        stroke.color = withAlpha(0x6D4C41, a)
        val smile = Path()
        smile.moveTo(-10f, 14f)
        smile.cubicTo(-4f, 20f, 4f, 20f, 10f, 14f)
        canvas.drawPath(smile, stroke)

        // Sparkle on wink.
        if (wink > 0.2f) {
            val sparkleA = ((180 * wink) * alpha).toInt().coerceIn(0, 255)
            fill.color = withAlpha(0xFFF59D, sparkleA)
            val sx = 16f + cos(1.2f) * 4f
            val sy = -18f + sin(1.2f) * 4f
            canvas.drawCircle(sx, sy, 2.2f, fill)
            canvas.drawCircle(-20f, -16f, 1.6f, fill)
        }

        canvas.restore()
    }
}
