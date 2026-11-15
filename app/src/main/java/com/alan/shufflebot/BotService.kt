package com.alan.shufflebot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
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
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

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

    private fun toggleCalibration() {
        val prefs = Prefs(this)
        val current = frame
        if (current == null) {
            stopBot()
            val bounds = wm.currentWindowMetrics.bounds
            val size = if (prefs.boardSize > 0) prefs.boardSize else (bounds.width() * 0.9).toInt()
            val left = if (prefs.boardLeft >= 0) prefs.boardLeft else (bounds.width() - size) / 2
            val top = if (prefs.boardTop >= 0) prefs.boardTop else (bounds.height() * 0.45).toInt()

            val v = GridFrameView(this)
            val p = overlayParams(size, size).apply { x = left; y = top }
            v.onDrag = { dx, dy -> p.x += dx; p.y += dy; wm.updateViewLayout(v, p) }
            v.onResize = { d ->
                p.width = (p.width + d).coerceAtLeast(dp(120)); p.height = p.width
                wm.updateViewLayout(v, p)
            }
            wm.addView(v, p)
            frame = v
            calibBtn?.text = "Salvar grade"
            status("Encaixe a grade nas 6x6 casas")
        } else {
            val loc = IntArray(2)
            current.getLocationOnScreen(loc)
            prefs.boardLeft = loc[0]
            prefs.boardTop = loc[1]
            prefs.boardSize = current.width
            wm.removeView(current)
            frame = null
            calibBtn?.text = "Calibrar"
            status("Grade salva. Pode iniciar!")
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
