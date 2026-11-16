package com.alan.shufflebot

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("bot", Context.MODE_PRIVATE)

    var boardLeft: Int
        get() = sp.getInt("left", -1)
        set(v) = sp.edit().putInt("left", v).apply()
    var boardTop: Int
        get() = sp.getInt("top", -1)
        set(v) = sp.edit().putInt("top", v).apply()
    var boardSize: Int
        get() = sp.getInt("size", 0)
        set(v) = sp.edit().putInt("size", v).apply()

    /** Tempo segurando o dedo antes/depois de arrastar (ms). 0 = arrasto simples. */
    var holdMs: Int
        get() = sp.getInt("hold", 40)
        set(v) = sp.edit().putInt("hold", v).apply()
    /** Duração do arrasto (ms). */
    var moveMs: Int
        get() = sp.getInt("move", 120)
        set(v) = sp.edit().putInt("move", v).apply()
    /** Pausa depois de cada jogada (ms). */
    var afterMoveMs: Int
        get() = sp.getInt("after2", 0)
        set(v) = sp.edit().putInt("after2", v).apply()
    /** Diferença máxima para considerar duas casas o mesmo Pokémon. */
    var threshold: Int
        get() = sp.getInt("thr2", 15)
        set(v) = sp.edit().putInt("thr2", v).apply()
    /** Esperar as peças pararem de cair antes de jogar (mais seguro, um pouco mais lento). */
    var waitStable: Boolean
        get() = sp.getBoolean("stable2", false)
        set(v) = sp.edit().putBoolean("stable2", v).apply()

    /** Usar a Shuffle Dex (tipo, habilidades, Mega) para escolher as jogadas. */
    var useDex: Boolean
        get() = sp.getBoolean("dex", true)
        set(v) = sp.edit().putBoolean("dex", v).apply()

    var panelX: Int
        get() = sp.getInt("px", 20)
        set(v) = sp.edit().putInt("px", v).apply()
    var panelY: Int
        get() = sp.getInt("py", 200)
        set(v) = sp.edit().putInt("py", v).apply()

    /** Posição da moldura de calibração (coordenadas da janela flutuante). */
    var frameX: Int
        get() = sp.getInt("fx", -1)
        set(v) = sp.edit().putInt("fx", v).apply()
    var frameY: Int
        get() = sp.getInt("fy", -1)
        set(v) = sp.edit().putInt("fy", v).apply()

    /** Time marcado: lista de (papel, aparência média da espécie). */
    var team: List<Pair<Role, FloatArray>>
        get() {
            val raw = sp.getString("team2", "") ?: ""
            if (raw.isBlank()) return emptyList()
            return raw.split(";").mapNotNull { e ->
                val parts = e.split(":")
                if (parts.size != 2) return@mapNotNull null
                val role = runCatching { Role.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
                val f = parts[1].split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                Pair(role, f)
            }
        }
        set(v) = sp.edit().putString("team2", v.joinToString(";") { (r, f) ->
            r.name + ":" + f.joinToString(",") { "%.1f".format(java.util.Locale.US, it) }
        }).apply()

    fun geom(): BoardGeom? =
        if (boardSize > 0 && boardLeft >= 0 && boardTop >= 0) BoardGeom(boardLeft, boardTop, boardSize) else null
}
