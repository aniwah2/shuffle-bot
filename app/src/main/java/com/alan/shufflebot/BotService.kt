package com.alan.shufflebot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Environment
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BotService : AccessibilityService() {

    companion object {
        @Volatile var instance: BotService? = null
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val shotExecutor = Executors.newSingleThreadExecutor()

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var statusText: TextView? = null
    private var startBtn: Button? = null
    private var calibBtn: Button? = null
    private var teamBtn: Button? = null
    private var teamView: TeamOverlayView? = null
    private var teamLooks: Map<Int, FloatArray>? = null
    private var frame: GridFrameView? = null
    private var frameLp: WindowManager.LayoutParams? = null

    @Volatile private var running = false
    /** Até quando o bot fica parado porque você tocou na tela. */
    @Volatile private var userPauseUntil = 0L
    /** Até quando um toque "fora do painel" é nosso próprio arrasto (e não seu dedo). */
    @Volatile private var gestureBusyUntil = 0L

    private fun onOutsideTouch() {
        val now = System.currentTimeMillis()
        if (now < gestureBusyUntil) return
        if (running) userPauseUntil = now + 2500
    }
    private var worker: Thread? = null

    // ---------------------------------------------------------------- ciclo de vida

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    /** App que está na frente agora (atualizado pelos eventos de acessibilidade). */
    @Volatile private var foregroundPkg: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return  // o nosso próprio painel não conta
        foregroundPkg = pkg
    }

    override fun onInterrupt() { stopBot() }

    override fun onDestroy() {
        stopBot()
        frame?.let { runCatching { wm.removeView(it) } }
        teamView?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        instance = null
        shotExecutor.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- painel flutuante

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // coordenadas sempre a partir do canto da tela física (inclusive notch/barra de status)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { onClick() }
    }

    private fun showPanel() {
        val prefs = Prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 20, 20, 30)); cornerRadius = dp(12).toFloat()
            }
        }
        val handle = TextView(this).apply {
            text = "⠿ Shuffle Bot"
            setTextColor(Color.WHITE); textSize = 13f
        }
        val status = TextView(this).apply {
            text = if (prefs.geom() == null) "Calibre a grade primeiro" else "Parado"
            setTextColor(Color.rgb(180, 220, 255)); textSize = 11f
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = smallButton("▶ Iniciar") { if (running) stopBot() else startBot() }
        val calib = smallButton("Calibrar") { toggleCalibration() }
        val team = smallButton("Time") { toggleTeam() }
        val debug = smallButton("📷") { saveDebugShot() }
        row.addView(start)
        row.addView(calib)
        row.addView(team)
        row.addView(debug)
        teamBtn = team
        root.addView(handle); root.addView(status); root.addView(row)

        val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            .apply {
                x = prefs.panelX; y = prefs.panelY
                // recebe um aviso quando você toca em qualquer lugar fora do painel
                flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            }
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTouch()
            false
        }

        // arrastar o painel pelo título
        var lx = 0f; var ly = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.rawX; ly = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    p.x += (e.rawX - lx).toInt(); p.y += (e.rawY - ly).toInt()
                    lx = e.rawX; ly = e.rawY
                    wm.updateViewLayout(root, p)
                }
                MotionEvent.ACTION_UP -> { prefs.panelX = p.x; prefs.panelY = p.y }
            }
            true
        }

        wm.addView(root, p)
        panel = root; panelParams = p; statusText = status; startBtn = start; calibBtn = calib
    }

    private fun status(msg: String) = main.post { statusText?.text = msg }

    // ---------------------------------------------------------------- calibração

    private fun screenBounds() = wm.currentWindowMetrics.bounds

    private fun systemBars(): Insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
    )

    /** Mantém a moldura sempre dentro da tela física. */
    private fun clampFrame(p: WindowManager.LayoutParams) {
        val b = screenBounds()
        val maxSize = minOf(b.width(), b.height())
        p.width = p.width.coerceIn(dp(120), maxSize)
        p.height = p.width
        p.x = p.x.coerceIn(0, b.width() - p.width)
        p.y = p.y.coerceIn(0, b.height() - p.height)
    }

    /** true se a moldura está em cima da barra de status, notch ou barra de navegação. */
    private fun overlapsSystemBars(p: WindowManager.LayoutParams): Boolean {
        val b = screenBounds()
        val ins = systemBars()
        return p.x < ins.left || p.y < ins.top ||
            p.x + p.width > b.width() - ins.right ||
            p.y + p.height > b.height() - ins.bottom
    }

    private fun refreshFrame(v: GridFrameView, p: WindowManager.LayoutParams) {
        clampFrame(p)
        wm.updateViewLayout(v, p)
        val warn = overlapsSystemBars(p)
        v.warning = warn
        status(if (warn) "⚠️ Grade em cima da barra do sistema" else "Encaixe a grade nas 6x6 casas")
    }

    private fun toggleCalibration() {
        val prefs = Prefs(this)
        val current = frame
        if (current == null) {
            stopBot()
            val bounds = screenBounds()
            val size = if (prefs.boardSize > 0) prefs.boardSize else (bounds.width() * 0.9).toInt()
            val left = if (prefs.frameX >= 0) prefs.frameX else (bounds.width() - size) / 2
            val top = if (prefs.frameY >= 0) prefs.frameY else (bounds.height() * 0.45).toInt()

            val v = GridFrameView(this)
            val p = overlayParams(size, size).apply { x = left; y = top }
            clampFrame(p)
            v.onDrag = { dx, dy -> p.x += dx; p.y += dy; refreshFrame(v, p) }
            v.onResize = { d -> p.width += d; p.height = p.width; refreshFrame(v, p) }
            wm.addView(v, p)
            frame = v
            frameLp = p
            calibBtn?.text = "Salvar grade"
            refreshFrame(v, p)
        } else {
            val p = frameLp ?: return
            val loc = IntArray(2)
            current.getLocationOnScreen(loc)
            val b = screenBounds()
            if (loc[0] < 0 || loc[1] < 0 || loc[0] + current.width > b.width() || loc[1] + current.height > b.height()) {
                status("❌ Grade fora da tela — ajuste antes de salvar")
                return
            }
            prefs.boardLeft = loc[0]
            prefs.boardTop = loc[1]
            prefs.boardSize = current.width
            prefs.frameX = p.x
            prefs.frameY = p.y
            val warn = overlapsSystemBars(p)
            wm.removeView(current)
            frame = null
            frameLp = null
            calibBtn?.text = "Calibrar"
            status(if (warn) "Salva, mas confira: parte da grade está na barra do sistema" else "✅ Grade salva. Pode iniciar!")
            // refina a calibração uma única vez, agora, se o tabuleiro estiver na tela
            Thread {
                Thread.sleep(500)
                val g0 = prefs.geom() ?: return@Thread
                val g = autoFit(prefs, g0, BoardReader())
                status(if (g != g0) "✅ Grade salva e ajustada. Pode iniciar!" else "✅ Grade salva. Pode iniciar!")
            }.start()
        }
    }

    // ---------------------------------------------------------------- time

    /** Abre a camada para marcar o time (ou salva e fecha, se já estiver aberta). */
    private fun toggleTeam() {
        val current = teamView
        val prefs = Prefs(this)
        if (current != null) {
            val looks = teamLooks ?: emptyMap()
            val list = current.roles.mapNotNull { (sp, role) -> looks[sp]?.let { Pair(role, it) } }
            prefs.team = list
            runCatching { wm.removeView(current) }
            teamView = null; teamLooks = null
            teamBtn?.text = "Time"
            status(if (list.isEmpty()) "Time limpo (todos normais)" else "✅ Time salvo: ${list.size} marcado(s)")
            return
        }
        if (frame != null) toggleCalibration()
        stopBot()
        val g = prefs.geom() ?: run { status("Calibre a grade primeiro"); return }
        status("Lendo o tabuleiro…")
        Thread {
            Thread.sleep(400)  // dá tempo do jogo redesenhar
            val shot = screenshot() ?: run { status("Não consegui ler a tela"); return@Thread }
            val reader = BoardReader()
            val feats = reader.features(shot, g)
            shot.recycle()
            if (feats == null) { status("Grade fora da tela — recalibre"); return@Thread }
            val view = reader.analyze(feats, prefs.threshold.toFloat())
            if (reader.boardProblem(view) != null) {
                status("Abra uma batalha (tabuleiro na tela) para marcar o time")
                return@Thread
            }
            val looks = reader.speciesLook(view, feats)
            // papéis já salvos aparecem marcados
            val roles = HashMap<Int, Role>()
            reader.rolesFor(view, feats, prefs.team, prefs.threshold * 1.3f)?.forEachIndexed { sp, r ->
                if (r != Role.NORMAL && looks.containsKey(sp)) roles[sp] = r
            }
            main.post {
                val v = TeamOverlayView(this, g.n, view.species, roles)
                val p = overlayParams(g.size, g.size).apply {
                    x = if (prefs.frameX >= 0) prefs.frameX else g.left
                    y = if (prefs.frameY >= 0) prefs.frameY else g.top
                }
                wm.addView(v, p)
                teamView = v; teamLooks = looks
                teamBtn?.text = "Salvar time"
                status("Toque nos Pokémon: MEGA → FORTE → apoio")
            }
        }.start()
    }

    // ---------------------------------------------------------------- liga/desliga

    private fun startBot() {
        if (frame != null) toggleCalibration()
        if (teamView != null) toggleTeam()
        if (Prefs(this).geom() == null) { status("Calibre a grade primeiro"); return }
        running = true
        startBtn?.text = "⏸ Parar"
        worker = Thread({
            try { botLoop() }
            catch (_: InterruptedException) { }
            catch (e: Exception) { status("Erro: ${e.message}"); main.post { stopBot() } }
        }, "bot").apply { start() }
    }

    fun stopBot() {
        running = false
        worker?.interrupt()
        worker = null
        main.post { startBtn?.text = "▶ Iniciar" }
        status("Parado")
    }

    // ---------------------------------------------------------------- loop principal

    /**
     * Tenta ajustar a grade, mas SÓ aceita (e salva) se a tela atual for claramente um tabuleiro
     * e a grade nova reconhecer melhor que a atual. Em menus/animações não muda nada.
     */
    private fun autoFit(prefs: Prefs, g0: BoardGeom, reader: BoardReader): BoardGeom {
        val shot = screenshot() ?: return g0
        try {
            val thr = prefs.threshold.toFloat()
            val f0 = reader.features(shot, g0)
            val v0 = f0?.let { reader.analyze(it, thr) }
            val p0 = v0?.let { reader.boardProblem(it) } ?: "fora da tela"
            if (p0 == null && v0 != null && v0.types <= 7) return g0      // grade atual já está boa
            status("🔍 Ajustando a grade…")
            val g = runCatching { reader.fitGrid(shot, g0, thr) }.getOrDefault(g0)
            val f1 = reader.features(shot, g) ?: return g0
            val v1 = reader.analyze(f1, thr)
            val ok = reader.boardProblem(v1) == null && (p0 != null || v1.types < (v0?.types ?: 99))
            if (!ok || g == g0) return g0
            prefs.frameX = (if (prefs.frameX >= 0) prefs.frameX else g0.left) + (g.left - g0.left)
            prefs.frameY = (if (prefs.frameY >= 0) prefs.frameY else g0.top) + (g.top - g0.top)
            prefs.boardLeft = g.left; prefs.boardTop = g.top; prefs.boardSize = g.size
            return g
        } finally {
            shot.recycle()
        }
    }

    private fun botLoop() {
        val prefs = Prefs(this)
        val reader = BoardReader()
        var g = prefs.geom() ?: return       // usa a grade salva; só reajusta antes da batalha, se não conseguir ler
        var lastFitTry = 0L
        var emptyMovesOff = false        // se soltar em casa vazia não funcionar no jogo, desliga

        // ---- Shuffle Dex: quem é cada Pokémon do time e quem é o inimigo
        val useDex = prefs.useDex && runCatching { Dex.load(this) }.isSuccess
        val knownEntry = ArrayList<DexEntry?>()           // mesma ordem de "known"
        val opponentRef = java.util.concurrent.atomic.AtomicReference<DexEntry?>(null)
        var ocrTries = 0
        var lastOcr = 0L
        var emptyFails = 0; var emptyOk = 0
        var boardishSince = 0L
        val total = g.n * g.n
        val thr = prefs.threshold.toFloat()
        val team = prefs.team

        // O jogo é o app que estava na frente quando você tocou em Iniciar.
        val gamePkg = foregroundPkg

        // ---- Pokémon DESTA batalha. Só joga se a maior parte das casas forem eles.
        // (Chão do cenário, Pokébola, janela da Great Ball etc. não batem com nenhum e o bot não toca.)
        val known = ArrayList<FloatArray>()
        val knownThr = 20f
        fun isKnown(f: FloatArray) = known.any { reader.dist(it, f) < knownThr }
        var candidate: List<FloatArray>? = null
        var candidateStreak = 0
        var adopt: List<FloatArray>? = null      // Pokémon novos no meio da batalha (ex.: Mega evoluiu)
        var adoptStreak = 0
        val waitingSince = System.currentTimeMillis()

        var prev: Array<FloatArray>? = null
        var lastMove: Move? = null
        var beforeMove: Array<FloatArray>? = null
        val failed = HashSet<Long>()
        val cellFails = IntArray(total)
        var moves = 0
        var failStreak = 0
        var lostSince = 0L
        var unstable = 0

        // Aprende quais "tipos" não se mexem (pedra, bloco, moeda...): aparência que só aparece em jogadas que falharam.
        class Sig(val f: FloatArray) { var fails = 0; var ok = 0 }
        val sigs = ArrayList<Sig>()
        val sigThr = thr * 0.8f
        fun sigOf(f: FloatArray): Sig {
            for (sg in sigs) if (reader.dist(sg.f, f) < sigThr) return sg
            return Sig(f.copyOf()).also { sigs.add(it) }
        }
        fun unmovable(f: FloatArray) = sigs.any { it.ok == 0 && it.fails >= 2 && reader.dist(it.f, f) < sigThr }

        /** Aparência média das espécies livres com pelo menos [minCells] casas. */
        fun freeLooks(view: BoardView, feats: Array<FloatArray>, minCells: Int): List<FloatArray> {
            val cnt = HashMap<Int, Int>()
            // conta livres + congelados da espécie (fases cheias de gelo); a aparência vem só dos livres
            for (i in 0 until total) if ((view.movable[i] || view.frozen[i]) && view.species[i] >= 0) cnt[view.species[i]] = (cnt[view.species[i]] ?: 0) + 1
            val looks = reader.speciesLook(view, feats)
            return cnt.filter { it.value >= minCells }.keys.mapNotNull { looks[it] }
        }

        while (running) {
            // 0) o jogo ainda está na frente?
            val fg = foregroundPkg
            if (gamePkg != null && fg != null && fg != gamePkg) {
                autoStop("⏹ Parado: você saiu do jogo"); return
            }
            // 0b) você tocou na tela: o bot sai do caminho
            if (System.currentTimeMillis() < userPauseUntil) {
                status("✋ Você tocou na tela — esperando")
                lastMove = null; beforeMove = null; prev = null
                Thread.sleep(150); continue
            }

            val bmp = screenshot()
            if (bmp == null) { Thread.sleep(60); continue }
            val feats = reader.features(bmp, g)
            val border = if (feats != null) reader.frozenByBorder(bmp, g) else null
            if (useDex && known.isNotEmpty() && opponentRef.get() == null && ocrTries < 5 &&
                System.currentTimeMillis() - lastOcr > 3000) {
                lastOcr = System.currentTimeMillis(); ocrTries++
                val x0 = (g.left + g.size * 0.45f).toInt().coerceIn(0, bmp.width - 1)
                val y0 = (g.top - g.size * 0.27f).toInt().coerceIn(0, bmp.height - 1)
                val w = (bmp.width - x0).coerceAtLeast(1)
                val h = (g.size * 0.23f).toInt().coerceIn(1, bmp.height - y0)
                runCatching { Bitmap.createBitmap(bmp, x0, y0, w, h) }.getOrNull()?.let { crop ->
                    readOpponent(crop) { e -> if (e != null) opponentRef.set(e) }
                }
            }
            bmp.recycle()
            if (feats == null) { status("Grade fora da tela — recalibre"); Thread.sleep(1000); continue }
            val now = System.currentTimeMillis()

            // 1) (opcional) esperar as peças assentarem — no máximo 1 print a mais
            val p0 = prev
            prev = feats
            if (prefs.waitStable && unstable < 1 && p0 != null && reader.changedCells(p0, feats, 30f) > 3) {
                unstable++; continue
            }
            unstable = 0

            val view = reader.analyze(feats, thr, border)
            val emptyCells = if (emptyMovesOff) null else view.isEmpty

            // 2) ainda não sabe quais Pokémon são desta batalha: aprende com um tabuleiro claro e parado
            if (known.isEmpty()) {
                val looks = if (reader.boardProblem(view) == null && view.empty <= 2) freeLooks(view, feats, 4) else emptyList()
                if (looks.size >= 3) {
                    val prevC = candidate
                    val same = prevC != null && prevC.size == looks.size &&
                        looks.all { l -> prevC.any { reader.dist(it, l) < knownThr } }
                    candidateStreak = if (same) candidateStreak + 1 else 0
                    candidate = looks
                    if (candidateStreak >= 2) {
                        known.addAll(looks)
                        for (l in looks) knownEntry.add(if (useDex) Dex.identify(l) else null)
                    }
                } else {
                    candidate = null; candidateStreak = 0
                }
                if (known.isEmpty()) {
                    if (now - waitingSince > 300_000) { autoStop("⏹ Parado: 5 min sem ver uma batalha"); return }
                    // parece tabuleiro mas não dá pra ler direito: a grade pode estar imprecisa → ajusta (só aceita se melhorar)
                    val p = reader.boardProblem(view)
                    if (p != null && !p.startsWith("quase tudo liso") && now - lastFitTry > 8000) {
                        if (boardishSince == 0L) boardishSince = now
                        if (now - boardishSince > 1500) {
                            lastFitTry = now
                            g = autoFit(prefs, g, reader); prev = null
                            continue
                        }
                    } else if (p == null || p.startsWith("quase tudo liso")) boardishSince = 0L
                    status("⏳ Aguardando a batalha…")
                    continue
                }
            }

            // 3) é mesmo o tabuleiro desta batalha?
            // casas reconhecidas: Pokémon livres conhecidos + congelados dessas mesmas espécies.
            // Blocos não contam a favor nem contra (fases com muitos blocos).
            val knownFree = BooleanArray(total) { view.movable[it] && isKnown(feats[it]) }
            val knownSpecies = (0 until total).filter { knownFree[it] }.map { view.species[it] }.toHashSet()
            val knownFreeCount = knownFree.count { it }
            val recognized = (0 until total).count { knownFree[it] || (view.frozen[it] && (view.species[it] in knownSpecies || border?.get(it) == true)) }
            // fora da batalha (captura, Great Ball, Joias, Game Over) nunca passou de 2 casas reconhecidas;
            // na batalha, efeitos cobrem parte do tabuleiro — 30% já basta para seguir jogando
            val needed = maxOf(7, (total - view.blocks) * 3 / 10)
            if (recognized < needed || knownFreeCount < 3 || view.empty > 9) {
                // Parte do tabuleiro ainda é conhecida e o resto forma Pokémon consistentes (o Mega evoluiu e
                // mudou de ícone, ou entrou Pokémon novo): se isso se repetir em 2 prints seguidos, aprende.
                val partial = recognized >= maxOf(6, (total - view.blocks) * 3 / 10)
                val newLooks = if (partial && view.empty <= 2 && reader.boardProblem(view) == null)
                    freeLooks(view, feats, 3).filter { !isKnown(it) } else emptyList()
                if (newLooks.isNotEmpty() && known.size < 14) {
                    val prevA = adopt
                    val same = prevA != null && newLooks.all { l -> prevA.any { reader.dist(it, l) < knownThr } }
                    adoptStreak = if (same) adoptStreak + 1 else 0
                    adopt = newLooks
                    if (adoptStreak >= 1) {
                        known.addAll(newLooks)
                        for (l in newLooks) knownEntry.add(if (useDex) Dex.identify(l) else null)
                        adopt = null; adoptStreak = 0; continue
                    }
                } else { adopt = null; adoptStreak = 0 }

                if (lostSince == 0L) lostSince = now
                lastMove = null; beforeMove = null
                if (moves > 0 && now - lostSince > 6000) {
                    autoStop("✅ Batalha terminou. Toque ▶ na próxima"); return
                }
                status("⏸ Tabuleiro sumiu ($recognized/$needed) — animação?")
                continue
            }
            lostSince = 0L
            // Pokémon novos que apareçam no meio da batalha (disruptions) entram na lista
            if (known.size < 12) for (l in freeLooks(view, feats, 3)) if (!isKnown(l)) {
                known.add(l); knownEntry.add(if (useDex) Dex.identify(l) else null)
            }

            // 4) a última jogada funcionou? (se o tabuleiro não mudou, era pedra/bloco: não tentar de novo)
            val lm = lastMove
            val bm = beforeMove
            if (lm != null && bm != null) {
                val moved = reader.dist(bm[lm.from], feats[lm.from]) > 25f || reader.dist(bm[lm.to], feats[lm.to]) > 25f
                val wasEmptyMove = lm.toEmpty
                if (wasEmptyMove) { if (moved) emptyOk++ else emptyFails++; if (emptyFails >= 3 && emptyOk == 0) emptyMovesOff = true }
                if (!moved) {
                    failed.add(lm.key)
                    cellFails[lm.from]++; cellFails[lm.to]++
                    sigOf(bm[lm.from]).fails++; sigOf(bm[lm.to]).fails++
                    if (!wasEmptyMove) failStreak++
                    if (failStreak >= 15) { autoStop("⏹ Parado: as jogadas não estão funcionando"); return }
                } else {
                    sigOf(bm[lm.from]).ok++; sigOf(bm[lm.to]).ok++
                    failed.clear(); cellFails.fill(0); failStreak = 0
                }
                lastMove = null; beforeMove = null
            }

            // 5) escolher e fazer a jogada
            val types = view.types
            val blocked = BooleanArray(total) { !view.movable[it] || cellFails[it] >= 1 || unmovable(feats[it]) }
            val roles = reader.rolesFor(view, feats, team, thr * 1.3f)
            var value: SpeciesValue? = null
            var dexTxt = ""
            if (useDex) {
                val looks = reader.speciesLook(view, feats)
                val maxSp = view.species.maxOrNull() ?: -1
                val ents = arrayOfNulls<DexEntry>(maxSp + 1)
                for ((sp, look) in looks) {
                    var bi = -1; var bd = Float.MAX_VALUE
                    for (k in known.indices) { val d = reader.dist(known[k], look); if (d < bd) { bd = d; bi = k } }
                    if (bi >= 0 && bd < knownThr) ents[sp] = knownEntry.getOrNull(bi)
                }
                val megaEvolved = knownEntry.any { it?.isMega == true }
                val disruptions = view.blocks > 0 || view.frozen.any { it }
                val opp = opponentRef.get()
                value = SpeciesValue.from(ents, opp, disruptions, megaEvolved)
                if (opp != null) dexTxt = " • vs ${opp.name}"
            }
            val mv = Solver.best(view.species, g.n, failed, blocked, view.frozen, roles, emptyCells, value)
            if (mv == null) {
                status("Sem jogada ($types tipos)")
                failed.clear(); cellFails.fill(0)
                continue
            }
            if (!running || System.currentTimeMillis() < userPauseUntil) continue

            moves++
            val extra = buildString {
                if (view.blocks > 0) append(" • ${view.blocks} blocos")
                val fz = view.frozen.count { it }
                if (fz > 0) append(" • $fz no gelo")
            }
            val teamTxt = if (roles != null && roles.any { it != Role.NORMAL }) " • time ✓" else ""
            status("Jogando$dexTxt • $types tipos$extra$teamTxt • jogada $moves")
            beforeMove = feats
            lastMove = mv
            drag(g.center(mv.from), g.center(mv.to), prefs)
            if (prefs.afterMoveMs > 0) Thread.sleep(prefs.afterMoveMs.toLong())
        }
    }

    /**
     * Salva na Galeria (Pictures/ShuffleBot) um print com o que o bot está "vendo":
     * o número do tipo em cada casa e o diagnóstico do tabuleiro.
     */
    private fun saveDebugShot() {
        Thread {
            val prefs = Prefs(this)
            val g = prefs.geom()
            if (g == null) { status("Calibre a grade primeiro"); return@Thread }
            var shot = screenshot()
            if (shot == null) { Thread.sleep(400); shot = screenshot() }
            if (shot == null) { status("Não consegui tirar o print"); return@Thread }
            val reader = BoardReader()
            val feats = reader.features(shot, g)
            val dbgBorder = if (feats != null) reader.frozenByBorder(shot, g) else null
            val out = shot.copy(Bitmap.Config.ARGB_8888, true)
            shot.recycle()

            val c = Canvas(out)
            val d = resources.displayMetrics.density
            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 16 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
            val bg = Paint().apply { color = Color.argb(170, 0, 0, 0) }
            val palette = intArrayOf(
                Color.RED, Color.CYAN, Color.YELLOW, Color.GREEN, Color.MAGENTA, Color.WHITE,
                Color.rgb(255, 140, 0), Color.rgb(140, 120, 255), Color.rgb(160, 255, 160), Color.rgb(255, 160, 200)
            )
            val lines = ArrayList<String>()
            if (feats == null) {
                lines.add("Grade fora da tela")
            } else {
                val view = reader.analyze(feats, prefs.threshold.toFloat(), dbgBorder)
                val fz = view.frozen.count { it }
                lines.add("tipos: ${view.types} • blocos: ${view.blocks} • gelo: $fz • vazias: ${view.empty} • tolerância: ${prefs.threshold}")
                lines.add("diagnóstico: ${reader.boardProblem(view) ?: "tabuleiro OK"} • brilho ${reader.brightness(feats).toInt()}")
                val blockedNow = BooleanArray(g.n * g.n) { !view.movable[it] }
                if (prefs.useDex && runCatching { Dex.load(this) }.isSuccess) {
                    val names = reader.speciesLook(view, feats).entries.sortedBy { it.key }
                        .joinToString { (sp, look) -> "$sp=" + (Dex.identify(look)?.name ?: "?") }
                    lines.add("Dex: $names")
                }
                val dbgRoles = reader.rolesFor(view, feats, prefs.team, prefs.threshold * 1.3f)
                if (dbgRoles != null) lines.add("time: " + dbgRoles.withIndex().filter { it.value != Role.NORMAL }
                    .joinToString { "${it.index}=${it.value.label}" }.ifEmpty { "nenhum reconhecido" })
                val mv = Solver.best(view.species, g.n, emptySet(), blockedNow, view.frozen, dbgRoles)
                for (i in view.species.indices) {
                    val sp = view.species[i]
                    val label = when {
                        sp < 0 -> "■"
                        view.frozen[i] -> "${sp}❄"
                        else -> sp.toString()
                    }
                    val col = if (sp < 0) Color.GRAY else palette[sp % palette.size]
                    val r = i / g.n
                    val cc = i % g.n
                    val x0 = g.left + cc * g.cell
                    val y0 = g.top + r * g.cell
                    box.color = col
                    c.drawRect(x0 + 2, y0 + 2, x0 + g.cell - 2, y0 + g.cell - 2, box)
                    val cx = x0 + g.cell / 2
                    val cy = y0 + g.cell / 2
                    c.drawCircle(cx, cy - 5 * d, 15 * d, bg)
                    txt.color = col
                    c.drawText(label, cx, cy, txt)
                }
                if (mv != null) {
                    val a = g.center(mv.from)
                    val b = g.center(mv.to)
                    val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 5 * d }
                    c.drawLine(a.x, a.y, b.x, b.y, arrow)
                    c.drawCircle(b.x, b.y, 8 * d, arrow)
                    lines.add("melhor jogada: casa ${mv.from} → ${mv.to}")
                }
            }
            var y = g.top - lines.size * 22 * d - 8 * d
            if (y < 40 * d) y = 40 * d
            txt.textAlign = Paint.Align.LEFT
            txt.textSize = 14 * d
            for (l in lines) {
                c.drawRect(0f, y - 17 * d, out.width.toFloat(), y + 6 * d, bg)
                txt.color = Color.WHITE
                c.drawText(l, 8 * d, y, txt)
                y += 22 * d
            }

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "shufflebot_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ShuffleBot")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            val ok = uri != null && contentResolver.openOutputStream(uri)?.use {
                out.compress(Bitmap.CompressFormat.PNG, 100, it)
            } == true
            out.recycle()
            status(if (ok) "📷 Print salvo na Galeria (ShuffleBot)" else "Não consegui salvar o print")
        }.start()
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Lê o nome do inimigo (caixinha à direita, acima do tabuleiro) e procura na Shuffle Dex. */
    private fun readOpponent(crop: Bitmap, onResult: (DexEntry?) -> Unit) {
        try {
            recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { t ->
                    var found: DexEntry? = null
                    for (block in t.textBlocks) {
                        val cands = block.lines.map { it.text } + block.text.replace("\n", " ")
                        for (txt in cands) {
                            val e = Dex.findByText(txt) ?: continue
                            if (found == null || e.name.length > found!!.name.length) found = e
                        }
                    }
                    crop.recycle(); onResult(found)
                }
                .addOnFailureListener { crop.recycle(); onResult(null) }
        } catch (e: Exception) { crop.recycle(); onResult(null) }
    }

    /** Para o bot por conta própria e deixa o motivo no painel. */
    private fun autoStop(msg: String) {
        running = false
        main.post {
            worker = null
            startBtn?.text = "▶ Iniciar"
            statusText?.text = msg
        }
    }

    // ---------------------------------------------------------------- print da tela

    private fun screenshot(): Bitmap? {
        val latch = CountDownLatch(1)
        var out: Bitmap? = null
        main.post {
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    out = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    buffer.close()
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) { latch.countDown() }
            })
        }
        latch.await(3, TimeUnit.SECONDS)
        return out
    }

    // ---------------------------------------------------------------- arrastar

    private fun line(a: PointF, b: PointF) = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y) }

    private fun dispatchSync(stroke: GestureDescription.StrokeDescription, timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        main.post {
            val sent = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(d: GestureDescription?) { latch.countDown() }
            }, main)
            if (!sent) latch.countDown()
        }
        latch.await(timeoutMs + 2000, TimeUnit.MILLISECONDS)
        return ok
    }

    private fun drag(a: PointF, b: PointF, prefs: Prefs) {
        val hold = prefs.holdMs.toLong()
        val move = prefs.moveMs.toLong().coerceAtLeast(30)
        gestureBusyUntil = System.currentTimeMillis() + hold * 2 + move + 400
        if (hold <= 0) {
            dispatchSync(GestureDescription.StrokeDescription(line(a, b), 0, move), move)
            return
        }
        // segura no início, arrasta, segura no fim (alguns jogos precisam disso para "pegar" a peça)
        val a2 = PointF(a.x + 1, a.y)
        val b2 = PointF(b.x + 1, b.y)
        val s1 = GestureDescription.StrokeDescription(line(a, a2), 0, hold, true)
        if (!dispatchSync(s1, hold)) return
        val s2 = s1.continueStroke(line(a2, b), 0, move, true)
        if (!dispatchSync(s2, move)) return
        val s3 = s2.continueStroke(line(b, b2), 0, hold, false)
        dispatchSync(s3, hold)
    }
}
