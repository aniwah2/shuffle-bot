package com.alan.shufflebot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import java.nio.ByteOrder

/**
 * Captura contínua da tela (MediaProjection): sem o limite de ~3 prints/s do serviço de acessibilidade.
 * O bot pega sempre o quadro mais recente, na hora.
 */
class CaptureService : Service() {

    companion object {
        @Volatile var instance: CaptureService? = null
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "captura"
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private val lock = Any()
    private var cache: ArrayImg? = null
    /** Se a conferência achar vermelho/azul trocados neste celular, corrige na conversão. */
    @Volatile var swapRB = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Captura da tela", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, CHANNEL)
            .setContentTitle("Shuffle Bot")
            .setContentText("Lendo a tela para jogar mais rápido")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, notif)

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return START_NOT_STICKY }
            projection = p
            val ht = HandlerThread("captura").apply { start() }
            thread = ht
            val h = Handler(ht.looper)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { release(); stopSelf() }
            }, h)
            val wm = getSystemService(WindowManager::class.java)
            val b = wm.currentWindowMetrics.bounds
            val w = b.width(); val hh = b.height()
            val r = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 3)
            reader = r
            display = p.createVirtualDisplay(
                "shufflebot", w, hh, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h,
            )
            instance = this
        } catch (e: Exception) {
            release(); stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Quadro mais recente da tela, em coordenadas de tela. Se a tela não mudou desde o último,
     * devolve o mesmo quadro (o Android só gera quadro novo quando algo muda).
     */
    fun grab(): ArrayImg? {
        val r = reader ?: return null
        synchronized(lock) {
            val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return cache
            try {
                val plane = image.planes[0]
                val w = image.width; val h = image.height
                val rowInts = plane.rowStride / 4
                val ib = plane.buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                val px = cache?.takeIf { it.width == w && it.height == h }?.px ?: IntArray(w * h)
                val row = IntArray(rowInts)
                for (y in 0 until h) {
                    ib.position(y * rowInts)
                    ib.get(row, 0, minOf(rowInts, ib.remaining()))
                    val o = y * w
                    for (x in 0 until w) {
                        val c = row[x]                       // bytes R,G,B,A lidos como inteiro 0xAABBGGRR
                        px[o + x] = if (swapRB) (c or 0xFF000000.toInt())
                                    else (c and 0xFF00FF00.toInt()) or ((c and 0xFF) shl 16) or ((c shr 16) and 0xFF)
                    }
                }
                val img = ArrayImg(w, h, px)
                cache = img
                return img
            } finally {
                image.close()
            }
        }
    }

    fun stopCapture() { release(); stopSelf() }

    private fun release() {
        instance = null
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        runCatching { thread?.quitSafely() }
        display = null; reader = null; projection = null
    }

    override fun onDestroy() { release(); super.onDestroy() }
}

/** Tela invisível que só pede a permissão de captura e liga o CaptureService. */
class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 7)
    }

    @Deprecated("API antiga, suficiente aqui")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, resultCode).putExtra(CaptureService.EXTRA_DATA, data))
            BotService.instance?.onCaptureReady(true)
        } else {
            BotService.instance?.onCaptureReady(false)
        }
        finish()
    }

    companion object {
        fun request(ctx: Context) {
            ctx.startActivity(Intent(ctx, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
