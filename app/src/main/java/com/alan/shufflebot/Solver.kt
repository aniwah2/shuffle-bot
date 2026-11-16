package com.alan.shufflebot

data class Move(val from: Int, val to: Int, val score: Int, val toEmpty: Boolean = false) {
    val key: Long get() = pairKey(from, to)
}

fun pairKey(a: Int, b: Int): Long = minOf(a, b).toLong() * 1000 + maxOf(a, b)

/** Papel de um Pokémon do time, marcado pelo jogador. */
enum class Role(val label: String, val weight: Float, val bigBonus: Float) {
    NORMAL("", 1.0f, 1.0f),
    MEGA("MEGA", 2.0f, 1.6f),     // encher a barra de Mega o quanto antes
    FORTE("FORTE", 1.5f, 2.0f),   // habilidade forte: vale muito fazer 4 ou 5
    APOIO("apoio", 0.6f, 0.6f);   // pouco útil: só se não houver coisa melhor

    fun next(): Role = values()[(ordinal + 1) % values().size]
}

/**
 * No Pokémon Shuffle dá para arrastar um Pokémon para QUALQUER casa (troca de lugar com o que estiver lá).
 * Para cada troca possível, simulamos a combinação, as peças caindo e os combos em cadeia,
 * e damos uma nota levando em conta o papel de cada Pokémon no time.
 */
object Solver {

    private const val MAX_WAVES = 8

    /**
     * @param lab     espécie de cada casa (>= 0); negativos nunca combinam (bloco, vazio)
     * @param blocked casas que não podem ser arrastadas
     * @param frozen  casas congeladas (combinam, mas não se movem nem caem)
     * @param roles   papel de cada espécie (índice = espécie); null = todos normais
     */
    fun best(
        lab: IntArray, n: Int, failed: Set<Long>, blocked: BooleanArray,
        frozen: BooleanArray? = null, roles: Array<Role>? = null, empty: BooleanArray? = null,
    ): Move? {
        val total = n * n
        val fz = frozen ?: BooleanArray(total)
        var best: Move? = null
        val b = IntArray(total)
        val f = BooleanArray(total)
        for (i in 0 until total) {
            if (blocked[i]) continue
            for (j in 0 until total) {
                if (j == i) continue
                val toEmpty = empty != null && empty[j]
                if (!toEmpty) {
                    if (j < i || blocked[j] || lab[i] == lab[j]) continue
                }
                if (failed.contains(pairKey(i, j))) continue
                lab.copyInto(b); fz.copyInto(f)
                if (toEmpty) { b[j] = lab[i]; b[i] = -5000 - i } else { b[i] = lab[j]; b[j] = lab[i] }
                val firstWave = BooleanArray(total)
                var s = simulate(b, f, n, roles, firstWave)
                if (s <= 0) continue
                if (toEmpty) s -= 5        // não é certeza que o jogo aceita; prefere trocas normais
                if (best == null || s > best.score) {
                    best = if (toEmpty) Move(i, j, s, true) else if (firstWave[j]) Move(i, j, s) else Move(j, i, s)
                }
            }
        }
        return best
    }

    /** Simula combinações + quedas + combos. Retorna a nota (0 = não combina nada). */
    private fun simulate(b: IntArray, f: BooleanArray, n: Int, roles: Array<Role>?, firstWave: BooleanArray): Int {
        var score = 0f
        var unknown = -1000
        val matched = BooleanArray(n * n)
        for (wave in 0 until MAX_WAVES) {
            matched.fill(false)
            var any = false
            var waveScore = 0f

            // linhas e colunas com 3+ iguais
            for (dir in 0..1) {
                for (line in 0 until n) {
                    var k = 0
                    while (k < n) {
                        val p0 = if (dir == 0) line * n + k else k * n + line
                        val sp = b[p0]
                        var len = 1
                        while (k + len < n) {
                            val p = if (dir == 0) line * n + k + len else (k + len) * n + line
                            if (b[p] != sp) break
                            len++
                        }
                        if (sp >= 0 && len >= 3) {
                            any = true
                            val role = roleOf(roles, sp)
                            for (t in 0 until len) matched[if (dir == 0) line * n + k + t else (k + t) * n + line] = true
                            if (len == 4) waveScore += 15f * role.bigBonus
                            if (len >= 5) waveScore += 40f * role.bigBonus
                        }
                        k += len
                    }
                }
            }
            if (!any) break

            for (p in matched.indices) if (matched[p]) {
                val sp = b[p]
                waveScore += 10f * roleOf(roles, sp).weight
                if (f[p]) waveScore += 8f                     // quebra barreira
                if (wave == 0) firstWave[p] = true
                // peças "lisas" (bloco/pedra) ao lado da combinação costumam quebrar
                val r = p / n; val c = p % n
                if (r > 0 && b[p - n] < 0 && b[p - n] > -1000) waveScore += 3f
                if (r < n - 1 && b[p + n] < 0 && b[p + n] > -1000) waveScore += 3f
                if (c > 0 && b[p - 1] < 0 && b[p - 1] > -1000) waveScore += 3f
                if (c < n - 1 && b[p + 1] < 0 && b[p + 1] > -1000) waveScore += 3f
            }
            // combos depois da primeira onda: valem um pouco menos (incerteza), mas cada combo ganha bônus
            score += if (wave == 0) waveScore else waveScore * 0.8f + 12f

            // limpa e aplica gravidade: congelados ficam parados, o resto cai; o topo vira "desconhecido"
            for (p in matched.indices) if (matched[p]) { b[p] = Int.MIN_VALUE; f[p] = false }
            for (c in 0 until n) {
                var write = n - 1
                for (r in n - 1 downTo 0) {
                    val p = r * n + c
                    if (f[p]) continue
                    if (b[p] == Int.MIN_VALUE) continue
                    while (write >= 0 && f[write * n + c]) write--
                    if (write < 0) break
                    if (write != r) b[write * n + c] = b[p]
                    write--
                }
                while (write >= 0) {
                    val p = write * n + c
                    if (!f[p]) b[p] = unknown--
                    write--
                }
            }
        }
        return score.toInt()
    }

    private fun roleOf(roles: Array<Role>?, sp: Int): Role =
        if (roles == null || sp < 0 || sp >= roles.size) Role.NORMAL else roles[sp]
}
