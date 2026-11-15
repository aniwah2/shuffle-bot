package com.alan.shufflebot

data class Move(val from: Int, val to: Int, val score: Int) {
    val key: Long get() = pairKey(from, to)
}

fun pairKey(a: Int, b: Int): Long = minOf(a, b).toLong() * 1000 + maxOf(a, b)

/**
 * No Pokémon Shuffle dá para arrastar um Pokémon para QUALQUER casa (ele troca de lugar
 * com o que estiver lá). Então testamos todas as trocas possíveis e escolhemos a melhor.
 */
object Solver {

    fun best(lab: IntArray, n: Int, failed: Set<Long>, blocked: BooleanArray): Move? {
        val b = lab.copyOf()
        var best: Move? = null
        val total = n * n
        for (i in 0 until total) {
            if (blocked[i]) continue
            for (j in i + 1 until total) {
                if (blocked[j] || lab[i] == lab[j]) continue
                if (failed.contains(pairKey(i, j))) continue

                b[i] = lab[j]; b[j] = lab[i]
                val atJ = matchedAt(b, n, j)   // peça que veio de i, agora em j
                val atI = matchedAt(b, n, i)   // peça que veio de j, agora em i
                b[i] = lab[i]; b[j] = lab[j]

                if (atJ.isEmpty() && atI.isEmpty()) continue
                val cells = HashSet<Int>().apply { addAll(atJ); addAll(atI) }
                val s = score(cells, atJ.size, atI.size, n)
                if (best == null || s > best.score) {
                    // arrasta a peça que forma a combinação no destino
                    best = if (atJ.size >= atI.size) Move(i, j, s) else Move(j, i, s)
                }
            }
        }
        return best
    }

    /** Casas que somem se a peça em p formar linha/coluna de 3+. */
    private fun matchedAt(b: IntArray, n: Int, p: Int): List<Int> {
        val r = p / n
        val c = p % n
        val t = b[p]
        val out = ArrayList<Int>()

        var c0 = c; while (c0 > 0 && b[r * n + c0 - 1] == t) c0--
        var c1 = c; while (c1 < n - 1 && b[r * n + c1 + 1] == t) c1++
        if (c1 - c0 + 1 >= 3) for (x in c0..c1) out.add(r * n + x)

        var r0 = r; while (r0 > 0 && b[(r0 - 1) * n + c] == t) r0--
        var r1 = r; while (r1 < n - 1 && b[(r1 + 1) * n + c] == t) r1++
        if (r1 - r0 + 1 >= 3) for (y in r0..r1) if (y != r) out.add(y * n + c)

        if (out.isNotEmpty() && !out.contains(p)) out.add(p)
        return out
    }

    private fun score(cells: Set<Int>, a: Int, b: Int, n: Int): Int {
        var s = cells.size * 10
        for (k in intArrayOf(a, b)) {
            if (k >= 5) s += 40        // combinações de 5 dão dano/efeito maior
            else if (k == 4) s += 15
        }
        if (a >= 3 && b >= 3) s += 20  // duas combinações de uma vez
        // combinações mais embaixo fazem mais peças caírem => mais chance de combo
        val lowest = cells.maxOf { it / n }
        s += lowest * 2
        return s
    }
}
