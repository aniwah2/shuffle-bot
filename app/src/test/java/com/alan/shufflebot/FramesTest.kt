package com.alan.shufflebot

import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Roda a leitura do tabuleiro (o mesmo código do app) em quadros de vídeos reais
 * e imprime o que o bot "entende" de cada um. Saída aparece no log do GitHub Actions.
 */
class FramesTest {
    private fun load(name: String): ArrayImg {
        val f = File(javaClass.classLoader!!.getResource("frames/$name")!!.toURI())
        val im = ImageIO.read(f)
        val px = IntArray(im.width * im.height)
        im.getRGB(0, 0, im.width, im.height, px, 0, im.width)
        return ArrayImg(im.width, im.height, px)
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
        for (t in listOf("020", "021", "022", "023", "024", "040", "041", "042")) describe("v5_$t", load("v5_$t.jpg"), g, learner)
        println("v5 aprendidos: ${learner.known.size}")
    }

    @Test fun video4_e_prints() {
        val g = BoardGeom(20, 1364, 1240)
        val learner = BattleLearner(BoardReader())
        for (t in listOf("030", "060", "061", "062", "100", "150")) describe("v4_$t", load("v4_$t.jpg"), g, learner)
        describe("ex4", load("ex4.png"), g, BattleLearner(BoardReader()))
        describe("blocos", load("blocos.png"), g, BattleLearner(BoardReader()))
    }
}
