package com.alan.shufflebot

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var serviceState: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        fun text(t: String, size: Float = 15f, color: Int = Color.DKGRAY) = TextView(this).apply {
            text = t; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
        }

        col.addView(text("Shuffle Bot", 24f, Color.BLACK))
        serviceState = text("", 15f)
        col.addView(serviceState)

        col.addView(text(
            "Como usar:\n" +
            "1. Toque em \"Ativar serviço\" e ligue o Shuffle Bot em Acessibilidade.\n" +
            "   (Android 13+: se a opção estiver cinza, vá em Configurações › Apps › Shuffle Bot › ⋮ › \"Permitir configurações restritas\" e tente de novo.)\n" +
            "2. Abra o Pokémon Shuffle e entre numa fase.\n" +
            "3. No painel flutuante, toque em \"Calibrar\", encaixe a grade verde nas 6x6 casas (arraste pelo meio, redimensione pela bolinha amarela) e toque em \"Salvar grade\".\n" +
            "4. Arraste o painel para fora do tabuleiro e toque em \"▶ Iniciar\".\n\n" +
            "O painel mostra quantos tipos de peça ele está vendo. Se o número estiver maior que o real, aumente a tolerância um pouco (16–18); se estiver menor (confundindo Pokémon parecidos), diminua (12–13). Na dúvida, toque em 📷 no painel e veja o print salvo na Galeria."
        ))

        col.addView(Button(this).apply {
            text = "Ativar serviço (Acessibilidade)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        col.addView(text("Ajustes", 18f, Color.BLACK))

        fun numberField(label: String, value: Int): EditText {
            col.addView(text(label, 14f))
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
                col.addView(this)
            }
        }
        val thr = numberField("Tolerância para \"mesmo Pokémon\" (padrão 15)", prefs.threshold)
        val move = numberField("Duração do arrasto em ms (padrão 120; mínimo seguro ~60)", prefs.moveMs)
        val hold = numberField("Segurar antes/depois do arrasto em ms (padrão 40; 0 = desliga)", prefs.holdMs)
        val after = numberField("Pausa depois de cada jogada em ms (padrão 0)", prefs.afterMoveMs)
        val stable = CheckBox(this).apply {
            text = "Esperar as peças assentarem (mais preciso, um pouco mais lento; deixe desligado nas fases de tempo)"
            isChecked = prefs.waitStable
        }
        col.addView(stable)
        val dex = CheckBox(this).apply {
            text = "Usar a Shuffle Dex (reconhece seu time e o inimigo e joga pensando em tipo, habilidade e Mega)"
            isChecked = prefs.useDex
        }
        col.addView(dex)

        col.addView(Button(this).apply {
            text = "Salvar ajustes"
            setOnClickListener {
                fun v(e: EditText, d: Int) = e.text.toString().toIntOrNull() ?: d
                prefs.threshold = v(thr, 15).coerceIn(5, 60)
                prefs.moveMs = v(move, 120).coerceIn(30, 2000)
                prefs.holdMs = v(hold, 40).coerceIn(0, 1000)
                prefs.afterMoveMs = v(after, 0).coerceIn(0, 3000)
                prefs.waitStable = stable.isChecked
                prefs.useDex = dex.isChecked
                Toast.makeText(this@MainActivity, "Salvo! Vale a partir do próximo ▶ Iniciar.", Toast.LENGTH_SHORT).show()
            }
        })

        col.addView(Button(this).apply {
            text = "Resetar calibração da grade"
            setOnClickListener {
                prefs.boardSize = 0; prefs.boardLeft = -1; prefs.boardTop = -1
                Toast.makeText(this@MainActivity, "Calibração apagada", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val on = BotService.instance != null
        serviceState.text = if (on) "✅ Serviço ativo — o painel flutuante está na tela"
                            else "⚠️ Serviço desligado — ative em Acessibilidade"
        serviceState.setTextColor(if (on) Color.rgb(0, 140, 60) else Color.rgb(200, 90, 0))
    }
}
