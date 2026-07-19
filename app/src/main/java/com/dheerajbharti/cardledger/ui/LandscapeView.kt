package com.dheerajbharti.cardledger.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min

class LandscapeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var month = 1
    private var budgetProgress = 0f
    private var yearProgress = 0f
    private var syncProgress = -1f
    private var cloudOffset = 0f
    private var animator: ValueAnimator? = null

    fun setState(
        month: Int,
        budgetProgress: Float,
        yearProgress: Float,
        syncProgress: Float = -1f,
        syncing: Boolean = false
    ) {
        this.month = month.coerceIn(1, 12)
        this.budgetProgress = budgetProgress.coerceAtLeast(0f)
        this.yearProgress = yearProgress.coerceAtLeast(0f)
        this.syncProgress = syncProgress
        if (syncing) startCloudAnimation() else stopCloudAnimation()
        invalidate()
    }

    private fun startCloudAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 7000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                cloudOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopCloudAnimation() {
        animator?.cancel()
        animator = null
        cloudOffset = 0f
    }

    override fun onDetachedFromWindow() {
        stopCloudAnimation()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val palette = paletteForMonth(month, budgetProgress)
        paint.shader = LinearGradient(0f, 0f, 0f, h, palette.skyTop, palette.skyBottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        drawSun(canvas, w, h, palette.sun)
        drawClouds(canvas, w, h, palette.cloud)
        drawMountains(canvas, w, h, palette.mountainFar, palette.mountainNear)
        drawLake(canvas, w, h, palette.lake)
        drawTrees(canvas, w, h, palette.tree)
        if (budgetProgress > 1f) drawRain(canvas, w, h)
        if (syncProgress >= 0f) drawBrushStroke(canvas, w, h)
    }

    private fun drawSun(canvas: Canvas, w: Float, h: Float, color: Int) {
        paint.color = color
        paint.alpha = 215
        canvas.drawCircle(w * 0.82f, h * 0.24f, h * 0.10f, paint)
        paint.alpha = 255
    }

    private fun drawClouds(canvas: Canvas, w: Float, h: Float, color: Int) {
        paint.color = color
        paint.alpha = 220
        val offset = cloudOffset * w
        drawCloud(canvas, ((w * 0.12f + offset) % (w * 1.2f)) - w * 0.1f, h * 0.23f, h * 0.055f)
        drawCloud(canvas, ((w * 0.57f + offset * 0.55f) % (w * 1.25f)) - w * 0.12f, h * 0.16f, h * 0.045f)
        if (budgetProgress > 0.8f) {
            paint.alpha = 180
            drawCloud(canvas, ((w * 0.34f + offset * 0.35f) % (w * 1.2f)) - w * 0.1f, h * 0.31f, h * 0.05f)
        }
        paint.alpha = 255
    }

    private fun drawCloud(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.drawCircle(x, y, r, paint)
        canvas.drawCircle(x + r * 0.9f, y - r * 0.35f, r * 1.2f, paint)
        canvas.drawCircle(x + r * 2f, y, r * 0.9f, paint)
        canvas.drawRoundRect(x - r * 0.2f, y, x + r * 2.8f, y + r * 0.9f, r, r, paint)
    }

    private fun drawMountains(canvas: Canvas, w: Float, h: Float, farColor: Int, nearColor: Int) {
        val peakBoost = min(yearProgress, 1.5f) * h * 0.08f
        paint.color = farColor
        path.reset()
        path.moveTo(0f, h * 0.72f)
        path.lineTo(w * 0.22f, h * 0.42f)
        path.lineTo(w * 0.39f, h * 0.65f)
        path.lineTo(w * 0.62f, h * 0.30f - peakBoost)
        path.lineTo(w * 0.82f, h * 0.62f)
        path.lineTo(w, h * 0.44f)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        canvas.drawPath(path, paint)

        paint.color = nearColor
        path.reset()
        path.moveTo(0f, h * 0.77f)
        path.lineTo(w * 0.34f, h * 0.55f)
        path.lineTo(w * 0.53f, h * 0.73f)
        path.lineTo(w * 0.77f, h * 0.48f)
        path.lineTo(w, h * 0.70f)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        canvas.drawPath(path, paint)

        paint.color = Color.argb(185, 245, 248, 247)
        path.reset()
        path.moveTo(w * 0.54f, h * 0.43f - peakBoost * 0.55f)
        path.lineTo(w * 0.62f, h * 0.30f - peakBoost)
        path.lineTo(w * 0.70f, h * 0.43f)
        path.lineTo(w * 0.64f, h * 0.39f)
        path.lineTo(w * 0.60f, h * 0.45f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawLake(canvas: Canvas, w: Float, h: Float, color: Int) {
        paint.color = color
        paint.alpha = 205
        canvas.drawRect(0f, h * 0.72f, w, h, paint)
        paint.alpha = 70
        paint.color = Color.WHITE
        for (i in 0..4) {
            val y = h * (0.77f + i * 0.045f)
            canvas.drawRoundRect(w * 0.18f, y, w * (0.55f + i * 0.05f), y + 2f, 2f, 2f, paint)
        }
        paint.alpha = 255
    }

    private fun drawTrees(canvas: Canvas, w: Float, h: Float, color: Int) {
        val remaining = (1f - min(budgetProgress, 1f)).coerceIn(0f, 1f)
        val treeCount = max(2, (2 + remaining * 8).toInt())
        paint.color = color
        repeat(treeCount) { index ->
            val x = w * (0.04f + index * 0.92f / max(1, treeCount - 1))
            val scale = 0.65f + (index % 3) * 0.15f
            drawTree(canvas, x, h * 0.88f, h * 0.15f * scale)
        }
    }

    private fun drawTree(canvas: Canvas, x: Float, baseY: Float, size: Float) {
        paint.color = Color.rgb(92, 70, 48)
        canvas.drawRect(x - size * 0.05f, baseY - size * 0.35f, x + size * 0.05f, baseY, paint)
        paint.color = if (budgetProgress > 1f) Color.rgb(52, 76, 70) else Color.rgb(48, 98, 67)
        repeat(3) { layer ->
            val top = baseY - size * (0.35f + layer * 0.22f)
            val half = size * (0.40f - layer * 0.07f)
            path.reset()
            path.moveTo(x, top - size * 0.34f)
            path.lineTo(x - half, top + size * 0.18f)
            path.lineTo(x + half, top + size * 0.18f)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun drawRain(canvas: Canvas, w: Float, h: Float) {
        paint.color = Color.argb(150, 45, 73, 92)
        paint.strokeWidth = 2f
        repeat(18) { i ->
            val x = (i * 73f) % w
            val y = h * 0.15f + (i % 5) * h * 0.08f
            canvas.drawLine(x, y, x - 8f, y + 18f, paint)
        }
    }

    private fun drawBrushStroke(canvas: Canvas, w: Float, h: Float) {
        val progress = syncProgress.coerceIn(0f, 1f)
        paint.color = Color.rgb(231, 163, 75)
        paint.strokeWidth = h * 0.035f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(w * 0.08f, h * 0.92f, w * (0.08f + 0.84f * progress), h * 0.92f, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = Color.rgb(120, 78, 46)
        canvas.drawRoundRect(
            w * (0.08f + 0.84f * progress) - 5f,
            h * 0.88f,
            w * (0.08f + 0.84f * progress) + 18f,
            h * 0.96f,
            6f,
            6f,
            paint
        )
    }

    private data class Palette(
        val skyTop: Int,
        val skyBottom: Int,
        val sun: Int,
        val cloud: Int,
        val mountainFar: Int,
        val mountainNear: Int,
        val lake: Int,
        val tree: Int
    )

    private fun paletteForMonth(month: Int, budgetProgress: Float): Palette {
        if (budgetProgress > 1f) {
            return Palette(
                Color.rgb(106, 127, 141), Color.rgb(171, 184, 187), Color.rgb(231, 188, 112),
                Color.rgb(109, 119, 126), Color.rgb(90, 105, 112), Color.rgb(67, 87, 85),
                Color.rgb(85, 122, 130), Color.rgb(46, 82, 66)
            )
        }
        return when (month) {
            12, 1, 2 -> Palette(
                Color.rgb(95, 160, 214), Color.rgb(219, 237, 247), Color.rgb(248, 225, 160),
                Color.WHITE, Color.rgb(120, 150, 177), Color.rgb(88, 122, 149),
                Color.rgb(95, 163, 183), Color.rgb(41, 91, 67)
            )
            3, 4, 5 -> Palette(
                Color.rgb(102, 177, 221), Color.rgb(236, 228, 211), Color.rgb(248, 198, 104),
                Color.rgb(255, 248, 232), Color.rgb(132, 143, 171), Color.rgb(93, 119, 126),
                Color.rgb(91, 170, 170), Color.rgb(55, 112, 68)
            )
            6, 7, 8, 9 -> Palette(
                Color.rgb(92, 144, 177), Color.rgb(193, 216, 211), Color.rgb(234, 187, 105),
                Color.rgb(224, 233, 229), Color.rgb(103, 123, 132), Color.rgb(68, 104, 91),
                Color.rgb(77, 139, 143), Color.rgb(41, 90, 59)
            )
            else -> Palette(
                Color.rgb(90, 157, 205), Color.rgb(236, 211, 174), Color.rgb(242, 166, 76),
                Color.rgb(255, 239, 216), Color.rgb(135, 122, 137), Color.rgb(103, 101, 101),
                Color.rgb(89, 151, 166), Color.rgb(68, 99, 60)
            )
        }
    }
}
