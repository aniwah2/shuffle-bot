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
import android.widget.ImageView
import android.widget.HorizontalScrollView
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
    private var recBox: LinearLayout? = null
    private val spriteSheet: Bitmap? by lazy {
        runCatching { assets.open("sprites48.webp").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
    }
    private fun icon(e: DexEntry): Bitmap? {
        val sh = spriteSheet ?: return null
        if (e.index < 0) return null
        val x = (e.index % 34) * 48; val y = (e.index / 34) * 48
        if (x + 48 > sh.width || y + 48 > sh.height) return null
        return runCatching { Bitmap.createBitmap(sh, x, y, 48, 48) }.getOrNull()
    }
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
        runCatching { stopService(android.content.Intent(this, CaptureService::class.java)) }
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
            text = "⠿ Shuffle Bot · ${appVersion()}"
            setTextColor(Color.WHITE); textSize = 13f
        }
        val status = TextView(this).apply {
            text = "Parado"
            setTextColor(Color.rgb(180, 220, 255)); textSize = 11f
        }
        val rec = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
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
        root.addView(handle); root.addView(status); root.addView(rec); root.addView(row)

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
        root.viewTreeObserver.addOnGlobalLayoutListener { updatePanelRect() }
        panel = root; panelParams = p; statusText = status; recBox = rec; startBtn = start; calibBtn = calib
    }

    private fun status(msg: String) = main.post { statusText?.text = msg }

    /** Onde o painel está na tela (para apagá-lo dos prints antes de ler texto). */
    @Volatile private var panelRect = android.graphics.Rect()
    private fun updatePanelRect() {
        val v = panel ?: return
        val xy = IntArray(2); v.getLocationOnScreen(xy)
        panelRect = android.graphics.Rect(xy[0], xy[1], xy[0] + v.width, xy[1] + v.height)
    }

    /** Pinta o painel de preto num recorte da tela (x0,y0 = canto do recorte; scale = redução). */
    private fun maskPanel(bmp: Bitmap, x0: Int, y0: Int, scale: Float = 1f): Bitmap {
        val r = panelRect
        if (r.isEmpty) return bmp
        val out = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true).also { bmp.recycle() }
        Canvas(out).drawRect((r.left - x0) * scale, (r.top - y0) * scale, (r.right - x0) * scale, (r.bottom - y0) * scale,
            Paint().apply { color = Color.BLACK })
        return out
    }

    /** Se o painel estiver em cima do tabuleiro, sobe ele para logo acima do tabuleiro. */
    private fun keepPanelOffBoard(g: BoardGeom) = main.post {
        val v = panel ?: return@post; val p = panelParams ?: return@post
        updatePanelRect()
        val r = panelRect
        if (r.bottom <= g.top || r.top >= g.top + g.size) return@post
        val newTop = (g.top - v.height - dp(6)).coerceAtLeast(0)
        p.y += newTop - r.top
        runCatching { wm.updateViewLayout(v, p) }
        v.post { updatePanelRect() }
    }

    /** Mostra a recomendação no painel com os ícones dos Pokémon. */
    private fun recommendation(r: Dex.Recommendation?) = main.post {
        val box = recBox ?: return@post
        box.removeAllViews()
        if (r == null) { box.visibility = View.GONE; return@post }
        fun label(t: String, color: Int = Color.rgb(255, 230, 150), size: Float = 10f) = TextView(this).apply {
            text = t; setTextColor(color); textSize = size
        }
        fun iconView(e: DexEntry, sub: String, color: Int): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), 0, dp(2), 0)
            addView(ImageView(this@BotService).apply {
                icon(e)?.let { setImageBitmap(it) }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            })
            addView(label(e.name.take(14), Color.WHITE, 8f).apply { gravity = Gravity.CENTER })
            addView(label(sub, color, 8f).apply { gravity = Gravity.CENTER })
        }
        fun row(title: String, items: List<Triple<DexEntry, String, Int>>) {
            if (items.isEmpty()) return
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(label(title).apply { setPadding(0, 0, dp(4), 0) })
            for ((e, sub, c) in items) line.addView(iconView(e, sub, c))
            box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(line) })
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { icon(r.opp)?.let { setImageBitmap(it) }; layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) })
        head.addView(label("vs ${r.opp.name} (${r.opp.type})\nforte contra ele: ${r.strong.joinToString("/")}", Color.WHITE, 10f))
        box.addView(head)
        row("Seu time:", r.team.map { (e, m) -> Triple(e, when { m > 1f -> "×2 ✓"; m < 1f -> "×½ ✗"; else -> "×1" }, when { m > 1f -> Color.rgb(120, 255, 120); m < 1f -> Color.rgb(255, 120, 120); else -> Color.LTGRAY }) })
        row("Mega:", r.megas.map { Triple(it, "${it.type} ${it.ap}", Color.rgb(255, 230, 150)) })
        row("Dex:", r.picks.map { Triple(it, "${it.type} ${it.ap}", Color.rgb(255, 230, 150)) })
        box.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- calibração

    private fun screenBounds() = wm.currentWindowMetrics.bounds

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    /** Grade salva (se você calibrou) ou a estimativa automática pelo tamanho da tela. */
    private fun geomOrGuess(prefs: Prefs): BoardGeom {
        val manual = if (prefs.manualGrid) prefs.geom() else null
        return manual ?: screenBounds().let { BoardGeom.guess(it.width(), it.height()) }
    }

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
            // mostra a moldura onde a grade REALMENTE está (a sua calibração, ou a estimativa automática)
            val cur = geomOrGuess(prefs)
            val size = cur.size
            val left = if (prefs.manualGrid && prefs.frameX >= 0) prefs.frameX else cur.left
            val top = if (prefs.manualGrid && prefs.frameY >= 0) prefs.frameY else cur.top

            val v = GridFrameView(this)
            v.ghost = loadGhost()
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
            prefs.manualGrid = true
            // refina a calibração uma única vez, agora, se o tabuleiro estiver na tela (e salva, porque foi você que calibrou)
            Thread {
                Thread.sleep(500)
                val g0 = prefs.geom() ?: return@Thread
                val g = autoFit(prefs, g0, BoardReader(), wide = true, useDex = prefs.useDex && runCatching { Dex.load(this) }.isSuccess)
                if (g != g0) { prefs.boardLeft = g.left; prefs.boardTop = g.top; prefs.boardSize = g.size; prefs.frameX = -1; prefs.frameY = -1 }
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
        val g = geomOrGuess(prefs)
        status("Lendo o tabuleiro…")
        Thread {
            Thread.sleep(400)  // dá tempo do jogo redesenhar
            val shot = screenshotRetry() ?: run { status("Não consegui ler a tela"); return@Thread }
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

    /** Chamado pela tela de permissão de captura: liga o bot (com ou sem captura rápida). */
    fun onCaptureReady(ok: Boolean) {
        if (!ok) status("Sem captura rápida — seguindo com prints")
        main.postDelayed({ launchWorker() }, if (ok) 600 else 0)
    }

    private fun startBot() {
        if (frame != null) toggleCalibration()
        if (teamView != null) toggleTeam()
        // O bot começa NA HORA com prints. Se a captura rápida estiver marcada, a permissão é pedida em
        // paralelo e, quando for concedida, o bot passa a usá-la sozinho (sem travar esperando a janela).
        launchWorker()
        if (Prefs(this).fastCapture && CaptureService.instance == null && !captureAsked) {
            captureAsked = true
            CaptureActivity.request(this)
        }
    }

    private fun launchWorker() {
        if (running && worker?.isAlive == true) return
        extraHold = 0; extraMove = 0
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
        recommendation(null)
    }

    // ---------------------------------------------------------------- loop principal

    /**
     * Tenta ajustar a grade, mas SÓ aceita (e salva) se a tela atual for claramente um tabuleiro
     * e a grade nova reconhecer melhor que a atual. Em menus/animações não muda nada.
     */
    /**
     * Tenta achar uma grade melhor no print atual. Só aceita se ler o tabuleiro claramente melhor
     * (com a Dex: mais Pokémon reconhecidos). O resultado vale só para esta execução — nunca é salvo.
     */
    private fun autoFit(prefs: Prefs, g0: BoardGeom, reader: BoardReader, wide: Boolean = false, useDex: Boolean = false): BoardGeom {
        val shot = screenshotRetry() ?: return g0
        try {
            val thr = prefs.threshold.toFloat()
            val s0 = boardScore(reader, shot, g0, thr, useDex)
            val gg = BoardGeom.guess(shot.width, shot.height)
            if (gg != g0 && boardScore(reader, shot, gg, thr, useDex) > s0) return gg
            if (useDex && s0 >= 2) return g0            // já reconhece o time: não mexe
            status("🔍 Ajustando a grade…")
            val g = runCatching { reader.fitGrid(shot, g0, thr, wide) }.getOrDefault(g0)
            if (g == g0) return g0
            val s1 = boardScore(reader, shot, g, thr, useDex)
            val ok = if (useDex) s1 >= 2 && s1 > s0 else {
                val v1 = reader.features(shot, g)?.let { reader.analyze(it, thr, reader.frozenByBorder(shot, g)) }
                v1 != null && reader.boardProblem(v1) == null && s1 > s0
            }
            return if (ok) g else g0
        } finally {
            shot.recycle()
        }
    }

    private fun botLoop() {
        val prefs = Prefs(this)
        val reader = BoardReader()
        var capSeen: CaptureService? = null       // captura rápida que já foi conferida com o print
        var lastCapCheck = System.currentTimeMillis()
        var g = geomOrGuess(prefs)            // grade salva ou estimada; só reajusta antes da batalha, se não conseguir ler
        var lastFitTry = 0L
        var emptyMovesOff = false        // se soltar em casa vazia não funcionar no jogo, desliga
        var cloudOffUntil = 0L; var cloudFails = 0                  // nuvem: pausa curta só se várias seguidas falharem
        var prevSolver: IntArray? = null                             // espécie de cada casa no print anterior
        var hidden = IntArray(36) { -1 }                             // palpite do que está embaixo de cada nuvem
        var notIt = Array(36) { HashSet<Int>() }                     // o que já sabemos que NÃO está embaixo da nuvem
        var specMove: Move? = null; var specCell = -1; var specSp = -1  // última jogada "testando" uma nuvem
        var megaCount = 0                // ícones do Mega já combinados (estimativa)
        var lastGhost = 0L               // última foto de referência do tabuleiro
        var wantGhost = false
        var lastMegaCells = 0

        // ---- Shuffle Dex: quem é cada Pokémon do time e quem é o inimigo
        val useDex = prefs.useDex && runCatching { Dex.load(this) }.isSuccess
        val knownEntry = ArrayList<DexEntry?>()           // mesma ordem de "known"
        val opponentRef = java.util.concurrent.atomic.AtomicReference<DexEntry?>(null)
        val endWordRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        var lastEndOcr = 0L
        var ocrTries = 0
        var lastOcr = 0L
        var emptyFails = 0; var emptyOk = 0
        var boardishSince = 0L
        val total = g.n * g.n
        val thr = prefs.threshold.toFloat()
        val team = prefs.team

        // O jogo é o app que estava na frente quando você tocou em Iniciar.
        // Janela de permissão/sistema/o próprio bot na frente NÃO conta como o jogo.
        fun isSystemPkg(p: String?) = p == null || p == packageName || p == "com.android.systemui" ||
            p.contains("permissioncontroller") || p.contains("packageinstaller") || p.startsWith("com.miui.securitycenter")
        var gamePkg: String? = foregroundPkg.takeUnless { isSystemPkg(it) }

        // ---- Pokémon DESTA batalha. Só joga se a maior parte das casas forem eles.
        // (Chão do cenário, Pokébola, janela da Great Ball etc. não batem com nenhum e o bot não toca.)
        val learner = BattleLearner(reader)
        val adoptName = HashMap<String, Int>()
        val known = learner.known
        val knownThr = learner.knownThr
        fun isKnown(f: FloatArray) = learner.isKnown(f)
        var adopt: List<FloatArray>? = null      // Pokémon novos no meio da batalha (ex.: Mega evoluiu)
        var adoptStreak = 0
        val waitingSince = System.currentTimeMillis()

        var prev: Array<FloatArray>? = null
        class Pend(val mv: Move, val before: Array<FloatArray>, val at: Long, val megaCells: Int,
                   val spec: Boolean, val specCell: Int, val specSp: Int)
        val pending = ArrayList<Pend>()
        var lastMove: Move? = null
        var moveDoneAt = 0L              // quando o último arrasto terminou
        val recent = ArrayDeque<Boolean>()   // últimas jogadas: pegou ou não no jogo
        var beforeMove: Array<FloatArray>? = null
        val failed = HashSet<Long>()
        val cellFails = IntArray(total)
        var moves = 0                    // arrastos feitos
        var realMoves = 0                // jogadas que o jogo aceitou
        // Tipo de fase, lido do contador acima do tabuleiro: "Time Limit 00:57" ou "Moves Left 15"
        var stageMode = 0                // 0 ainda não sei, 1 tempo, 2 jogadas limitadas
        val hudRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        var lastHud = 0L; var hudTries = 0
        var lastInfoOcr = 0L; var recShownFor: String? = null; var infoMiss = 0
        recommendation(null)
        val infoRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        var movesLeft = -1; var movesAtDrag = -1
        var pendingSpec: Pair<Int, Int>? = null
        var settling = false; var stableFrames = 0; var prevSettle: Array<FloatArray>? = null
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

        fun freeLooks(view: BoardView, feats: Array<FloatArray>, minCells: Int) = learner.freeLooks(view, feats, minCells)

        while (running) {
            // 0) o jogo ainda está na frente?
            val fg = foregroundPkg
            if (gamePkg == null && !isSystemPkg(fg)) gamePkg = fg
            if (gamePkg != null && fg != null && !isSystemPkg(fg) && fg != gamePkg) {
                autoStop("⏹ Parado: você saiu do jogo"); return
            }
            // 0b) você tocou na tela: o bot sai do caminho
            if (System.currentTimeMillis() < userPauseUntil) {
                status("✋ Você tocou na tela — esperando")
                lastMove = null; beforeMove = null; prev = null; pending.clear()
                Thread.sleep(150); continue
            }

            val bmp = grabFrame()
            if (bmp == null) { Thread.sleep(30); continue }
            // captura rápida: se a tela não mudou, não reprocessa à toa (mas reavalia a cada 250 ms)
            if (bmp === lastFrame && System.currentTimeMillis() - lastFrameAt < 250) { Thread.sleep(8); continue }
            lastFrame = bmp; lastFrameAt = System.currentTimeMillis()
            var f0 = reader.features(bmp, g)
            var b0 = if (f0 != null) reader.frozenByBorder(bmp, g) else null
            // Esperando a batalha: compara a grade atual com a estimativa automática no MESMO print e fica com a melhor
            if (known.isEmpty()) {
                val gg = BoardGeom.guess(bmp.width, bmp.height)
                if (gg != g) {
                    val sCur = boardScore(reader, bmp, g, thr, useDex)
                    val sGuess = boardScore(reader, bmp, gg, thr, useDex)
                    if (sGuess > sCur) {
                        g = gg
                        f0 = reader.features(bmp, g); b0 = f0?.let { reader.frozenByBorder(bmp, g) }
                        prev = null
                    }
                }
            }
            if (useDex && known.isNotEmpty() && opponentRef.get() == null && ocrTries < 5 &&
                System.currentTimeMillis() - lastOcr > 3000) {
                lastOcr = System.currentTimeMillis(); ocrTries++
                val x0 = (g.left + g.size * 0.45f).toInt().coerceIn(0, bmp.width - 1)
                val y0 = (g.top - g.size * 0.27f).toInt().coerceIn(0, bmp.height - 1)
                val w = (bmp.width - x0).coerceAtLeast(1)
                val h = (g.size * 0.23f).toInt().coerceIn(1, bmp.height - y0)
                runCatching { regionBitmap(bmp, x0, y0, w, h) }.getOrNull()?.let { crop ->
                    readOpponent(maskPanel(crop, x0, y0)) { e -> if (e != null) opponentRef.set(e) }
                }
            }
            // Contador da fase (tempo ou jogadas): define o modo de jogo; no modo jogadas, lê o número sempre
            val tHud = System.currentTimeMillis()
            if (known.isNotEmpty() && ((stageMode == 0 && hudTries < 12 && tHud - lastHud > 1000) || (stageMode == 2 && tHud - lastHud > 350))) {
                lastHud = tHud; if (stageMode == 0) hudTries++
                val x0 = (g.left - g.size * 0.02f).toInt().coerceIn(0, bmp.width - 1)
                val y0 = (g.top - g.size * 0.29f).toInt().coerceIn(0, bmp.height - 1)
                val w = (g.size * 0.36f).toInt().coerceIn(1, bmp.width - x0)
                val h = (g.size * 0.22f).toInt().coerceIn(1, bmp.height - y0)
                runCatching { regionBitmap(bmp, x0, y0, w, h) }.getOrNull()?.let { crop -> readText(maskPanel(crop, x0, y0)) { t -> hudRef.set(t) } }
            }
            hudRef.getAndSet(null)?.let { t ->
                val up = t.uppercase()
                val digits = Regex("\\d+").findAll(up.substringAfter("LEFT", up)).map { it.value.toInt() }.toList()
                if (up.contains(":") || up.contains("TIME") || up.contains("LIMIT")) { if (stageMode != 1) { stageMode = 1; status("Fase de TEMPO: modo rápido") } }
                else if (up.contains("MOVE") || up.contains("LEFT") || (stageMode == 2 && digits.size == 1)) {
                    if (stageMode != 2) { stageMode = 2; status("Fase de JOGADAS: modo estratégico") }
                    digits.lastOrNull { it in 0..99 }?.let { movesLeft = it }
                }
            }
            if (wantGhost) { saveGhost(bmp, g); wantGhost = false }
            val feats = f0
            if (feats == null) { status("Grade fora da tela — recalibre"); Thread.sleep(1000); continue }
            val border = b0
            val now = System.currentTimeMillis()
            CaptureService.instance?.let { ci -> if (ci !== capSeen) { capSeen = ci; verifyCapture(); prev = null; lastCapCheck = now } }

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
                // Dex primeiro (aguenta muita disrupção); a regra antiga, também segura contra menus, fica de reserva
                var learned = if (useDex) learner.tryLearnDex(view, feats) else emptyList()
                if (learned.isEmpty() && known.isEmpty()) {
                    val dexReason = learner.lastReason
                    learned = learner.tryLearn(view, feats)
                    if (learned.isEmpty() && useDex) learner.lastReason = "$dexReason / ${learner.lastReason}"
                }
                for (l in learned) knownEntry.add(if (useDex) Dex.identify(l) else null)
                if (known.isEmpty()) {
                    if (now - waitingSince > 300_000) { autoStop("⏹ Parado: 5 min sem ver uma batalha"); return }
                    // Tela de informação da fase (antes de apertar Start): lê o nome do inimigo e "Moves/Time"
                    // para mostrar a recomendação de time ANTES da batalha começar.
                    // (a cada 1 s, usando o MESMO print do laço — sem print extra)
                    if (useDex && now - lastInfoOcr > 1000) {
                        lastInfoOcr = now
                        runCatching {
                            val h = bmp.height * 2 / 3    // metade de cima da tela, onde fica o nome da fase/inimigo
                            val crop = regionBitmap(bmp, 0, 0, bmp.width, h)
                            val small = Bitmap.createScaledBitmap(crop, crop.width / 2, crop.height / 2, true)
                            crop.recycle()
                            readText(maskPanel(small, 0, 0, 0.5f)) { t -> infoRef.set(t ?: "") }
                        }
                    }
                    infoRef.getAndSet(null)?.let { t ->
                        val up = t.uppercase()
                        if (stageMode == 0) { if (up.contains("MOVES")) stageMode = 2 else if (up.contains("TIME")) stageMode = 1 }
                        var found: DexEntry? = null
                        for (line in t.split('\n')) {
                            val e = Dex.findByText(line.trim()) ?: continue
                            if (found == null || e.name.length > found!!.name.length) found = e
                        }
                        if (found != null) {
                            infoMiss = 0
                            if (opponentRef.get()?.name != found.name) { opponentRef.set(found); status("👁 Inimigo lido na tela: ${found.name}") }
                        } else if (opponentRef.get() != null && ++infoMiss >= 3) {
                            // saiu da tela da fase (nome sumiu por 3 leituras): limpa a recomendação
                            infoMiss = 0; opponentRef.set(null); recShownFor = null; recommendation(null)
                        }
                    }
                    opponentRef.get()?.let { opp -> if (recShownFor != opp.name + ":0") { recShownFor = opp.name + ":0"; recommendation(Dex.recommend(opp, emptyList())) } }
                    // esperando há um tempo com captura rápida: confere de novo se a imagem dela está certa
                    if (CaptureService.instance != null && now - lastCapCheck > 4000) {
                        lastCapCheck = now; verifyCapture(); prev = null; continue
                    }
                    // parece tabuleiro mas não dá pra ler direito: a grade pode estar imprecisa → ajusta (só aceita se melhorar)
                    // Só vale tentar se a tela PARECE um tabuleiro de verdade: a Dex reconhece pelo menos 4 casas
                    // (tela de informação da fase, menu e loja não passam — antes ficava "Ajustando a grade" sem parar)
                    val p = reader.boardProblem(view)
                    val dexHits = if (useDex) (0 until total).count { view.species[it] >= 0 && Dex.identify(feats[it]) != null } else 4
                    if (p != null && !p.startsWith("quase tudo liso") && dexHits >= 4 && now - lastFitTry > 10_000) {
                        if (boardishSince == 0L) boardishSince = now
                        if (now - boardishSince > 1500) {
                            lastFitTry = now
                            g = autoFit(prefs, g, reader, wide = true, useDex = useDex); prev = null
                            continue
                        }
                    } else if (p == null || p.startsWith("quase tudo liso")) boardishSince = 0L
                    status("⏳ Aguardando a batalha… (${learner.lastReason})")
                    continue
                }
            }

            // 3) é mesmo o tabuleiro desta batalha?
            // casas reconhecidas: Pokémon livres conhecidos + congelados dessas mesmas espécies.
            // Blocos não contam a favor nem contra (fases com muitos blocos).
            // Nome (pela Dex) de cada espécie desta tela, e quais nomes são do time desta batalha
            val spName = HashMap<Int, String>()
            if (useDex) for ((sp, look) in reader.speciesLook(view, feats)) Dex.identify(look)?.let { spName[sp] = it.baseName }
            val teamNames = knownEntry.mapNotNull { it?.baseName }.toHashSet()
            val knownFree = BooleanArray(total) {
                view.movable[it] && (isKnown(feats[it]) || (spName[view.species[it]]?.let { n -> n in teamNames } == true))
            }
            val knownSpecies = (0 until total).filter { knownFree[it] }.map { view.species[it] }.toHashSet()
            val knownFreeCount = knownFree.count { it }
            val recognized = (0 until total).count { knownFree[it] || (view.frozen[it] && (view.species[it] in knownSpecies || border?.get(it) == true)) }
            // fora da batalha (captura, Great Ball, Joias, Game Over) nunca passou de 2 casas reconhecidas;
            // na batalha, efeitos cobrem parte do tabuleiro — 30% já basta para seguir jogando
            // disrupções conhecidas (caixote, bloco de metal...) não contam contra
            val disruptCells = (0 until total).count { view.species[it] >= 0 && !knownFree[it] && learner.isDisrupt(feats[it]) }
            val needed = maxOf(6, (total - view.blocks - disruptCells) * 3 / 10)
            // Casas vazias: barreira/gelo no topo de uma coluna impede o jogo de repor as peças de baixo,
            // e as casas ficam vazias de verdade (fase 188). Muitas vazias só é "animação" se quase nada é reconhecido.
            if (recognized < needed || knownFreeCount < 3 || (view.empty > 9 && recognized < 12)) {
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
                        for (l in newLooks) knownEntry.add(if (useDex) Dex.identifyInBattle(l, knownEntry.filterNotNull()) else null)
                        adopt = null; adoptStreak = 0; continue
                    }
                } else { adopt = null; adoptStreak = 0 }

                if (lostSince == 0L) lostSince = now
                if (stageMode != 2) { lastMove = null; beforeMove = null }; pending.clear()
                // Fim de batalha = o jogo ESCREVEU isso na tela (CLEAR!, GAME OVER, Catchability…).
                // Combo grande ou efeito na tela só pausa: o bot não toca em nada enquanto não reconhece o tabuleiro.
                endWordRef.get()?.let { w -> autoStop("✅ Batalha terminou (li \"$w\"). Toque ▶ na próxima"); return }
                if (moves > 0 && now - lostSince > 1500 && now - lastEndOcr > 1500) {
                    lastEndOcr = now
                    screenshotRetry(2)?.let { shot ->
                        val small = runCatching { Bitmap.createScaledBitmap(shot, shot.width / 2, shot.height / 2, true) }.getOrNull()
                        shot.recycle()
                        if (small != null) readEndWord(small) { w -> if (w != null) endWordRef.set(w) }
                    }
                }
                if (moves > 0 && now - lostSince > 60_000) {
                    autoStop("⏹ Parado: 1 min sem ver o tabuleiro"); return
                }
                status("⏳ Animação do jogo — esperando o tabuleiro voltar ($recognized/$needed)")
                continue
            }
            lostSince = 0L
            endWordRef.set(null)
            // tabuleiro bem reconhecido: guarda uma foto de referência para a calibração (a cada 20 s)
            if (recognized >= needed + 6 && now - lastGhost > 20_000) { lastGhost = now; wantGhost = true }
            // Pokémon novos que apareçam no meio da batalha (disruptions) entram na lista
            if (known.size < 12) for (l in freeLooks(view, feats, 3)) if (!isKnown(l)) {
                known.add(l); knownEntry.add(if (useDex) Dex.identifyInBattle(l, knownEntry.filterNotNull()) else null)
            }
            // Pokémon que a Dex reconhece no tabuleiro (2+ casas) mas que ainda não estão no time do bot
            // (ex.: Roselia que começou com poucas peças): visto em 2 prints seguidos → passa a poder mexer.
            if (useDex && known.size < 16) {
                val cnt = HashMap<Int, Int>()
                for (i in 0 until total) if ((view.movable[i] || view.frozen[i]) && view.species[i] >= 0)
                    cnt[view.species[i]] = (cnt[view.species[i]] ?: 0) + 1
                val seen = HashSet<String>()
                for ((sp, look) in reader.speciesLook(view, feats)) {
                    val e = Dex.identifyInBattle(look, knownEntry.filterNotNull()) ?: continue
                    if (learner.isKnown(look) || (cnt[sp] ?: 0) < 2 || learner.isDisrupt(look)) continue
                    if (!e.isMega && e.baseName in teamNames) continue
                    seen.add(e.baseName)
                    val c = (adoptName[e.baseName] ?: 0) + 1
                    adoptName[e.baseName] = c
                    if (c >= 2) { known.add(look); knownEntry.add(e); teamNames.add(e.baseName); adoptName.remove(e.baseName) }
                }
                adoptName.keys.retainAll(seen)
            }

            // 4) as jogadas feitas pegaram? O bot NÃO fica esperando: continua jogando em outras partes do
            //    tabuleiro e confere cada jogada quando o print mostrar a reação (até ~0,45 s).
            val busy = BooleanArray(total)
            run {
                val it = pending.iterator()
                while (it.hasNext()) {
                    val pd = it.next(); val lm = pd.mv; val bm = pd.before
                    val area = HashSet<Int>(lm.cells.toList()); area.add(lm.from); area.add(lm.to)
                    val arrived = lm.to < total && reader.dist(feats[lm.to], bm[lm.from]) < knownThr
                    val moved = arrived || area.count { i: Int -> i < total && reader.dist(bm[i], feats[i]) > 30f } >= 2
                    val limit = if (lm.toEmpty || pd.spec) 650 else 450
                    if (!moved && now - pd.at < limit) {
                        // ainda esperando a reação: não mexe nessas casas nem no que está acima delas (vai cair)
                        for (i in area) if (i < total) { var r = i / g.n; val c = i % g.n; while (r >= 0) { busy[r * g.n + c] = true; r-- } }
                        continue
                    }
                    it.remove()
                    val counts = !lm.explore && !lm.toEmpty && !pd.spec      // só jogadas certas medem o arrasto
                    if (counts) { recent.addLast(moved); if (recent.size > 6) recent.removeFirst() }
                    if (lm.explore) {
                        if (moved) cloudFails = 0 else cloudFails++
                        if (lm.from in hidden.indices && !moved) hidden[lm.from] = -1      // palpite errado: esquece
                        if (lm.to in hidden.indices && !moved) hidden[lm.to] = -1
                        if (cloudFails >= 4) { cloudOffUntil = now + 2500; cloudFails = 0 }
                    }
                    else if (lm.toEmpty) { if (moved) emptyOk++ else emptyFails++; if (emptyFails >= 3 && emptyOk == 0) emptyMovesOff = true }
                    if (pd.spec && !moved && pd.specCell < notIt.size) notIt[pd.specCell].add(pd.specSp)   // não era esse: risca
                    if (moved) { realMoves++; megaCount += pd.megaCells; failed.clear(); cellFails.fill(0) }
                    else {
                        if (!pd.spec) failed.add(lm.key)
                        // vários arrastos CERTOS seguidos sem pegar: o arrasto está curto demais
                        // (o ajuste automático do arrasto foi removido: deixava o bot cada vez mais lento;
                        //  o arrasto é sempre o dos ajustes — mude "Segurar"/"Arrasto" se o jogo não aceitar)
                    }
                }
            }

            // 5) escolher e fazer a jogada
            val types = view.types
            // Com a Dex: o que não bate com nenhum Pokémon (caixote/pedra, moeda…) é disrupção e não se arrasta.
            // Só vale se a Dex reconheceu pelo menos 2 Pokémon (sinal de que está funcionando neste celular).
            val dexWorks = useDex && knownEntry.count { it != null } >= 2
            fun isDisruption(f: FloatArray): Boolean {
                if (!dexWorks) return false
                var bi = -1; var bd = Float.MAX_VALUE
                for (k in known.indices) { val d = reader.dist(known[k], f); if (d < bd) { bd = d; bi = k } }
                return bi >= 0 && bd < knownThr && knownEntry.getOrNull(bi) == null
            }
            // Só mexe em casas onde reconhece um Pokémon desta batalha. Nuvem, caixote, bloco, o botão do gravador
            // de tela ou qualquer outra coisa por cima ficam de fora (nem arrasta, nem conta como combinação).
            val blocked = BooleanArray(total) { busy[it] || !knownFree[it] || !view.movable[it] || cellFails[it] >= 1 || isDisruption(feats[it]) || learner.isDisrupt(feats[it]) }
            // Que disrupção é cada casa (pela aparência do jogo): rocha, bloco, nuvem, metal; gelo = moldura clara
            val dkind = IntArray(total)
            val dCount = HashMap<String, Int>()
            if (useDex) for (i in 0 until total) {
                if (knownFree[i]) continue
                if (view.frozen[i]) { dCount["gelo"] = (dCount["gelo"] ?: 0) + 1; continue }
                val k = Dex.disruptionKind(feats[i])
                if (k != null) {
                    dkind[i] = when (k) { "rocha" -> 1; "bloco" -> 2; "nuvem" -> 3; else -> 4 }
                    val key = if (k == "metal") "gelo" else k
                    dCount[key] = (dCount[key] ?: 0) + 1
                } else if (spName[view.species[i]]?.let { it !in teamNames } == true) {
                    dCount["estranho"] = (dCount["estranho"] ?: 0) + 1   // Pokémon que não é do seu time
                }
            }
            val exploreCells = if (now < cloudOffUntil || !useDex) null else BooleanArray(total) { dkind[it] == 3 }
            // o mesmo Pokémon dividido em grupos (nuvem/efeito) vira uma espécie só, para combinar entre si
            val canon = HashMap<Int, Int>(); val firstByName = HashMap<String, Int>()
            for ((sp, n) in spName) { val f0 = firstByName.getOrPut(n) { sp }; canon[sp] = f0 }
            val solverSpecies = IntArray(total) { i ->
                val sp = view.species[i]
                if (sp >= 0 && (knownFree[i] || (view.frozen[i] && sp in knownSpecies))) (canon[sp] ?: sp) else -(i + 1)
            }
            // Palpite embaixo da nuvem: a nuvem cobre uma peça que o bot acabou de ver → presume que é ela.
            // Se algo mudar na coluna (peças caíram), o palpite daquela coluna é esquecido.
            if (hidden.size != total) { hidden = IntArray(total) { -1 }; notIt = Array(total) { HashSet<Int>() } }
            val ps = prevSolver
            if (ps != null && ps.size == total) {
                for (c in 0 until g.n) {
                    var colChanged = false
                    for (r in 0 until g.n) { val i = r * g.n + c
                        if (dkind[i] != 3 && solverSpecies[i] >= 0 && ps[i] >= 0 && ps[i] != solverSpecies[i]) colChanged = true }
                    for (r in 0 until g.n) { val i = r * g.n + c
                        if (dkind[i] != 3) { hidden[i] = -1; notIt[i].clear() }
                        else if (ps[i] >= 0) { hidden[i] = ps[i]; notIt[i].clear() }   // nuvem acabou de cobrir esta peça
                        else if (colChanged) { hidden[i] = -1; notIt[i].clear() } }
                }
            }
            prevSolver = IntArray(total) { if (dkind[it] == 3) -1 else solverSpecies[it] }
            val guessedCells = if (exploreCells == null) null else BooleanArray(total) { dkind[it] == 3 && hidden[it] >= 0 }
            if (guessedCells != null) for (i in 0 until total) if (guessedCells[i]) { solverSpecies[i] = hidden[i]; blocked[i] = false }
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
                val megaBase = knownEntry.firstOrNull { it != null && !it.isMega && Dex.megaOf(it) != null }
                val megaNeed = megaBase?.let { Dex.megaOf(it)?.megaIcons } ?: 0
                val megaEvolved = knownEntry.any { it?.isMega == true } || (megaNeed > 0 && megaCount >= megaNeed)
                val disruptions = dCount
                val opp = opponentRef.get()
                value = SpeciesValue.from(ents, opp, disruptions, megaEvolved)
                if (megaBase != null && megaNeed > 0) {
                    dexTxt += if (megaEvolved) " • Mega ✓" else " • Mega ${minOf(megaCount, megaNeed)}/$megaNeed"
                }
                if (opp != null) dexTxt = " • vs ${opp.name}"
                // Na batalha a recomendação some: o painel fica pequeno e não cobre o tabuleiro
                if (recShownFor != "batalha") { recShownFor = "batalha"; recommendation(null); keepPanelOffBoard(g) }
            }
            // MODO JOGADAS: uma jogada por vez. Depois de arrastar, espera a cascata terminar (tabuleiro
            // parado em 2 prints seguidos) e confere pelo contador se o jogo aceitou a jogada.
            if (stageMode == 2 && settling) {
                val ch = prevSettle?.let { reader.changedCells(it, feats, 18f) } ?: 99
                prevSettle = feats
                stableFrames = if (ch == 0) stableFrames + 1 else 0
                if (now - moveDoneAt < 600 || stableFrames < 2) { status("⏳ Esperando a cascata terminar…"); continue }
                settling = false
                val lm = lastMove; val bm = beforeMove
                val accepted = (movesLeft in 0 until movesAtDrag) ||
                    (lm != null && bm != null && reader.changedCells(bm, feats, 30f) >= 3)
                if (accepted) { realMoves++; failed.clear(); cellFails.fill(0) }
                else if (lm != null) {
                    val ps = pendingSpec
                    if (ps != null && ps.first < notIt.size) notIt[ps.first].add(ps.second) else { failed.add(lm.key); cellFails[lm.from]++; cellFails[lm.to]++ }
                }
                pendingSpec = null
                lastMove = null; beforeMove = null
            }
            fun bestMove() = if (stageMode == 2)
                Solver.bestPlanned(solverSpecies, g.n, failed, blocked, view.frozen, roles, emptyCells, value,
                    if (useDex) dkind else null, exploreCells, guessedCells)
            else Solver.best(solverSpecies, g.n, failed, blocked, view.frozen, roles, emptyCells, value,
                if (useDex) dkind else null, exploreCells, guessedCells)
            var mv = bestMove()
            // Nenhuma jogada certa: usa as peças ESCONDIDAS. Para cada nuvem, imagina cada Pokémon que pode estar
            // embaixo (menos os já descartados) e procura a jogada que daria combinação. Peso = quão comum é
            // esse Pokémon no tabuleiro. Se o jogo não aceitar, aquele Pokémon é riscado daquela nuvem.
            var specTxt = ""
            if (mv == null && exploreCells != null && pending.isEmpty()) {
                val freq = HashMap<Int, Int>()
                for (i in 0 until total) if (knownFree[i] && solverSpecies[i] >= 0) freq[solverSpecies[i]] = (freq[solverSpecies[i]] ?: 0) + 1
                var bestEv = 0f; var bestMv: Move? = null; var bCell = -1; var bSp = -1
                val clouds = (0 until total).filter { dkind[it] == 3 && guessedCells?.get(it) != true }
                for (k in clouds.take(10)) {
                    val cands = freq.keys.filter { it !in notIt[k] }
                    if (cands.isEmpty()) continue
                    val tot = cands.sumOf { freq[it] ?: 0 }.toFloat()
                    for (c in cands) {
                        val lab = solverSpecies.copyOf(); lab[k] = c
                        val bl = blocked.copyOf(); bl[k] = false
                        val gs = guessedCells?.copyOf() ?: BooleanArray(total); gs[k] = true
                        val m = Solver.best(lab, g.n, failed, bl, view.frozen, roles, emptyCells, value,
                            if (useDex) dkind else null, exploreCells, gs) ?: continue
                        if (k != m.from && k != m.to && k !in m.cells) continue
                        val ev = m.score * (freq[c] ?: 0) / tot
                        if (ev > bestEv) { bestEv = ev; bestMv = m; bCell = k; bSp = c }
                    }
                }
                if (bestMv != null) {
                    mv = bestMv; specMove = bestMv; specCell = bCell; specSp = bSp
                    val nm = spName.entries.firstOrNull { (canon[it.key] ?: it.key) == bSp }?.value ?: "?"
                    specTxt = " • apostando: $nm na nuvem (${bCell / g.n + 1},${bCell % g.n + 1})"
                }
            }
            lastMegaCells = 0
            if (mv != null && useDex) {
                val mb = knownEntry.firstOrNull { it != null && !it.isMega && Dex.megaOf(it) != null }?.baseName
                if (mb != null) lastMegaCells = mv.cells.count { spName[view.species[it]] == mb }
            }
            if (mv == null && pending.isNotEmpty()) { Thread.sleep(5); continue }   // esperando a reação das jogadas feitas
            if (mv == null) {
                status("Sem jogada ($types tipos)")
                failed.clear(); cellFails.fill(0)
                continue
            }
            if (!running || System.currentTimeMillis() < userPauseUntil) continue

            moves++
            val extra = buildString {
                if (dCount.isNotEmpty()) {
                    for ((k, c) in dCount) append(" • $c $k")
                } else {
                    if (view.blocks > 0) append(" • ${view.blocks} blocos")
                    val fz = view.frozen.count { it }
                    if (fz > 0) append(" • $fz no gelo")
                }
                if (specTxt.isNotEmpty()) append(specTxt)
                else if (mv.explore) append(if (guessedCells?.let { it[mv.from] || it[mv.to] } == true) " • palpite na nuvem" else " • soltando na nuvem")
            }
            val teamTxt = if (roles != null && roles.any { it != Role.NORMAL }) " • time ✓" else ""
            val modeTxt = when (stageMode) { 2 -> " • JOGADAS" + (if (movesLeft >= 0) " ($movesLeft restantes)" else ""); 1 -> " • tempo"; else -> "" }
            status("Jogando$modeTxt$dexTxt • $types tipos$extra$teamTxt • jogada ${realMoves + 1}")
            beforeMove = feats
            lastMove = mv
            val isSpec = mv === specMove
            specMove = null
            drag(g.center(mv.from), g.center(mv.to), prefs)
            moveDoneAt = System.currentTimeMillis()
            if (stageMode == 2) {
                settling = true; stableFrames = 0; prevSettle = null; movesAtDrag = movesLeft
                if (isSpec && specCell < notIt.size) pendingSpec = Pair(specCell, specSp) else pendingSpec = null
                continue
            }
            pending.add(Pend(mv, feats, moveDoneAt, lastMegaCells, isSpec, if (isSpec) specCell else -1, if (isSpec) specSp else -1))
            if (prefs.afterMoveMs > 0) Thread.sleep(prefs.afterMoveMs.toLong())

            // Mais jogadas no MESMO print: o print do Android demora ~0,33 s; enquanto o próximo não vem,
            // joga em outras partes do tabuleiro que não têm nada a ver com as jogadas já feitas.
            if (stageMode != 2 && !mv.explore && !mv.toEmpty && !isSpec) {
                var extraMoves = 0
                var lastMv: Move = mv
                while (extraMoves < 2 && running && System.currentTimeMillis() >= userPauseUntil) {
                    // reserva as casas da jogada anterior, as que ela apaga e tudo acima (vai cair)
                    val area = HashSet<Int>(lastMv.cells.toList()); area.add(lastMv.from); area.add(lastMv.to)
                    for (i in area) if (i < total) { var r = i / g.n; val c = i % g.n; while (r >= 0) { blocked[r * g.n + c] = true; r-- } }
                    val m2 = Solver.best(solverSpecies, g.n, failed, blocked, view.frozen, roles, emptyCells, value,
                        if (useDex) dkind else null, null, null) ?: break
                    if (m2.explore || m2.toEmpty) break
                    moves++; extraMoves++
                    status("Jogando$modeTxt$dexTxt • $types tipos$extra$teamTxt • jogada ${realMoves + 1} (+${extraMoves} no mesmo print)")
                    drag(g.center(m2.from), g.center(m2.to), prefs)
                    moveDoneAt = System.currentTimeMillis()
                    pending.add(Pend(m2, feats, moveDoneAt, 0, false, -1, -1))
                    lastMv = m2
                }
            }
        }
    }

    /**
     * Salva na Galeria (Pictures/ShuffleBot) um print com o que o bot está "vendo":
     * o número do tipo em cada casa e o diagnóstico do tabuleiro.
     */
    private fun saveDebugShot() {
        Thread {
            val prefs = Prefs(this)
            val g = geomOrGuess(prefs)
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

    /** Lê todo o texto de um recorte (contador de tempo/jogadas). */
    private fun readText(crop: Bitmap, onResult: (String?) -> Unit) {
        try {
            recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { t -> crop.recycle(); onResult(t.text) }
                .addOnFailureListener { crop.recycle(); onResult(null) }
        } catch (e: Exception) { crop.recycle(); onResult(null) }
    }

    /** Palavras que o jogo mostra quando a batalha acabou (vitória, derrota, captura). */
    private val endWords = listOf(
        "CLEAR", "VICTORY", "GAME OVER", "GAMEOVER", "CATCHABILITY", "OUT OF TIME", "GREAT BALL",
        "TO MAP", "CAPTURE", "CAUGHT", "RAN AWAY", "FLED", "TRY AGAIN",
    )

    /** Lê o texto da tela inteira e diz qual palavra de fim de batalha apareceu (ou null). */
    private fun readEndWord(img: Bitmap, onResult: (String?) -> Unit) {
        try {
            recognizer.process(InputImage.fromBitmap(img, 0))
                .addOnSuccessListener { t ->
                    val txt = t.text.uppercase().replace("\n", " ")
                    img.recycle(); onResult(endWords.firstOrNull { txt.contains(it) })
                }
                .addOnFailureListener { img.recycle(); onResult(null) }
        } catch (e: Exception) { img.recycle(); onResult(null) }
    }

    /** Para o bot por conta própria e deixa o motivo no painel. */
    private fun autoStop(msg: String) {
        running = false
        recommendation(null)
        main.post {
            worker = null
            startBtn?.text = "▶ Iniciar"
            statusText?.text = msg
        }
    }

    // ---------------------------------------------------------------- print da tela

    private fun ghostFile() = java.io.File(filesDir, "tabuleiro_referencia.png")

    /** Guarda uma foto do tabuleiro (recortado pela grade) para usar como referência na calibração. */
    private fun saveGhost(bmp: ArrayImg, g: BoardGeom) {
        runCatching {
            if (g.left < 0 || g.top < 0 || g.left + g.size > bmp.width || g.top + g.size > bmp.height) return
            val crop = regionBitmap(bmp, g.left, g.top, g.size, g.size)
            val small = Bitmap.createScaledBitmap(crop, 480, 480, true)
            crop.recycle()
            ghostFile().outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 90, it) }
            small.recycle()
        }
    }

    private fun loadGhost(): Bitmap? = runCatching {
        val f = ghostFile(); if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.path) else null
    }.getOrNull()

    /** Quão bem essa grade lê um tabuleiro: Pokémon reconhecidos (com Dex) ou tabuleiro sem problema. */
    private fun boardScore(reader: BoardReader, bmp: Bitmap, g: BoardGeom, thr: Float, useDex: Boolean): Int =
        boardScore(reader, BitmapImg(bmp), g, thr, useDex)

    private fun boardScore(reader: BoardReader, bmp: Img, g: BoardGeom, thr: Float, useDex: Boolean): Int {
        val f = reader.features(bmp, g) ?: return -1
        val v = reader.analyze(f, thr, reader.frozenByBorder(bmp, g))
        if (useDex) {
            val cnt = v.species.filter { it >= 0 }.groupingBy { it }.eachCount()
            return reader.speciesLook(v, f).count { (sp, look) -> (cnt[sp] ?: 0) >= 3 && Dex.identify(look) != null }
        }
        return if (reader.boardProblem(v) == null) 1 else 0
    }

    @Volatile private var lastShotAt = 0L
    @Volatile private var lastFrame: ArrayImg? = null
    @Volatile private var lastFrameAt = 0L

    /**
     * Imagem atual da tela, já em pixels (lida de uma vez, sem ir pixel a pixel).
     * Com a captura rápida: o quadro mais recente, na hora. Sem ela: print da acessibilidade (máx. ~3/s).
     */
    private fun grabFrame(): ArrayImg? {
        CaptureService.instance?.let { cap ->
            val f = cap.grab()
            if (f != null) return f
        }
        val bmp = screenshot() ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val img = ArrayImg(bmp.width, bmp.height, px)
        bmp.recycle()
        return img
    }

    @Volatile private var captureAsked = false

    /**
     * Confere se a captura rápida mostra a MESMA imagem que o print normal. Corrige cor trocada;
     * se não bater, desliga a captura rápida e o bot segue com prints (que já funcionavam).
     * Retorna true se a captura está ok.
     */
    private fun verifyCapture(): Boolean {
        val cap = CaptureService.instance ?: return false
        val shot = screenshotRetry() ?: return true          // sem print para comparar: não decide agora
        val ref = IntArray(shot.width * shot.height)
        shot.getPixels(ref, 0, shot.width, 0, 0, shot.width, shot.height)
        val W = shot.width; val H = shot.height
        shot.recycle()
        val f = cap.grab() ?: return false
        if (f.width != W || f.height != H) { disableCapture("tamanho diferente (${f.width}x${f.height} vs ${W}x$H)"); return false }
        fun diff(swap: Boolean): Float {
            var s = 0L; var n = 0
            var y = H / 3
            while (y < H * 95 / 100) {
                var x = 8
                while (x < W - 8) {
                    val a = ref[y * W + x]; var b = f.px[y * W + x]
                    if (swap) b = (b and 0xFF00FF00.toInt()) or ((b and 0xFF) shl 16) or ((b shr 16) and 0xFF)
                    s += Math.abs(((a shr 16) and 255) - ((b shr 16) and 255)) + Math.abs(((a shr 8) and 255) - ((b shr 8) and 255)) + Math.abs((a and 255) - (b and 255))
                    n += 3; x += 16
                }
                y += 16
            }
            return if (n == 0) 999f else s.toFloat() / n
        }
        val d0 = diff(false); val d1 = diff(true)
        if (d0 <= 18f) return true
        if (d1 <= 18f) { cap.swapRB = !cap.swapRB; return true }
        disableCapture("imagem diferente do print (${d0.toInt()})")
        return false
    }

    private fun disableCapture(why: String) {
        runCatching { CaptureService.instance?.stopCapture() }
        status("Captura rápida desligada: $why — usando prints")
    }

    private fun regionBitmap(img: ArrayImg, x: Int, y: Int, w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(img.px, y * img.width + x, img.width, w, h, Bitmap.Config.ARGB_8888)

    /** Print com novas tentativas: o Android só permite ~3 prints por segundo. */
    private fun screenshotRetry(tries: Int = 4): Bitmap? {
        repeat(tries) {
            screenshot()?.let { return it }
            Thread.sleep(350)
        }
        return null
    }

    private fun screenshot(): Bitmap? {
        // o Android recusa prints com menos de ~1/3 s de intervalo: espera o tempo exato em vez de errar e tentar de novo
        val wait = 340 - (System.currentTimeMillis() - lastShotAt)
        if (wait > 0) Thread.sleep(wait)
        lastShotAt = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        var out: Bitmap? = null
        main.post {
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    // Celulares com tela "cores vivas" (Display P3) entregam o print em outro espaço de cor:
                    // os números das cores mudam (nuvem deixava de parecer nuvem). Converte para sRGB,
                    // o mesmo da captura rápida e da Dex.
                    val srgb = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
                    out = if (sw != null && sw.colorSpace != null && sw.colorSpace != srgb) {
                        runCatching {
                            val o = Bitmap.createBitmap(sw.width, sw.height, Bitmap.Config.ARGB_8888, true, srgb)
                            android.graphics.Canvas(o).drawBitmap(sw, 0f, 0f, null)
                            sw.recycle(); o
                        }.getOrElse { sw }
                    } else sw
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

    /** Acréscimo automático no arrasto quando o jogo não está aceitando (vale até o bot parar). */
    @Volatile private var extraHold = 0
    @Volatile private var extraMove = 0

    private fun drag(a: PointF, b: PointF, prefs: Prefs) {
        // mínimos que o jogo aceitou nos testes; o bot aumenta sozinho se os arrastos não pegarem
        val hold = (maxOf(prefs.holdMs, 30) + extraHold).toLong()
        val move = (maxOf(prefs.moveMs, 80) + extraMove).toLong()
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
