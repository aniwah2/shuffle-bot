package com.alan.shufflebot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * Camada por cima do tabuleiro para marcar o time.
 * Tocar numa casa troca o papel daquela espécie: normal → MEGA → FORTE → apoio → normal.
 * Todas as casas da mesma espécie mudam juntas.
 */
class TeamOverlayView(
    ctx: Context,
    private val n: Int,
    private val species: IntArray,          // espécie de cada casa (<0 = bloco/vazio)
    val roles: HashMap<Int, Role>,          // papel por espécie
) : View(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private val shade = Paint()
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d }
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val frame = Paint().apply { color = Color.rgb(255, 200, 0); style = Paint.Style.STROKE; strokeWidth = 3 * d }

    private fun colorOf(r: Role) = when (r) {
        Role.MEGA -> Color.rgb(255, 170, 0)
        Role.FORTE -> Color.rgb(235, 60, 60)
        Role.APOIO -> Color.rgb(120, 140, 160)
        Role.NORMAL -> Color.TRANSPARENT
    }

    override fun onDraw(canvas: Canvas) {
        val cell = width.toFloat() / n
        for (i in species.indices) {
            val r = i / n; val c = i % n
            val x0 = c * cell; val y0 = r * cell
            val sp = species[i]
            if (sp < 0) {
                shade.color = Color.argb(140, 0, 0, 0)
                canvas.drawRect(x0, y0, x0 + cell, y0 + cell, shade)
                continue
            }
            val role = roles[sp] ?: Role.NORMAL
            if (role == Role.NORMAL) {
                shade.color = Color.argb(60, 0, 0, 0)
                canvas.drawRect(x0, y0, x0 + cell, y0 + cell, shade)
                continue
            }
            val col = colorOf(role)
            border.color = col
            canvas.drawRect(x0 + 3 * d, y0 + 3 * d, x0 + cell - 3 * d, y0 + cell - 3 * d, border)
            badgeBg.color = col
            val bw = text.measureText(role.label) + 10 * d
            val by = y0 + cell - 22 * d
            canvas.drawRoundRect(x0 + cell / 2 - bw / 2, by, x0 + cell / 2 + bw / 2, by + 17 * d, 8 * d, 8 * d, badgeBg)
            canvas.drawText(role.label, x0 + cell / 2, by + 13 * d, text)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), frame)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val cell = width.toFloat() / n
            val c = (e.x / cell).toInt().coerceIn(0, n - 1)
            val r = (e.y / cell).toInt().coerceIn(0, n - 1)
            val sp = species[r * n + c]
            if (sp >= 0) {
                val next = (roles[sp] ?: Role.NORMAL).next()
                if (next == Role.NORMAL) roles.remove(sp) else roles[sp] = next
                invalidate()
            }
        }
        return true
    }
}
