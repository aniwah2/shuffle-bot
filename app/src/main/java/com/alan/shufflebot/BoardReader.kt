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
/** O que o bot entendeu do tabuleiro. */
class BoardView(
    /** Espécie de cada casa (congelado = mesma espécie do livre). Bloco/vazio = número negativo único (nunca combina). */
    val species: IntArray,
    /** Pode ser arrastada? (não: bloco, casa vazia, congelado) */
    val movable: BooleanArray,
    val frozen: BooleanArray,
    val blocks: Int,
    val empty: Int,
) {
    val types: Int get() = species.filter { it >= 0 }.distinct().size
}

class BoardReader(private val blocks: Int = 8, private val inset: Float = 0.18f) {

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

    // ------------------------------------------------------------------ análise completa

    /**
     * Agrupa as casas, reconhece blocos/casas vazias e liga cada Pokémon congelado (barreira)
     * à espécie livre correspondente — no jogo, congelados combinam com os livres da mesma espécie.
     */
    fun analyze(feats: Array<FloatArray>, thr: Float): BoardView {
        val lab = cluster(feats, thr)
        val k = (lab.maxOrNull() ?: 0) + 1
        val dim = feats[0].size
        val reps = Array(k) { FloatArray(dim) }
        val cnt = IntArray(k)
        for (i in feats.indices) { cnt[lab[i]]++; for (j in 0 until dim) reps[lab[i]][j] += feats[i][j] }
        for (c in 0 until k) { val m = cnt[c].coerceAtLeast(1).toFloat(); for (j in 0 until dim) reps[c][j] = reps[c][j] / m }

        val nb = dim / 3
        val bright = FloatArray(k)
        val kind = IntArray(k)          // 0 livre, 1 congelado, 2 bloco, 3 vazio
        for (c in 0 until k) {
            val r = reps[c]
            var sum = 0f; var satSum = 0f
            val lum = FloatArray(nb)
            for (b in 0 until nb) {
                val R = r[b * 3]; val G = r[b * 3 + 1]; val B = r[b * 3 + 2]
                lum[b] = (R + G + B) / 3f; sum += lum[b]
                satSum += maxOf(R, G, B) - minOf(R, G, B)
            }
            val mean = sum / nb
            var v = 0f
            for (b in 0 until nb) v += (lum[b] - mean) * (lum[b] - mean)
            val std = kotlin.math.sqrt(v / nb)
            bright[c] = mean
            kind[c] = when {
                std < 5f -> 3
                satSum / nb < 12f && std < 20f -> 2
                else -> 0
            }
        }

        val spOf = IntArray(k) { it }
        val base = kind.copyOf()
        for (c in 0 until k) {
            if (base[c] != 0) continue
            var bestR = Float.MAX_VALUE; var bestJ = -1
            for (j in 0 until k) {
                if (j == c || base[j] != 0 || bright[c] < bright[j] + 12f) continue
                val r = robustFit(reps[c], reps[j])
                if (r < 19f && r < bestR) { bestR = r; bestJ = j }
            }
            if (bestJ >= 0) { kind[c] = 1; spOf[c] = bestJ }
        }

        val n = feats.size
        val species = IntArray(n)
        val movable = BooleanArray(n)
        val frozen = BooleanArray(n)
        var nBlocks = 0; var nEmpty = 0
        for (i in 0 until n) {
            when (kind[lab[i]]) {
                0 -> { species[i] = spOf[lab[i]]; movable[i] = true }
                1 -> { species[i] = spOf[lab[i]]; frozen[i] = true }
                2 -> { species[i] = -(i + 1); nBlocks++ }
                else -> { species[i] = -(i + 1); nEmpty++ }
            }
        }
        return BoardView(species, movable, frozen, nBlocks, nEmpty)
    }

    /**
     * Quão bem "fz" é uma versão esbranquiçada de "fr" (fz ≈ a·fr + b por canal),
     * ignorando os 25% piores pontos (a faixa branca do gelo). Menor = mais parecido.
     */
    private fun robustFit(fz: FloatArray, fr: FloatArray): Float {
        val nb = fz.size / 3
        val x = FloatArray(nb); val y = FloatArray(nb); val res = FloatArray(nb)
        var total = 0f
        for (ch in 0 until 3) {
            for (b in 0 until nb) { x[b] = fr[b * 3 + ch]; y[b] = fz[b * 3 + ch] }
            var (a, c) = linFit(x, y, null)
            for (b in 0 until nb) res[b] = abs(y[b] - (a * x[b] + c))
            val cut = percentile(res, 0.75f)
            val keep = BooleanArray(nb) { res[it] <= cut }
            val f2 = linFit(x, y, keep); a = f2.first; c = f2.second
            for (b in 0 until nb) res[b] = abs(y[b] - (a * x[b] + c))
            total += percentile(res, 0.75f)
        }
        return total / 3
    }

    private fun linFit(x: FloatArray, y: FloatArray, keep: BooleanArray?): Pair<Float, Float> {
        var n = 0; var sx = 0f; var sy = 0f
        for (i in x.indices) if (keep == null || keep[i]) { n++; sx += x[i]; sy += y[i] }
        if (n < 2) return Pair(1f, 0f)
        val mx = sx / n; val my = sy / n
        var cov = 0f; var vx = 0f
        for (i in x.indices) if (keep == null || keep[i]) {
            cov += (x[i] - mx) * (y[i] - my); vx += (x[i] - mx) * (x[i] - mx)
        }
        val a = if (vx < 1e-3f) 0.05f else (cov / vx).coerceAtLeast(0.05f)
        return Pair(a, my - a * mx)
    }

    private fun percentile(v: FloatArray, q: Float): Float {
        val s = v.sortedArray()
        val pos = (s.size - 1) * q
        val lo = pos.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    /**
     * Confere se parece um tabuleiro de verdade. Retorna null se ok, ou o motivo.
     */
    fun boardProblem(v: BoardView): String? {
        val counts = HashMap<Int, Int>()
        for (sp in v.species) if (sp >= 0) counts[sp] = (counts[sp] ?: 0) + 1
        val n = v.species.size
        val types = counts.size
        val singles = counts.values.count { it <= 1 }
        val biggest = counts.values.maxOrNull() ?: 0
        return when {
            v.blocks + v.empty > n * 0.75f -> "quase tudo liso (janela/menu?)"
            types < 2 -> "poucos tipos ($types)"
            types > 12 -> "tipos demais ($types)"
            singles > 6 -> "muitas peças soltas"
            biggest > n * 0.6f -> "área repetida grande (janela/menu?)"
            else -> null
        }
    }

    // ------------------------------------------------------------------ time

    /** Aparência média de cada espécie, usando só as peças livres (sem gelo). */
    fun speciesLook(view: BoardView, feats: Array<FloatArray>): Map<Int, FloatArray> {
        val sum = HashMap<Int, FloatArray>()
        val cnt = HashMap<Int, Int>()
        for (i in feats.indices) {
            val sp = view.species[i]
            if (sp < 0 || !view.movable[i]) continue
            val acc = sum.getOrPut(sp) { FloatArray(feats[i].size) }
            for (j in acc.indices) acc[j] += feats[i][j]
            cnt[sp] = (cnt[sp] ?: 0) + 1
        }
        for ((sp, acc) in sum) { val c = cnt[sp]!!.toFloat(); for (j in acc.indices) acc[j] = acc[j] / c }
        return sum
    }

    /** Descobre o papel de cada espécie do tabuleiro comparando com o time salvo. */
    fun rolesFor(view: BoardView, feats: Array<FloatArray>, team: List<Pair<Role, FloatArray>>, thr: Float): Array<Role>? {
        if (team.isEmpty()) return null
        val looks = speciesLook(view, feats)
        val maxSp = (view.species.maxOrNull() ?: -1)
        if (maxSp < 0) return null
        val roles = Array(maxSp + 1) { Role.NORMAL }
        for ((sp, look) in looks) {
            var best = Float.MAX_VALUE
            var role = Role.NORMAL
            for ((r, f) in team) {
                if (f.size != look.size) continue
                val dd = dist(look, f)
                if (dd < best) { best = dd; role = r }
            }
            if (best < thr) roles[sp] = role
        }
        return roles
    }

    // ------------------------------------------------------------------ ajuste automático da grade

    /**
     * Procura a posição/tamanho de grade em que as casas ficam mais "organizadas"
     * (menos tipos diferentes e peças iguais bem parecidas). Corrige calibração imprecisa.
     */
    fun fitGrid(bmp: Bitmap, g: BoardGeom, thr: Float): BoardGeom {
        val W = bmp.width
        val H = bmp.height
        val px = IntArray(W * H)
        bmp.getPixels(px, 0, W, 0, 0, W, H)

        fun score(l: Float, t: Float, sz: Float, stride: Int): Float {
            if (l < 0 || t < 0 || l + sz > W || t + sz > H) return Float.MAX_VALUE
            val f = sample(px, W, l, t, sz, g.n, stride)
            val lab = cluster(f, thr)
            val k = (lab.maxOrNull() ?: 0) + 1
            val dim = f[0].size
            val reps = Array(k) { FloatArray(dim) }
            val cnt = IntArray(k)
            for (i in f.indices) { cnt[lab[i]]++; for (j in 0 until dim) reps[lab[i]][j] += f[i][j] }
            for (c in 0 until k) { val m = cnt[c].toFloat(); for (j in 0 until dim) reps[c][j] = reps[c][j] / m }
            var intra = 0f
            for (i in f.indices) intra += dist(f[i], reps[lab[i]])
            return k + intra / f.size / 10f
        }

        val L = g.left.toFloat(); val T = g.top.toFloat(); val S = g.size.toFloat()
        var bestS = score(L, T, S, 3); var bL = L; var bT = T; var bSz = S
        var k = -0.15f
        while (k <= 0.1501f) {
            val sz = S * (1 + k); val cs = sz / g.n
            var ox = -0.5f
            while (ox <= 0.501f) {
                var oy = -0.5f
                while (oy <= 0.501f) {
                    val l = L + (S - sz) / 2 + ox * cs
                    val t = T + (S - sz) / 2 + oy * cs
                    val sc = score(l, t, sz, 3)
                    if (sc < bestS) { bestS = sc; bL = l; bT = t; bSz = sz }
                    oy += 0.125f
                }
                ox += 0.125f
            }
            k += 0.015f
        }
        // refino fino com todos os pixels
        val L0 = bL; val T0 = bT; val S0 = bSz
        bestS = score(L0, T0, S0, 1)
        for (kk in -4..4) {
            val sz = S0 * (1 + kk * 0.0025f)
            for (dx in -1..1) for (dy in -1..1) {
                val l = L0 + (S0 - sz) / 2 + dx * 0.06f * sz / g.n
                val t = T0 + (S0 - sz) / 2 + dy * 0.06f * sz / g.n
                val sc = score(l, t, sz, 1)
                if (sc < bestS) { bestS = sc; bL = l; bT = t; bSz = sz }
            }
        }
        return BoardGeom(bL.toInt(), bT.toInt(), bSz.toInt(), g.n)
    }

    /** Mesmo cálculo de features(), direto do array de pixels, pulando "stride" pixels (mais rápido). */
    private fun sample(px: IntArray, W: Int, l: Float, t: Float, sz: Float, n: Int, stride: Int): Array<FloatArray> {
        val cs = sz / n
        val w = (cs * (1 - 2 * inset)).toInt().coerceAtLeast(blocks)
        return Array(n * n) { i ->
            val r = i / n; val c = i % n
            val x0 = (l + c * cs + cs * inset).toInt()
            val y0 = (t + r * cs + cs * inset).toInt()
            val f = FloatArray(blocks * blocks * 3)
            val cnt = IntArray(blocks * blocks)
            var yy = 0
            while (yy < w) {
                val by = yy * blocks / w
                val row = (y0 + yy) * W + x0
                var xx = 0
                while (xx < w) {
                    val b = by * blocks + xx * blocks / w
                    val col = px[row + xx]
                    f[b * 3] += ((col shr 16) and 0xFF).toFloat()
                    f[b * 3 + 1] += ((col shr 8) and 0xFF).toFloat()
                    f[b * 3 + 2] += (col and 0xFF).toFloat()
                    cnt[b]++
                    xx += stride
                }
                yy += stride
            }
            for (b in 0 until blocks * blocks) {
                val m = cnt[b].coerceAtLeast(1).toFloat()
                f[b * 3] = f[b * 3] / m; f[b * 3 + 1] = f[b * 3 + 1] / m; f[b * 3 + 2] = f[b * 3 + 2] / m
            }
            f
        }
    }
}
