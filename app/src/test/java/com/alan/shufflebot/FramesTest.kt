package com.alan.shufflebot

import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Roda a leitura do tabuleiro (o mesmo código do app) em quadros de vídeos reais
 * e imprime o que o bot "entende" de cada um. Saída aparece no log do GitHub Actions.
 */
class FramesTest {
    /** Leitor mínimo de PNG (RGB/RGBA 8 bits, sem entrelaçamento). */
    private fun load(name: String): ArrayImg {
        val bytes = File(javaClass.classLoader!!.getResource("frames/$name")!!.toURI()).readBytes()
        var p = 8
        var w = 0; var h = 0; var ct = 0
        val idat = ByteArrayOutputStream()
        fun int(o: Int) = ((bytes[o].toInt() and 255) shl 24) or ((bytes[o + 1].toInt() and 255) shl 16) or
            ((bytes[o + 2].toInt() and 255) shl 8) or (bytes[o + 3].toInt() and 255)
        while (p < bytes.size) {
            val len = int(p); val type = String(bytes, p + 4, 4)
            if (type == "IHDR") { w = int(p + 8); h = int(p + 12); ct = bytes[p + 17].toInt() }
            if (type == "IDAT") idat.write(bytes, p + 8, len)
            if (type == "IEND") break
            p += 12 + len
        }
        val bpp = if (ct == 6) 4 else 3
        val inf = Inflater(); inf.setInput(idat.toByteArray())
        val stride = w * bpp
        val raw = ByteArray((stride + 1) * h)
        var off = 0
        while (off < raw.size) { val n = inf.inflate(raw, off, raw.size - off); if (n == 0 && (inf.finished() || inf.needsInput())) break; off += n }
        val cur = IntArray(stride); val prev = IntArray(stride)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val f = raw[y * (stride + 1)].toInt()
            val base = y * (stride + 1) + 1
            for (x in 0 until stride) {
                val v = raw[base + x].toInt() and 255
                val a = if (x >= bpp) cur[x - bpp] else 0
                val b = prev[x]
                val c = if (x >= bpp) prev[x - bpp] else 0
                cur[x] = when (f) {
                    1 -> v + a; 2 -> v + b; 3 -> v + (a + b) / 2
                    4 -> { val pp = a + b - c; val pa = Math.abs(pp - a); val pb = Math.abs(pp - b); val pc = Math.abs(pp - c)
                           v + (if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c) }
                    else -> v
                } and 255
            }
            for (x in 0 until w) px[y * w + x] = (0xFF shl 24) or (cur[x * bpp] shl 16) or (cur[x * bpp + 1] shl 8) or cur[x * bpp + 2]
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        return ArrayImg(w, h, px)
    }

    private fun describe(tag: String, img: ArrayImg, g: BoardGeom, learner: BattleLearner?) {
        val r = BoardReader()
        val feats = r.features(img, g)
        if (feats == null) { println("[$tag] grade fora da imagem"); return }
        val border = r.frozenByBorder(img, g)
        val v = r.analyze(feats, 15f, border)
        val sb = StringBuilder()
        for (row in 0 until 6) {
            sb.append("    ")
            for (c in 0 until 6) {
                val i = row * 6 + c
                val s = v.species[i]
                sb.append(when { v.isEmpty[i] -> "  _"; s < 0 -> "  B"; v.frozen[i] -> String.format("%2d*", s); else -> String.format("%3d", s) })
            }
            sb.append('\n')
        }
        val learned = learner?.tryLearn(v, feats)
        println("[$tag] tipos=${v.types} blocos=${v.blocks} vazias=${v.empty} gelo=${v.frozen.count { it }} " +
            "problema=${r.boardProblem(v)} borda=${border.count { it }} " +
            (if (learner != null) "aprendeu=${learned!!.size} motivo='${learner.lastReason}'" else ""))
        print(sb)
    }

    @Test fun video5_tabuleiroParado() {
        val g = BoardGeom(14, 1208, 1052)
        val learner = BattleLearner(BoardReader())
        for (t in listOf("020", "021", "022", "040")) describe("v5_$t", load("v5_$t.png"), g, learner)
        println("v5 aprendidos: ${learner.known.size}")
    }

    private fun dexLearn(name: String, times: Int = 2): String {
        Dex.loadFrom(File("src/main/assets/dex.json").readText(), File("src/main/assets/sprites.bin").readBytes())
        val img = load(name)
        val r = BoardReader()
        val g = BoardGeom.guess(img.width, img.height)
        val l = BattleLearner(r)
        var learned = 0
        repeat(times) {
            val f = r.features(img, g) ?: return "fora"
            val v = r.analyze(f, 15f, r.frozenByBorder(img, g))
            learned += l.tryLearnDex(v, f).size
        }
        return if (learned > 0) "BATALHA (${l.known.size} Pokémon, ${l.disrupt.size} disrupções) — ${l.lastReason}" else "não — ${l.lastReason}"
    }

    @Test fun dex_reconheceBatalhaDificilENaoMenus() {
        println("== batalhas (devem ser reconhecidas)")
        for (n in listOf("v71_011", "v71_012", "v72_006", "v72_007", "v73_006", "v73_007", "v73_010", "v6_003", "v5_020", "ex4", "blocos", "v4_060", "v12_005", "v12_041", "v12_061", "v12_081", "v12_121"))
            println("[batalha] $n: ${dexLearn("$n.png")}")
        println("== fora de batalha (NUNCA podem ser reconhecidas)")
        for (n in listOf("v71_003", "v71_005", "v72_045", "v72_050", "v2_100", "v2_110", "v2_122", "v4_030"))
            println("[fora] $n: ${dexLearn("$n.png", 3)}")
    }

    @Test fun tempoPorQuadro() {
        Dex.loadFrom(File("src/main/assets/dex.json").readText(), File("src/main/assets/sprites.bin").readBytes())
        val r = BoardReader()
        val img = load("v10_s4.png"); val g = BoardGeom.guess(img.width, img.height)
        fun once(): Long {
            val t0 = System.nanoTime()
            val f = r.features(img, g)!!
            val v = r.analyze(f, 15f, r.frozenByBorder(img, g))
            val names = r.speciesLook(v, f).mapValues { Dex.identify(it.value) }
            val sp = IntArray(36) { v.species[it] }
            Solver.best(sp, 6, emptySet(), BooleanArray(36) { !v.movable[it] }, v.frozen)
            return (System.nanoTime() - t0) / 1_000_000
        }
        repeat(5) { once() }
        val ts = (1..20).map { once() }.sorted()
        println("[tempo] por quadro (computador do GitHub): mediana ${ts[10]} ms, pior ${ts.last()} ms")
    }

    @Test fun xerneas_semJogada() {
        Dex.loadFrom(File("src/main/assets/dex.json").readText(), File("src/main/assets/sprites.bin").readBytes())
        val r = BoardReader(); val l = BattleLearner(r)
        for (n in listOf("v10_a", "v10_b", "v10_c", "v10_a")) {
            val img = load("$n.png"); val g = BoardGeom.guess(img.width, img.height)
            val f = r.features(img, g)!!; val v0 = r.analyze(f, 15f, r.frozenByBorder(img, g))
            if (l.known.isEmpty()) { if (l.tryLearnDex(v0, f).isEmpty() && l.known.isEmpty()) l.tryLearn(v0, f) }
        }
        val team = l.known.mapNotNull { Dex.identify(it)?.baseName }.toHashSet()
        println("[xer] aprendeu ${l.known.size}: $team — ${l.lastReason}")
        for (n in listOf("v10_s1", "v10_s2", "v10_s3", "v10_s4")) {
            val img = load("$n.png"); val g = BoardGeom.guess(img.width, img.height)
            val f = r.features(img, g)!!; val v = r.analyze(f, 15f, r.frozenByBorder(img, g))
            val spName = HashMap<Int, String>()
            for ((sp, look) in r.speciesLook(v, f)) Dex.identify(look)?.let { spName[sp] = it.baseName }
            val knownFree = BooleanArray(36) { v.movable[it] && (l.isKnown(f[it]) || spName[v.species[it]]?.let { x -> x in team } == true) }
            val canon = HashMap<Int, Int>(); val first = HashMap<String, Int>()
            for ((sp, nm) in spName) canon[sp] = first.getOrPut(nm) { sp }
            val sb = StringBuilder()
            for (row in 0 until 6) { sb.append("      "); for (c in 0 until 6) { val i = row * 6 + c
                val s = v.species[i]; val cs = canon[s] ?: s
                sb.append(when { s < 0 -> "  B"; v.frozen[i] -> String.format("%2d*", cs); knownFree[i] -> String.format("%3d", cs); else -> String.format("%2d?", cs) }) }; sb.append('\n') }
            val spK = IntArray(36) { if (knownFree[it]) (canon[v.species[it]] ?: v.species[it]) else -(it + 1) }
            val mvK = Solver.best(spK, 6, emptySet(), BooleanArray(36) { !knownFree[it] })
            val spA = IntArray(36) { val s = v.species[it]; if (s >= 0) (canon[s] ?: s) else s }
            val mvA = Solver.best(spA, 6, emptySet(), BooleanArray(36) { !v.movable[it] }, v.frozen)
            println("[xer] $n: nomes=${spName.values.toSet()}  (? = Pokémon que o bot NÃO aceitou mexer)\n$sb" +
                "[xer]   jogada só com reconhecidos: $mvK | com todos os livres: $mvA")
        }
    }

    @Test fun roserade_semJogada() {
        val adopted = HashSet<String>()
        Dex.loadFrom(File("src/main/assets/dex.json").readText(), File("src/main/assets/sprites.bin").readBytes())
        val r = BoardReader(); val l = BattleLearner(r)
        for (n in listOf("v13_023", "v13_025", "v13_023")) {
            val img = load("$n.png"); val g = BoardGeom.guess(img.width, img.height)
            val f = r.features(img, g)!!; val v0 = r.analyze(f, 15f, r.frozenByBorder(img, g))
            if (l.known.isEmpty()) { if (l.tryLearnDex(v0, f).isEmpty() && l.known.isEmpty()) l.tryLearn(v0, f) }
        }
        val team = l.known.mapNotNull { Dex.identify(it)?.baseName }.toHashSet()
        // mesma regra do bot: nome da Dex com 2+ casas em 2 prints seguidos entra no time
        for (n in listOf("v13_041", "v13_041")) {
            val img = load("$n.png"); val g = BoardGeom.guess(img.width, img.height)
            val f = r.features(img, g)!!; val v = r.analyze(f, 15f, r.frozenByBorder(img, g))
            val cnt = HashMap<Int, Int>()
            for (i in 0 until 36) if ((v.movable[i] || v.frozen[i]) && v.species[i] >= 0) cnt[v.species[i]] = (cnt[v.species[i]] ?: 0) + 1
            for ((sp, look) in r.speciesLook(v, f)) { val e = Dex.identify(look) ?: continue
                if (e.baseName !in team && (cnt[sp] ?: 0) >= 2 && !l.isDisrupt(look)) { if (n == "v13_041" && adopted.add(e.baseName) ) Unit else team.add(e.baseName) } }
        }
        team.addAll(adopted)
        println("[rose] aprendeu ${l.known.size}: $team — ${l.lastReason}")
        for (n in listOf("v13_041", "v13_055", "v13_069", "v13_079")) {
            val img = load("$n.png"); val g = BoardGeom.guess(img.width, img.height)
            val f = r.features(img, g)!!; val v = r.analyze(f, 15f, r.frozenByBorder(img, g))
            val spName = HashMap<Int, String>()
            for ((sp, look) in r.speciesLook(v, f)) Dex.identify(look)?.let { spName[sp] = it.baseName }
            val knownFree = BooleanArray(36) { v.movable[it] && (l.isKnown(f[it]) || spName[v.species[it]]?.let { x -> x in team } == true) }
            val canon = HashMap<Int, Int>(); val first = HashMap<String, Int>()
            for ((sp, nm) in spName) canon[sp] = first.getOrPut(nm) { sp }
            val sb = StringBuilder()
            for (row in 0 until 6) { sb.append("      "); for (c in 0 until 6) { val i = row * 6 + c
                val s = v.species[i]; val cs = canon[s] ?: s
                sb.append(when { s < 0 -> "  B"; v.frozen[i] -> String.format("%2d*", cs); knownFree[i] -> String.format("%3d", cs); else -> String.format("%2d?", cs) }) }; sb.append('\n') }
            val spK = IntArray(36) { if (knownFree[it]) (canon[v.species[it]] ?: v.species[it]) else -(it + 1) }
            val mvK = Solver.best(spK, 6, emptySet(), BooleanArray(36) { !knownFree[it] })
            val spA = IntArray(36) { val s = v.species[it]; if (s >= 0) (canon[s] ?: s) else s }
            val mvA = Solver.best(spA, 6, emptySet(), BooleanArray(36) { !v.movable[it] }, v.frozen)
            println("[rose] $n: nomes=${spName.values.toSet()}  (? = Pokémon que o bot NÃO aceitou mexer)\n$sb" +
                "[rose]   jogada só com reconhecidos: $mvK | com todos os livres: $mvA")
        }
    }

    @Test fun nuvens_soMexeEmPokemonReconhecido() {
        Dex.loadFrom(File("src/main/assets/dex.json").readText(), File("src/main/assets/sprites.bin").readBytes())
        val r = BoardReader()
        val l = BattleLearner(r)
        for (n in listOf("v8_a", "v8_b", "v8_c", "v8_a", "v8_b")) {
            val img = load("$n.png"); val g = BoardGeom.guess(img.width, img.height)
            val f = r.features(img, g)!!; val v0 = r.analyze(f, 15f, r.frozenByBorder(img, g))
            if (l.known.isEmpty()) { if (l.tryLearnDex(v0, f).isEmpty() && l.known.isEmpty()) l.tryLearn(v0, f) }
            println("[nuvem] $n -> ${l.lastReason} (aprendidos ${l.known.size})")
            val cnt = HashMap<Int, Int>(); val mov = HashMap<Int, Int>()
            for (i in 0 until 36) if (v0.species[i] >= 0) { cnt[v0.species[i]] = (cnt[v0.species[i]] ?: 0) + 1; if (v0.movable[i]) mov[v0.species[i]] = (mov[v0.species[i]] ?: 0) + 1 }
            val looks = r.speciesLook(v0, f)
            println("[nuvem]    espécies: " + cnt.entries.sortedBy { -it.value }.joinToString { (sp, c) -> "$sp: $c casas (${mov[sp] ?: 0} livres) = ${looks[sp]?.let { Dex.identify(it)?.name } ?: "?"}" } +
                " | gelo=${v0.frozen.count { it }} blocos=${v0.blocks} vazias=${v0.empty}")
        }
        println("[nuvem] aprendeu ${l.known.size} Pokémon / ${l.disrupt.size} disrupções: ${l.lastReason}")
        val img = load("v8_nuvem.png"); val g = BoardGeom.guess(img.width, img.height)
        val f = r.features(img, g)!!; val v = r.analyze(f, 15f, r.frozenByBorder(img, g))
        val spName = HashMap<Int, String>()
        for ((sp, look) in r.speciesLook(v, f)) Dex.identify(look)?.let { spName[sp] = it.baseName }
        val teamNames = l.known.mapNotNull { Dex.identify(it)?.baseName }.toHashSet()
        println("[nuvem] time: $teamNames")
        val knownFree = BooleanArray(36) { v.movable[it] && (l.isKnown(f[it]) || spName[v.species[it]]?.let { n -> n in teamNames } == true) }
        val canon = HashMap<Int, Int>(); val first = HashMap<String, Int>()
        for ((sp, n) in spName) canon[sp] = first.getOrPut(n) { sp }
        val sb = StringBuilder()
        for (row in 0 until 6) { sb.append("    "); for (c in 0 until 6) { val i = row * 6 + c
            sb.append(if (knownFree[i]) String.format("%3d", canon[v.species[i]] ?: v.species[i]) else "  .") }; sb.append('\n') }
        print("[nuvem] casas que o bot pode mexer (. = nuvem/caixote/desconhecido):\n$sb")
        val sp = IntArray(36) { if (knownFree[it]) (canon[v.species[it]] ?: v.species[it]) else -(it + 1) }
        val mv = Solver.best(sp, 6, emptySet(), BooleanArray(36) { !knownFree[it] })
        println("[nuvem] jogada escolhida (só visíveis): $mv")
        val dk = IntArray(36) { if (knownFree[it]) 0 else when (Dex.disruptionKind(f[it])) { "rocha" -> 1; "bloco" -> 2; "nuvem" -> 3; "metal" -> 4; else -> 0 } }
        println("[nuvem] disrupções: " + (0 until 36).groupingBy { dk[it] }.eachCount().filterKeys { it > 0 }.mapKeys { listOf("", "rocha", "bloco", "nuvem", "metal")[it.key] })
        val mv2 = Solver.best(sp, 6, emptySet(), BooleanArray(36) { !knownFree[it] }, dkind = dk, explore = BooleanArray(36) { dk[it] == 3 })
        println("[nuvem] jogada com rochas/nuvens: $mv2 (de ${mv2?.from?.let { "(${it / 6},${it % 6})" }} para ${mv2?.to?.let { "(${it / 6},${it % 6})" }})")
    }

    @Test fun dex_caixoteNaoEPokemon() {
        val dexJson = File("src/main/assets/dex.json").readText()
        val spr = File("src/main/assets/sprites.bin").readBytes()
        Dex.loadFrom(dexJson, spr)
        val img = load("v6_003.png")
        val r = BoardReader()
        val g = BoardGeom.guess(img.width, img.height)
        val feats = r.features(img, g)!!
        val v = r.analyze(feats, 15f, r.frozenByBorder(img, g))
        val counts = v.species.filter { it >= 0 }.groupingBy { it }.eachCount()
        for ((sp, look) in r.speciesLook(v, feats)) println("[dex] espécie $sp (${counts[sp]} casas) -> ${Dex.identify(look)?.name ?: "NÃO É POKÉMON (${Dex.disruptionKind(look) ?: "?"})"}")
    }

    @Test fun semCalibracao_estimaEAjusta() {
        val r = BoardReader()
        for (name in listOf("v5_020.png", "v4_060.png", "ex4.png", "blocos.png")) {
            val img = load(name)
            val guess = BoardGeom.guess(img.width, img.height)
            val fit = r.fitGrid(img, guess, 15f, wide = true)
            fun tipos(g: BoardGeom) = r.features(img, g)?.let { val v = r.analyze(it, 15f, r.frozenByBorder(img, g)); "${v.types} tipos, problema=${r.boardProblem(v)}" } ?: "fora"
            println("[auto] $name ${img.width}x${img.height}: estimativa $guess -> ${tipos(guess)} | ajustada $fit -> ${tipos(fit)}")
        }
    }

    @Test fun ajusteLargo_corrigeGradeDeslocada() {
        val img = load("v5_020.png")
        val r = BoardReader()
        for ((dx, dy) in listOf(0 to 120, 0 to -150, 60 to 100, -80 to -80)) {
            val wrong = BoardGeom(14 + dx, 1208 + dy, 1052)
            val curto = r.fitGrid(img, wrong, 15f, wide = false)
            val largo = r.fitGrid(img, wrong, 15f, wide = true)
            fun tipos(g: BoardGeom) = r.features(img, g)?.let { r.analyze(it, 15f, r.frozenByBorder(img, g)).types } ?: -1
            println("[ajuste] desvio ($dx,$dy): curto -> $curto tipos=${tipos(curto)} | largo -> $largo tipos=${tipos(largo)}")
        }
    }

    @Test fun video4_e_prints() {
        val g = BoardGeom(20, 1364, 1240)
        val learner = BattleLearner(BoardReader())
        for (t in listOf("030", "060", "150")) describe("v4_$t", load("v4_$t.png"), g, learner)
        describe("ex4", load("ex4.png"), g, BattleLearner(BoardReader()))
        describe("blocos", load("blocos.png"), g, BattleLearner(BoardReader()))
    }
}
