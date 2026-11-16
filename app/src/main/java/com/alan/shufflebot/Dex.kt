package com.alan.shufflebot

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs

/** Um Pokémon da Shuffle Dex (dados da planilha do Alan). */
class DexEntry(
    val name: String,
    val type: String,          // em português, ex.: "Gelo"
    val ap: Int,               // ataque máximo
    val skill: String,
    val func: String,          // função da habilidade (ex.: "Remover disrupções")
    val rates: FloatArray,     // chance de ativar com 3 / 4 / 5
    val mult: Float,           // multiplicador de dano da habilidade
    val megaIcons: Int,        // ícones para Mega evoluir (0 = não é Mega)
) {
    val isMega: Boolean get() = name.startsWith("Mega ") || name.startsWith("Primal ")
    val baseName: String get() = name.substringBefore(" (").removePrefix("Mega ").removePrefix("Primal ").trim()
}

/**
 * Base de dados + reconhecimento de Pokémon pelo ícone.
 * sprites.bin: para cada Pokémon, a mesma "assinatura de cores" 8x8 que o bot calcula de cada casa.
 */
object Dex {
    @Volatile private var ready = false
    private lateinit var entries: List<DexEntry>
    private lateinit var feats: ByteArray
    private var dim = 192
    private val superEff = HashMap<String, Set<String>>()
    private val notVery = HashMap<String, Set<String>>()
    private val byName = HashMap<String, DexEntry>()

    fun load(ctx: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val json = JSONObject(ctx.assets.open("dex.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            dim = json.getInt("dim")
            val arr = json.getJSONArray("e")
            val list = ArrayList<DexEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val r = o.getJSONArray("r")
                val e = DexEntry(
                    o.getString("n"), o.getString("t"), o.optInt("ap", 0), o.optString("s"), o.optString("f"),
                    floatArrayOf(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat()),
                    o.optDouble("m", 1.0).toFloat(), o.optInt("mi", 0),
                )
                list.add(e)
                byName.putIfAbsent(norm(e.name), e)
            }
            entries = list
            feats = ctx.assets.open("sprites.bin").use { it.readBytes() }
            val types = json.getJSONObject("types")
            for (t in types.keys()) {
                val o = types.getJSONObject(t)
                superEff[t] = (0 until o.getJSONArray("se").length()).map { o.getJSONArray("se").getString(it) }.toSet()
                notVery[t] = (0 until o.getJSONArray("nve").length()).map { o.getJSONArray("nve").getString(it) }.toSet()
            }
            ready = true
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Qual Pokémon tem essa aparência? null se não tiver certeza. */
    fun identify(look: FloatArray): DexEntry? {
        if (!ready || look.size != dim) return null
        var b1 = Float.MAX_VALUE; var b2 = Float.MAX_VALUE; var i1 = -1; var i2 = -1
        for (k in entries.indices) {
            var s = 0f
            val off = k * dim
            for (j in 0 until dim) s += abs(look[j] - (feats[off + j].toInt() and 0xFF))
            val d = s / dim
            if (d < b1) { b2 = b1; i2 = i1; b1 = d; i1 = k } else if (d < b2) { b2 = d; i2 = k }
        }
        if (i1 < 0 || b1 > 30f) return null
        val e1 = entries[i1]
        // variantes quase iguais (ex.: Swirlix e Swirlix (Winking)) contam como o mesmo bicho
        if (b2 - b1 > 6f || (i2 >= 0 && entries[i2].baseName == e1.baseName)) return e1
        return null
    }

    /** Procura um nome lido na tela (OCR) na base. */
    fun findByText(text: String): DexEntry? {
        if (!ready) return null
        val n = norm(text)
        if (n.length < 3) return null
        return byName[n]
    }

    /** Existe forma Mega desse Pokémon? (para saber quem é o Mega do time) */
    fun megaOf(e: DexEntry): DexEntry? = if (!ready || e.isMega) null else byName[norm("Mega " + e.name)]

    /** Multiplicador de tipo: ataque do tipo [att] contra inimigo do tipo [def]. */
    fun typeMult(att: String, def: String): Float = when {
        superEff[att]?.contains(def) == true -> 2f
        notVery[att]?.contains(def) == true -> 0.5f
        else -> 1f
    }
}

/**
 * Quanto vale combinar cada espécie do tabuleiro, calculado pela Shuffle Dex:
 * ataque × efetividade de tipo, chance/força da habilidade por tamanho da combinação,
 * habilidades de remover disrupção quando há disrupções, prioridade ao Mega até evoluir.
 */
class SpeciesValue(n: Int) {
    val weight = FloatArray(n) { 1f }                 // multiplica os pontos de cada peça
    val dmgFactor = Array(n) { floatArrayOf(1f, 1f, 1f) }   // multiplicador esperado da habilidade (3/4/5)
    val flat = Array(n) { floatArrayOf(0f, 0f, 0f) }        // bônus fixo (habilidades de suporte)
    val bigBonus = FloatArray(n) { 1f }

    companion object {
        fun from(
            entries: Array<DexEntry?>, opponent: DexEntry?, disruptions: Boolean, megaEvolved: Boolean,
        ): SpeciesValue {
            val v = SpeciesValue(entries.size)
            for (sp in entries.indices) {
                val e = entries[sp] ?: continue
                val tm = if (opponent != null) Dex.typeMult(e.type, opponent.type) else 1f
                var w = (e.ap / 115f) * tm
                if (e.isMega) w *= 1.3f                                   // efeito Mega ao combinar
                else if (!megaEvolved && Dex.megaOf(e) != null) w *= 1.6f // encher a barra do Mega
                v.weight[sp] = w
                val r = e.rates
                when {
                    e.func.startsWith("Dano") -> for (k in 0..2) v.dmgFactor[sp][k] = 1f + r[k] * (e.mult - 1f).coerceAtLeast(0f)
                    e.func.startsWith("Remover disrupções") -> if (disruptions) for (k in 0..2) v.flat[sp][k] = r[k] * 20f
                    e.func.startsWith("Atrasar") -> for (k in 0..2) v.flat[sp][k] = r[k] * 6f
                    e.func.startsWith("Mega Boost") -> if (!megaEvolved) for (k in 0..2) v.flat[sp][k] = r[k] * 12f
                }
                // habilidades que só ativam com 4/5 (Power of 4 etc.): valoriza combinações maiores
                v.bigBonus[sp] = 1f + (r[1] + r[2]) * 0.5f * (e.mult - 1f).coerceAtLeast(0f)
            }
            return v
        }
    }
}
