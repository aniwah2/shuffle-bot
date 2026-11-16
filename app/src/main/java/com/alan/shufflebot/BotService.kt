package com.alan.shufflebot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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
        row.addView(start)
        row.addView(calib)
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

        var prev: Array<FloatArray>? = null
        var lastMove: Move? = null
        var beforeMove: Array<FloatArray>? = null
        val failed = HashSet<Long>()
        val cellFails = IntArray(total)
        var moves = 0
        var unstable = 0

        while (running) {
            val bmp = screenshot()
            if (bmp == null) { Thread.sleep(120); continue }
            val feats = reader.features(bmp, g)
            bmp.recycle()
            if (feats == null) { status("Grade fora da tela — recalibre"); Thread.sleep(1000); continue }

            // 1) esperar as peças pararem de cair
            val p0 = prev
            prev = feats
            if (prefs.waitStable && unstable < 6 && (p0 == null || reader.changedCells(p0, feats) > 1)) {
                unstable++; continue
            }
            unstable = 0

            // 2) a última jogada funcionou? (se o tabuleiro não mudou, era pedra/bloco: não tentar de novo)
            val lm = lastMove
            val bm = beforeMove
            if (lm != null && bm != null) {
                if (reader.changedCells(bm, feats) < 3) {
                    failed.add(lm.key)
                    cellFails[lm.from]++; cellFails[lm.to]++
                } else {
                    failed.clear(); cellFails.fill(0)
                }
                lastMove = null; beforeMove = null
            }

            // 3) reconhecer os tipos e escolher a jogada
            val labels = reader.cluster(feats, prefs.threshold.toFloat())
            val types = (labels.maxOrNull() ?: 0) + 1
            if (types < 3 || types > 14) {
                status("Não parece o tabuleiro ($types tipos)")
                Thread.sleep(500); continue
            }
            val blocked = BooleanArray(total) { cellFails[it] >= 2 }
            val mv = Solver.best(labels, g.n, failed, blocked)
            if (mv == null) {
                status("Sem jogada ($types tipos)")
                if (failed.isNotEmpty() && failed.size > 40) { failed.clear(); cellFails.fill(0) }
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
