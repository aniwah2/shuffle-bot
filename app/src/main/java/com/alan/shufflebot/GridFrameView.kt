package com.alan.shufflebot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/**
 * Moldura 6x6 semitransparente para alinhar com o tabuleiro do jogo.
 * Arraste pelo meio para mover; arraste a bolinha amarela (canto inferior direito) para redimensionar.
 * Fica vermelha quando está em cima da barra de status / navegação.
 */
class GridFrameView(ctx: Context) : View(ctx) {

    var onDrag: ((Int, Int) -> Unit)? = null
    var onResize: ((Int) -> Unit)? = null

    /** Foto do tabuleiro de uma batalha anterior, desenhada semitransparente como referência. */
    var ghost: Bitmap? = null
        set(v) { field = v; invalidate() }

    private val ghostPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 120 }
    private val slot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 120, 100, 80) }
    private val slotEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255); style = Paint.Style.STROKE
    }
    private val readArea = Paint().apply { color = Color.argb(150, 255, 230, 0); style = Paint.Style.STROKE }

    var warning: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val d = ctx.resources.displayMetrics.density

    private val okColor = Color.rgb(0, 230, 110)
    private val warnColor = Color.rgb(255, 60, 60)

    private val fill = Paint()
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val lines = Paint().apply { strokeWidth = 1 * d }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 200, 0) }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * d
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 30, 0); style = Paint.Style.STROKE
        strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val moveIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255); style = Paint.Style.STROKE
        strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private var lastX = 0f
    private var lastY = 0f
    private var resizing = false

    private val handleR get() = 18 * d
    private val handleCx get() = width - handleR - 4 * d
    private val handleCy get() = height - handleR - 4 * d

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val c = if (warning) warnColor else okColor
        fill.color = Color.argb(45, Color.red(c), Color.green(c), Color.blue(c))
        border.color = c
        lines.color = Color.argb(200, Color.red(c), Color.green(c), Color.blue(c))

        canvas.drawRect(0f, 0f, w, h, fill)
        val cw = w / 6; val ch = h / 6
        val g = ghost
        if (g != null) {
            // referência: o tabuleiro como estava numa batalha — encaixe os Pokémon da foto nos de verdade
            canvas.drawBitmap(g, null, RectF(0f, 0f, w, h), ghostPaint)
        } else {
            // sem foto ainda: desenha os espaços das casas como no jogo
            slotEdge.strokeWidth = 1.5f * d
            for (r in 0 until 6) for (c in 0 until 6) {
                val rect = RectF(c * cw + cw * 0.05f, r * ch + ch * 0.05f, (c + 1) * cw - cw * 0.05f, (r + 1) * ch - ch * 0.05f)
                canvas.drawRoundRect(rect, cw * 0.15f, cw * 0.15f, slot)
                canvas.drawRoundRect(rect, cw * 0.15f, cw * 0.15f, slotEdge)
            }
        }
        // área que o bot realmente lê em cada casa (o miolo): o Pokémon tem que ficar dentro dela
        readArea.strokeWidth = 1f * d
        for (r in 0 until 6) for (c in 0 until 6) {
            canvas.drawRect(c * cw + cw * 0.18f, r * ch + ch * 0.18f, (c + 1) * cw - cw * 0.18f, (r + 1) * ch - ch * 0.18f, readArea)
        }
        for (k in 1 until 6) {
            canvas.drawLine(w * k / 6, 0f, w * k / 6, h, lines)
            canvas.drawLine(0f, h * k / 6, w, h * k / 6, lines)
        }
        canvas.drawRect(0f, 0f, w, h, border)

        drawMoveIcon(canvas, w / 2, h / 2, 14 * d)
        drawResizeHandle(canvas)
    }

    /** Seta de 4 pontas no centro: "arraste para mover". */
    private fun drawMoveIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val a = r * 0.35f
        canvas.drawLine(cx - r, cy, cx + r, cy, moveIcon)
        canvas.drawLine(cx, cy - r, cx, cy + r, moveIcon)
        val p = Path().apply {
            moveTo(cx - r + a, cy - a); lineTo(cx - r, cy); lineTo(cx - r + a, cy + a)
            moveTo(cx + r - a, cy - a); lineTo(cx + r, cy); lineTo(cx + r - a, cy + a)
            moveTo(cx - a, cy - r + a); lineTo(cx, cy - r); lineTo(cx + a, cy - r + a)
            moveTo(cx - a, cy + r - a); lineTo(cx, cy + r); lineTo(cx + a, cy + r - a)
        }
        canvas.drawPath(p, moveIcon)
    }

    /** Bolinha amarela com seta diagonal de duas pontas: "arraste para redimensionar". */
    private fun drawResizeHandle(canvas: Canvas) {
        val cx = handleCx
        val cy = handleCy
        val r = handleR
        canvas.drawCircle(cx, cy, r, handleFill)
        canvas.drawCircle(cx, cy, r, handleRing)

        val s = r * 0.5f   // meio comprimento da seta
        val a = r * 0.32f  // tamanho da ponta
        canvas.drawLine(cx - s, cy - s, cx + s, cy + s, icon)
        val p = Path().apply {
            moveTo(cx - s + a, cy - s); lineTo(cx - s, cy - s); lineTo(cx - s, cy - s + a)
            moveTo(cx + s - a, cy + s); lineTo(cx + s, cy + s); lineTo(cx + s, cy + s - a)
        }
        canvas.drawPath(p, icon)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.rawX; lastY = e.rawY
                val dx = e.x - handleCx
                val dy = e.y - handleCy
                // área de toque da bolinha um pouco maior que o desenho
                resizing = dx * dx + dy * dy <= (handleR * 2f) * (handleR * 2f)
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
