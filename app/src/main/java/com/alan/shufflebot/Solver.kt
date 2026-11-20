package com.alan.shufflebot

data class Move(
    val from: Int, val to: Int, val score: Int, val toEmpty: Boolean = false,
    val explore: Boolean = false,              // tentativa numa casa coberta por nuvem
    val cells: IntArray = IntArray(0),         // casas que a combinação deve apagar (1ª onda)
) {
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
        value: SpeciesValue? = null,
        dkind: IntArray? = null,          // 1 rocha, 2 bloco, 3 nuvem, 4 metal (por casa)
        explore: BooleanArray? = null,    // casas com nuvem onde dá para tentar soltar um Pokémon
        guessed: BooleanArray? = null,    // casas com nuvem cuja espécie em lab[] é palpite (visto antes da nuvem)
    ): Move? = candidates(lab, n, failed, blocked, frozen, roles, empty, value, dkind, explore, guessed, 1).firstOrNull()?.move

    class Cand(val move: Move, val board: IntArray, val frozen: BooleanArray)

    /**
     * Todas as jogadas que combinam algo, da melhor para a pior (até [keep]), com o tabuleiro que fica
     * depois da cascata (casas que caíram do topo ficam "desconhecidas", negativas).
     */
    fun candidates(
        lab: IntArray, n: Int, failed: Set<Long>, blocked: BooleanArray,
        frozen: BooleanArray?, roles: Array<Role>?, empty: BooleanArray?, value: SpeciesValue?,
        dkind: IntArray?, explore: BooleanArray?, guessed: BooleanArray?, keep: Int,
    ): List<Cand> {
        val total = n * n
        val fz = frozen ?: BooleanArray(total)
        val out = ArrayList<Cand>()
        var worst = Int.MIN_VALUE
        val b = IntArray(total)
        val f = BooleanArray(total)
        for (i in 0 until total) {
            if (blocked[i]) continue
            for (j in 0 until total) {
                if (j == i) continue
                val toCloud = explore != null && explore[j]
                val toEmpty = (empty != null && empty[j]) || toCloud
                if (!toEmpty) {
                    if (j < i || blocked[j] || lab[i] == lab[j]) continue
                }
                if (failed.contains(pairKey(i, j))) continue
                lab.copyInto(b); fz.copyInto(f)
                // soltar numa nuvem: o que está embaixo vem para a casa de origem (palpite, se houver)
                if (toEmpty) { b[j] = lab[i]; b[i] = if (toCloud && lab[j] >= 0) lab[j] else -5000 - i } else { b[i] = lab[j]; b[j] = lab[i] }
                val firstWave = BooleanArray(total)
                var s = simulate(b, f, n, roles, firstWave, value, dkind)
                if (s <= 0) continue
                // palpite do que está embaixo de uma nuvem: pode estar errado, então vale um pouco menos
                val usesGuess = guessed != null && (guessed[i] || (!toEmpty && guessed[j]))
                if (usesGuess) s -= 6
                else if (toCloud) s -= 2  // soltar numa nuvem formando trinca com peças visíveis: quase certo
                else if (toEmpty) s -= 5  // não é certeza que o jogo aceita; prefere trocas normais
                if (out.size >= keep && s <= worst) continue
                val cells = (0 until total).filter { firstWave[it] }.toIntArray()
                val mv = if (toEmpty) Move(i, j, s, true, toCloud, cells)
                    else if (usesGuess) (if (firstWave[j]) Move(i, j, s, false, true, cells) else Move(j, i, s, false, true, cells))
                    else if (firstWave[j]) Move(i, j, s, cells = cells) else Move(j, i, s, cells = cells)
                out.add(Cand(mv, b.copyOf(), f.copyOf()))
                if (out.size > keep * 2) { out.sortByDescending { it.move.score }; while (out.size > keep) out.removeAt(out.size - 1); worst = out.last().move.score }
            }
        }
        out.sortByDescending { it.move.score }
        while (out.size > keep) out.removeAt(out.size - 1)
        return out
    }

    /**
     * Modo "jogadas limitadas": olha duas jogadas à frente. Para as melhores jogadas de agora, simula o
     * tabuleiro que sobra e vê a melhor jogada seguinte. Nota = agora + metade da próxima.
     * Jogadas incertas (nuvem/palpite) não entram no planejamento — só se não houver jogada certa.
     */
    fun bestPlanned(
        lab: IntArray, n: Int, failed: Set<Long>, blocked: BooleanArray,
        frozen: BooleanArray?, roles: Array<Role>?, empty: BooleanArray?, value: SpeciesValue?,
        dkind: IntArray?, explore: BooleanArray?, guessed: BooleanArray?, width: Int = 10,
    ): Move? {
        val total = n * n
        val sure = candidates(lab, n, failed, blocked, frozen, roles, empty, value, dkind, null, null, width)
        if (sure.isEmpty()) return best(lab, n, failed, blocked, frozen, roles, empty, value, dkind, explore, guessed)
        var bestMv: Move? = null; var bestS = Int.MIN_VALUE
        for (c in sure) {
            val b2 = c.board
            val bl2 = BooleanArray(total) { b2[it] < 0 || (blocked[it] && b2[it] == lab[it] && !c.frozen[it]) }
            val s2 = candidates(b2, n, emptySet(), bl2, c.frozen, roles, null, value, dkind, null, null, 1).firstOrNull()?.move?.score ?: 0
            val tot = c.move.score + s2 / 2
            if (tot > bestS) { bestS = tot; bestMv = c.move.copy(score = tot) }
        }
        return bestMv
    }

    /** Simula combinações + quedas + combos. Retorna a nota (0 = não combina nada). */
    private fun simulate(
        b: IntArray, f: BooleanArray, n: Int, roles: Array<Role>?, firstWave: BooleanArray, value: SpeciesValue?,
        dkind: IntArray?,
    ): Int {
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
                            val useDex = role == Role.NORMAL && value != null && sp < value.weight.size
                            val bb = if (useDex) value!!.bigBonus[sp] else role.bigBonus
                            if (len == 4) waveScore += 15f * bb
                            if (len >= 5) waveScore += 40f * bb
                            if (useDex) {
                                val ki = minOf(len, 5) - 3
                                waveScore += (value!!.dmgFactor[sp][ki] - 1f) * 10f * len * value.weight[sp] + value.flat[sp][ki]
                            }
                        }
                        k += len
                    }
                }
            }
            if (!any) break

            for (p in matched.indices) if (matched[p]) {
                val sp = b[p]
                val rl = roleOf(roles, sp)
                waveScore += 10f * (if (rl == Role.NORMAL && value != null && sp >= 0 && sp < value.weight.size) value.weight[sp] else rl.weight)
                if (f[p]) waveScore += if (p / n == 0) 18f else 12f   // quebra gelo (no topo libera a coluna)
                if (wave == 0) firstWave[p] = true
                // disrupção ao lado da combinação: rocha quebra, nuvem costuma sumir; bloco/metal não
                if (dkind != null && wave == 0) {
                    val r = p / n; val c = p % n
                    for (q in intArrayOf(if (r > 0) p - n else -1, if (r < n - 1) p + n else -1, if (c > 0) p - 1 else -1, if (c < n - 1) p + 1 else -1)) {
                        if (q < 0) continue
                        when (dkind[q]) { 1 -> waveScore += 8f; 3 -> waveScore += 4f }
                    }
                }
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
