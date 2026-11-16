package com.alan.shufflebot

/**
 * Aprende quais Pokémon são DESTA batalha (para o bot só jogar no tabuleiro certo).
 * Separado do serviço para poder ser testado com quadros de vídeo.
 */
class BattleLearner(private val reader: BoardReader, val knownThr: Float = 20f) {
    val known = ArrayList<FloatArray>()
    private var candidate: List<FloatArray>? = null
    private var streak = 0
    /** Último motivo de não ter aprendido (para diagnóstico). */
    var lastReason = ""
    /** Aparências que NÃO são Pokémon (caixote, bloco de metal, pedra...) vistas no começo da batalha. */
    val disrupt = ArrayList<FloatArray>()
    private var prevNames: Set<String>? = null
    private var dexStreak = 0

    fun isDisrupt(f: FloatArray) = !isKnown(f) && disrupt.any { reader.dist(it, f) < knownThr }

    /**
     * Com a Shuffle Dex: é batalha quando há pelo menos 2 espécies de Pokémon DE VERDADE (reconhecidas pela Dex)
     * com 3+ casas cada, iguais em 2 prints seguidos. Funciona mesmo com o tabuleiro cheio de disrupção.
     * Em menus e telas de captura cada Pokémon aparece no máximo em 1 casa, então não passa.
     */
    fun tryLearnDex(view: BoardView, feats: Array<FloatArray>): List<FloatArray> {
        val cnt = HashMap<Int, Int>()
        for (i in view.species.indices) if ((view.movable[i] || view.frozen[i]) && view.species[i] >= 0)
            cnt[view.species[i]] = (cnt[view.species[i]] ?: 0) + 1
        val looks = reader.speciesLook(view, feats)
        val poke = ArrayList<FloatArray>(); val names = HashSet<String>(); val other = ArrayList<FloatArray>()
        var strong = 0
        for ((sp, look) in looks) {
            val c = cnt[sp] ?: 0
            if (c < 2) continue
            val e = Dex.identify(look)
            if (e != null) { poke.add(look); names.add(e.baseName); if (c >= 3) strong++ }
            else if (c >= 3) other.add(look)
        }
        if (strong < 2 || view.empty > 6) {
            lastReason = "Pokémon reconhecidos: $strong de 2"
            prevNames = null; dexStreak = 0; return emptyList()
        }
        dexStreak = if (prevNames == names) dexStreak + 1 else 0
        prevNames = names
        lastReason = "vi ${names.joinToString()} (confirmando)"
        if (dexStreak < 1) return emptyList()
        known.addAll(poke); disrupt.addAll(other)
        return poke
    }

    fun isKnown(f: FloatArray) = known.any { reader.dist(it, f) < knownThr }

    /** Aparência média das espécies (livres) que têm pelo menos [minCells] casas (livres + congeladas). */
    fun freeLooks(view: BoardView, feats: Array<FloatArray>, minCells: Int): List<FloatArray> {
        val cnt = HashMap<Int, Int>()
        for (i in view.species.indices) if ((view.movable[i] || view.frozen[i]) && view.species[i] >= 0)
            cnt[view.species[i]] = (cnt[view.species[i]] ?: 0) + 1
        val looks = reader.speciesLook(view, feats)
        return cnt.filter { it.value >= minCells }.keys.mapNotNull { looks[it] }
    }

    /** Tenta aprender com este quadro. Retorna as aparências aprendidas agora (vazio se ainda não). */
    fun tryLearn(view: BoardView, feats: Array<FloatArray>): List<FloatArray> {
        val problem = reader.boardProblem(view)
        val looks = when {
            problem != null -> { lastReason = "problema: $problem"; emptyList() }
            view.empty > 2 -> { lastReason = "vazias: ${view.empty}"; emptyList() }
            else -> freeLooks(view, feats, 4).also { if (it.size < 3) lastReason = "só ${it.size} espécies com 4+ casas" }
        }
        if (looks.size < 3) { candidate = null; streak = 0; return emptyList() }
        val prevC = candidate
        val same = prevC != null && prevC.size == looks.size && looks.all { l -> prevC.any { reader.dist(it, l) < knownThr } }
        streak = if (same) streak + 1 else 0
        candidate = looks
        lastReason = "estável há $streak quadro(s)"
        if (streak >= 2) { known.addAll(looks); return looks }
        return emptyList()
    }
}
