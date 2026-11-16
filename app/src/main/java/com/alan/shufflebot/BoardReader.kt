package com.alan.shufflebot

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs

/** Posição do tabuleiro na tela (em pixels). O tabuleiro é quadrado, N x N casas. */
data class BoardGeom(val left: Int, val top: Int, val size: Int, val n: Int = 6) {
    val cell: Float get() = size.toFloat() / n
    fun center(index: Int): PointF {
        val r = index / n
        val c = index % n
        return PointF(left + (c + 0.5f) * cell, top + (r + 0.5f) * cell)
    }
}

/**
 * Lê o tabuleiro sem precisar conhecer os Pokémon: cada casa vira uma "assinatura" de cores
 * (grade 4x4 de médias RGB do miolo da casa), e casas com assinaturas parecidas são o mesmo tipo.
 */
class BoardReader(private val blocks: Int = 4, private val inset: Float = 0.18f) {

    fun features(bmp: Bitmap, g: BoardGeom): Array<FloatArray>? {
        if (g.size <= 0 || g.left < 0 || g.top < 0 ||
            g.left + g.size > bmp.width || g.top + g.size > bmp.height
        ) return null

        val cs = g.cell
        val w = (cs * (1 - 2 * inset)).toInt().coerceAtLeast(blocks)
        val px = IntArray(w * w)

        return Array(g.n * g.n) { i ->
            val r = i / g.n
            val c = i % g.n
            val x0 = (g.left + c * cs + cs * inset).toInt().coerceIn(0, bmp.width - w)
            val y0 = (g.top + r * cs + cs * inset).toInt().coerceIn(0, bmp.height - w)
            bmp.getPixels(px, 0, w, x0, y0, w, w)

            val f = FloatArray(blocks * blocks * 3)
            val cnt = IntArray(blocks * blocks)
            for (yy in 0 until w) {
                val by = yy * blocks / w
                for (xx in 0 until w) {
                    val b = by * blocks + xx * blocks / w
                    val col = px[yy * w + xx]
                    f[b * 3] += ((col shr 16) and 0xFF).toFloat()
                    f[b * 3 + 1] += ((col shr 8) and 0xFF).toFloat()
                    f[b * 3 + 2] += (col and 0xFF).toFloat()
                    cnt[b]++
                }
            }
            for (b in 0 until blocks * blocks) {
                val k = cnt[b].coerceAtLeast(1).toFloat()
                for (ch in 0 until 3) f[b * 3 + ch] = f[b * 3 + ch] / k
            }
            f
        }
    }

    /** Diferença média por canal (0..255). */
    fun dist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    /** Quantas casas mudaram entre dois prints. */
    fun changedCells(a: Array<FloatArray>, b: Array<FloatArray>, thr: Float = 18f): Int {
        var n = 0
        for (i in a.indices) if (dist(a[i], b[i]) > thr) n++
        return n
    }

    /** Agrupa casas parecidas. Retorna um rótulo (0, 1, 2...) por casa. */
    fun cluster(feats: Array<FloatArray>, thr: Float): IntArray {
        val reps = ArrayList<FloatArray>()
        val counts = ArrayList<Int>()
        val labels = IntArray(feats.size)
        for (i in feats.indices) {
            var best = -1
            var bestD = Float.MAX_VALUE
            for (k in reps.indices) {
                val d = dist(feats[i], reps[k])
                if (d < bestD) { bestD = d; best = k }
            }
            if (best >= 0 && bestD < thr) {
                labels[i] = best
                // atualiza a média do grupo
                val rep = reps[best]
                val c = counts[best] + 1
                for (j in rep.indices) rep[j] += (feats[i][j] - rep[j]) / c
                counts[best] = c
            } else {
                labels[i] = reps.size
                reps.add(feats[i].copyOf())
                counts.add(1)
            }
        }
        return labels
    }

    /** Brilho médio do tabuleiro (0..255). Escurece quando o jogo abre uma janela por cima. */
    fun brightness(feats: Array<FloatArray>): Float {
        var s = 0f
        var n = 0
        for (f in feats) for (v in f) { s += v; n++ }
        return if (n == 0) 0f else s / n
    }

    /**
     * Confere se os rótulos parecem um tabuleiro de verdade: poucos tipos, todos repetidos várias vezes
     * e nenhum dominando (um fundo liso de menu/janela vira um "tipo" gigante).
     * Retorna null se estiver ok, ou o motivo se não estiver.
     */
    fun boardProblem(labels: IntArray): String? {
        val counts = IntArray((labels.maxOrNull() ?: 0) + 1)
        for (l in labels) counts[l]++
        val types = counts.size
        val singles = counts.count { it <= 1 }
        val biggest = counts.maxOrNull() ?: 0
        val solid = counts.count { it >= 4 }
        return when {
            types < 3 -> "poucos tipos ($types)"
            types > 14 -> "tipos demais ($types)"
            singles > 7 -> "muitas peças soltas"
            biggest > labels.size * 0.6f -> "área lisa grande (janela/menu?)"
            solid < 2 -> "tipos pouco repetidos"
            else -> null
        }
    }
}
