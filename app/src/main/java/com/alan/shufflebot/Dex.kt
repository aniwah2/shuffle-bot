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
    val removes: String? = null,   // o que a habilidade remove: rocha, bloco, nuvem, gelo, estranho
    val removeCount: Int = 0,
    val megaRemoves: Boolean = false, // efeito Mega apaga rochas/blocos/etc.
    val megaIconsMin: Int = 0,
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
    private val disr = ArrayList<Pair<String, FloatArray>>()

    /** Qual disrupção é essa casa (nuvem, rocha, bloco, metal)? null se não for nenhuma conhecida. */
    /** Distância média até cada protótipo de disrupção (para testes/diagnóstico). */
    fun disruptionDists(f: FloatArray): Map<String, Float> = disr.filter { it.second.size == f.size }
        .associate { (k, v) -> k to (v.indices.sumOf { abs(v[it] - f[it]).toDouble() } / v.size).toFloat() }

    /** Nuvem negra pela cor: casa inteira azul-marinho escura e lisa (não depende do protótipo nem do celular). */
    fun looksLikeCloud(f: FloatArray): Boolean {
        val nb = f.size / 3
        if (nb == 0) return false
        var r = 0f; var g = 0f; var b = 0f
        for (k in 0 until nb) { r += f[k * 3]; g += f[k * 3 + 1]; b += f[k * 3 + 2] }
        r /= nb; g /= nb; b /= nb
        val lum = (r + g + b) / 3f
        var v = 0f
        for (k in 0 until nb) { val l = (f[k * 3] + f[k * 3 + 1] + f[k * 3 + 2]) / 3f - lum; v += l * l }
        val std = kotlin.math.sqrt(v / nb)
        return lum < 75f && std < 20f && b - r > 12f && b > g
    }

    fun disruptionKind(f: FloatArray): String? {
        if (looksLikeCloud(f)) return "nuvem"
        // a mais parecida; nuvem negra varia mais (fundo, brilho, compressão da captura): aceita até 25
        var bk: String? = null; var bd = Float.MAX_VALUE
        for ((k, v) in disr) {
            if (v.size != f.size) continue
            var s = 0f
            for (j in v.indices) s += abs(v[j] - f[j])
            val d = s / v.size
            if (d < bd) { bd = d; bk = k }
        }
        return if (bk != null && (bd < 12f || (bk == "nuvem" && bd < 25f))) bk else null
    }

    fun load(ctx: Context) {
        if (ready) return
        val txt = ctx.assets.open("dex.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val spr = ctx.assets.open("sprites.bin").use { it.readBytes() }
        loadFrom(txt, spr)
    }

    fun loadFrom(jsonText: String, spriteBytes: ByteArray) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val json = JSONObject(jsonText)
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
                    if (o.has("rt") && !o.isNull("rt")) o.getString("rt") else null, o.optInt("rn", 0),
                    o.optInt("mr", 0) == 1, o.optInt("mim", 0),
                )
                list.add(e)
                byName.putIfAbsent(norm(e.name), e)
            }
            entries = list
            feats = spriteBytes
            if (json.has("disr")) {
                val da = json.getJSONArray("disr")
                for (i in 0 until da.length()) {
                    val o = da.getJSONObject(i); val v = o.getJSONArray("v")
                    disr.add(Pair(o.getString("k"), FloatArray(v.length()) { v.getDouble(it).toFloat() }))
                }
            }
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
            entries: Array<DexEntry?>, opponent: DexEntry?, disruptions: Map<String, Int>, megaEvolved: Boolean,
        ): SpeciesValue {
            val v = SpeciesValue(entries.size)
            val breakable = (disruptions["rocha"] ?: 0) + (disruptions["bloco"] ?: 0)
            for (sp in entries.indices) {
                val e = entries[sp] ?: continue
                val tm = if (opponent != null) Dex.typeMult(e.type, opponent.type) else 1f
                var w = (e.ap / 115f) * tm
                val mega = Dex.megaOf(e)
                if (e.isMega) {
                    w *= 1.3f
                    // Mega que apaga rochas/blocos (ex.: Mega Aerodactyl): cada combinação dele limpa o caminho
                    if (e.megaRemoves && breakable > 0) for (k in 0..2) v.flat[sp][k] += 12f * minOf(breakable, 10) / 2f
                } else if (!megaEvolved && mega != null) {
                    // encher a barra do Mega; prioridade maior se o efeito Mega resolve as disrupções da fase
                    w *= if (mega.megaRemoves && breakable > 0) 2.2f else 1.6f
                }
                v.weight[sp] = w
                val r = e.rates
                if (e.func.contains("Dano")) for (k in 0..2) v.dmgFactor[sp][k] = 1f + r[k] * (e.mult - 1f).coerceAtLeast(0f)
                // habilidade de remoção vale pelo que tem para remover AGORA (ex.: Cloud Clear com nuvens na tela)
                val target = e.removes
                if (target != null) {
                    val present = disruptions[target] ?: 0
                    if (present > 0) for (k in 0..2) v.flat[sp][k] += r[k] * 12f * minOf(present, maxOf(1, e.removeCount))
                }
                if (e.func.startsWith("Atrasar")) for (k in 0..2) v.flat[sp][k] += r[k] * 6f
                if (e.func.startsWith("Mega Boost") && !megaEvolved) for (k in 0..2) v.flat[sp][k] += r[k] * 12f
                // habilidades que só ativam com 4/5 (Power of 4, Super Voice…): valoriza combinações maiores
                v.bigBonus[sp] = 1f + (r[1] + r[2]) * 0.5f * (e.mult - 1f).coerceAtLeast(0f)
            }
            return v
        }
    }
}
