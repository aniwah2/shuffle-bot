package com.alan.shufflebot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Moldura 6x6 semitransparente para alinhar com o tabuleiro do jogo.
 * Arraste pelo meio para mover; arraste o canto inferior direito para redimensionar.
 */
class GridFrameView(ctx: Context) : View(ctx) {

    var onDrag: ((Int, Int) -> Unit)? = null
    var onResize: ((Int) -> Unit)? = null

    private val density = ctx.resources.displayMetrics.density
    private val fill = Paint().apply { color = Color.argb(40, 0, 255, 120) }
    private val border = Paint().apply {
        color = Color.rgb(0, 230, 110); style = Paint.Style.STROKE; strokeWidth = 3 * density
    }
    private val lines = Paint().apply {
        color = Color.argb(200, 0, 230, 110); strokeWidth = 1 * density
    }
    private val handle = Paint().apply { color = Color.rgb(255, 200, 0) }

    private var lastX = 0f
    private var lastY = 0f
    private var resizing = false

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, fill)
        for (k in 1 until 6) {
            canvas.drawLine(w * k / 6, 0f, w * k / 6, h, lines)
            canvas.drawLine(0f, h * k / 6, w, h * k / 6, lines)
        }
        canvas.drawRect(0f, 0f, w, h, border)
        canvas.drawCircle(w - 14 * density, h - 14 * density, 12 * density, handle)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.rawX; lastY = e.rawY
                resizing = e.x > width * 0.75f && e.y > height * 0.75f
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - lastX).toInt()
                val dy = (e.rawY - lastY).toInt()
                if (dx == 0 && dy == 0) return true
                if (resizing) onResize?.invoke((dx + dy) / 2) else onDrag?.invoke(dx, dy)
                lastX += dx; lastY += dy
            }
        }
        return true
    }
}
