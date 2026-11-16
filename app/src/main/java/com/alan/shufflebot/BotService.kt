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
    private var frame: GridFrameView? = null
    private var frameLp: WindowManager.LayoutParams? = null

    @Volatile private var running = false
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
        val debug = smallButton("📷") { saveDebugShot() }
        row.addView(start)
        row.addView(calib)
        row.addView(debug)
        root.addView(handle); root.addView(status); root.addView(row)

        val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            .apply { x = prefs.panelX; y = prefs.panelY }

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
        }
    }

    // ---------------------------------------------------------------- liga/desliga

    private fun startBot() {
        if (frame != null) toggleCalibration()
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

    private fun botLoop() {
        val prefs = Prefs(this)
        val g = prefs.geom() ?: return
        val reader = BoardReader()
        val total = g.n * g.n

        // O jogo é o app que estava na frente quando você tocou em Iniciar.
        val gamePkg = foregroundPkg

        var prev: Array<FloatArray>? = null
        var lastMove: Move? = null
        var beforeMove: Array<FloatArray>? = null
        val failed = HashSet<Long>()
        val cellFails = IntArray(total)
        var moves = 0
        var failStreak = 0
        var baseline = -1f                       // brilho do tabuleiro no começo da batalha
        var badSince = 0L                        // desde quando a tela não parece batalha
        var unstable = 0

        // Aprende quais "tipos" não se mexem (pedra, bloco, moeda...): aparência que só aparece em jogadas que falharam.
        class Sig(val f: FloatArray) { var fails = 0; var ok = 0 }
        val sigs = ArrayList<Sig>()
        val sigThr = prefs.threshold * 0.8f
        fun sigOf(f: FloatArray): Sig {
            for (sg in sigs) if (reader.dist(sg.f, f) < sigThr) return sg
            return Sig(f.copyOf()).also { sigs.add(it) }
        }
        fun unmovable(f: FloatArray) = sigs.any { it.ok == 0 && it.fails >= 2 && reader.dist(it.f, f) < sigThr }

        // Tela que não parece batalha (animação, janela, menu): NÃO joga e espera o tabuleiro voltar.
        // Só desiste depois de muito tempo sem ver o tabuleiro.
        fun bad(reason: String): Boolean {
            val now = System.currentTimeMillis()
            if (badSince == 0L) badSince = now
            if (now - badSince > 90_000) {
                autoStop("⏹ Parado: 1 min e meio sem ver o tabuleiro")
                return true
            }
            status("⏸ Pausado: $reason")
            return false
        }

        while (running) {
            // 0) o jogo ainda está na frente?
            val fg = foregroundPkg
            if (gamePkg != null && fg != null && fg != gamePkg) {
                autoStop("⏹ Parado: você saiu do jogo"); return
            }

            val bmp = screenshot()
            if (bmp == null) { Thread.sleep(120); continue }
            val feats = reader.features(bmp, g)
            bmp.recycle()
            if (feats == null) { status("Grade fora da tela — recalibre"); Thread.sleep(1000); continue }

            // 1) esperar as peças pararem de cair (se a tela nunca para, não joga)
            val p0 = prev
            prev = feats
            if (prefs.waitStable && (p0 == null || reader.changedCells(p0, feats) > 1)) {
                unstable++
                if (unstable >= 4 && bad("tela mexendo")) return
                continue
            }
            unstable = 0

            // 2) parece mesmo o tabuleiro?
            val labels = reader.cluster(feats, prefs.threshold.toFloat())
            val problem = reader.boardProblem(labels)
            if (problem != null) { if (bad(problem)) return; Thread.sleep(150); continue }

            val light = reader.brightness(feats)
            if (baseline < 0) baseline = light
            if (light < baseline * 0.7f || light > baseline * 1.4f) {
                if (bad("tela escureceu/mudou")) return
                Thread.sleep(150); continue
            }
            badSince = 0L
            baseline = baseline * 0.85f + light * 0.15f   // acompanha variações pequenas

            // 3) a última jogada funcionou? (se o tabuleiro não mudou, era pedra/bloco: não tentar de novo)
            val lm = lastMove
            val bm = beforeMove
            if (lm != null && bm != null) {
                if (reader.changedCells(bm, feats) < 3) {
                    failed.add(lm.key)
                    cellFails[lm.from]++; cellFails[lm.to]++
                    sigOf(bm[lm.from]).fails++; sigOf(bm[lm.to]).fails++
                    failStreak++
                    if (failStreak >= 15) { autoStop("⏹ Parado: as jogadas não estão funcionando"); return }
                } else {
                    sigOf(bm[lm.from]).ok++; sigOf(bm[lm.to]).ok++
                    failed.clear(); cellFails.fill(0); failStreak = 0
                }
                lastMove = null; beforeMove = null
            }

            // 4) escolher e fazer a jogada
            val types = (labels.maxOrNull() ?: 0) + 1
            val blocked = BooleanArray(total) { cellFails[it] >= 1 || unmovable(feats[it]) }
            val mv = Solver.best(labels, g.n, failed, blocked)
            if (mv == null) {
                status("Sem jogada ($types tipos)")
                // talvez tenha bloqueado demais: libera as casas (mas mantém o que aprendeu sobre pedras)
                failed.clear(); cellFails.fill(0)
                Thread.sleep(300); continue
            }

            moves++
            status("Jogando • $types tipos • jogada $moves")
            beforeMove = feats
            lastMove = mv
            drag(g.center(mv.from), g.center(mv.to), prefs)
            Thread.sleep(prefs.afterMoveMs.toLong())
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
                val labels = reader.cluster(feats, prefs.threshold.toFloat())
                val types = (labels.maxOrNull() ?: 0) + 1
                lines.add("tipos: $types  •  brilho: ${reader.brightness(feats).toInt()}  •  tolerância: ${prefs.threshold}")
                lines.add("diagnóstico: ${reader.boardProblem(labels) ?: "tabuleiro OK"}")
                val mv = Solver.best(labels, g.n, emptySet(), BooleanArray(g.n * g.n))
                for (i in labels.indices) {
                    val col = palette[labels[i] % palette.size]
                    val r = i / g.n
                    val cc = i % g.n
                    val x0 = g.left + cc * g.cell
                    val y0 = g.top + r * g.cell
                    box.color = col
                    c.drawRect(x0 + 2, y0 + 2, x0 + g.cell - 2, y0 + g.cell - 2, box)
                    val cx = x0 + g.cell / 2
                    val cy = y0 + g.cell / 2
                    c.drawCircle(cx, cy - 5 * d, 13 * d, bg)
                    txt.color = col
                    c.drawText(labels[i].toString(), cx, cy, txt)
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
