package com.example.screencaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import com.example.screencaster.shared.DeviceInfo
import com.example.screencaster.shared.Message
import com.example.screencaster.shared.Protocol
import com.example.screencaster.shared.readMessage
import com.example.screencaster.shared.writeMessage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * خدمة البث (أمامية): تلتقط الشاشة بميديا بروجكشن، تشفّرها إتش ٢٦٤ بالعتاد،
 * وترسلها عبر الواي فاي إلى مستقبل عاكس. تعمل مع إشعار دائم فيه زر إيقاف.
 */
class CastService : Service() {

    companion object {
        const val ACTION_START = "com.example.screencaster.CAST_START"
        const val ACTION_STOP = "com.example.screencaster.CAST_STOP"
        const val ACTION_MODE = "com.example.screencaster.CAST_MODE"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        const val EXTRA_SCREEN_W = "screenW"
        const val EXTRA_SCREEN_H = "screenH"
        const val EXTRA_DPI = "dpi"
        const val EXTRA_PIN = "pin"

        private const val CHANNEL_ID = "cast"
        private const val NOTIF_ID = 1001

        /** ٣٠ إطاراً، إطار مفتاحي كل ثانيتين. الدقة ومعدل البت من اختيار المستخدم (Qualities). */
        const val FPS = 30
        const val IFRAME_SEC = 2

        fun start(ctx: Context, resultCode: Int, data: Intent, host: String, port: Int, name: String, screenW: Int, screenH: Int, dpi: Int, pin: String) {
            val i = Intent(ctx, CastService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_SCREEN_W, screenW)
                putExtra(EXTRA_SCREEN_H, screenH)
                putExtra(EXTRA_DPI, dpi)
                putExtra(EXTRA_PIN, pin)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CastService::class.java).apply { action = ACTION_STOP })
        }

        /** يرسل وضع العرض إلى المستقبل: fit / fill / stretch */
        fun setMode(ctx: Context, mode: String) {
            ctx.startService(Intent(ctx, CastService::class.java).apply { action = ACTION_MODE; putExtra(EXTRA_MODE, mode) })
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var worker: Thread? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var socket: Socket? = null
    private var audio: AudioCapture? = null
    private var out: DataOutputStream? = null
    private var dpi = 320
    private var longSide = 1280
    private var bitrate = 5_000_000
    @Volatile private var pendingSize: Pair<Int, Int>? = null
    private var currentSize: Pair<Int, Int>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCasting(intent)
            ACTION_STOP -> stopCasting("أُوقف البث")
            ACTION_MODE -> {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "fit"
                val o = out
                if (running && o != null) Thread { try { o.writeMessage(Message(Protocol.MSG_MODE, mode.toByteArray(Charsets.UTF_8))) } catch (_: Exception) {} }.start()
            }
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCasting(intent: Intent) {
        if (running) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val host = intent.getStringExtra(EXTRA_HOST) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT)
        val name = intent.getStringExtra(EXTRA_NAME) ?: host
        val screenW = intent.getIntExtra(EXTRA_SCREEN_W, 1080)
        val screenH = intent.getIntExtra(EXTRA_SCREEN_H, 1920)
        val dpi = intent.getIntExtra(EXTRA_DPI, 320)
        val pin = intent.getStringExtra(EXTRA_PIN) ?: ""
        if (data == null || host.isEmpty()) {
            stopSelf(); return
        }

        createChannel()
        val notif = buildNotification("جارٍ الاتصال بـ $name…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        running = true
        main.post {
            CastState.reset()
            CastState.connecting = true
            CastState.target = name
            CastState.status = "جارٍ الاتصال بـ $name…"
        }

        worker = Thread({ run(resultCode, data, host, port, name, screenW, screenH, dpi, pin) }, "cast-worker").also { it.start() }
    }

    /** يُرفع عند رفض كلمة السر كي لا تُعاد الدقة ولا يُنسى سبب التوقف. */
    private class AuthException(msg: String) : Exception(msg)

    private fun run(resultCode: Int, data: Intent, host: String, port: Int, name: String, screenW: Int, screenH: Int, dpi: Int, pin: String) {
        var out: DataOutputStream? = null
        this.dpi = dpi
        val q = Qualities.current(this)
        longSide = q.longSide
        bitrate = q.bitrate
        try {
            // ١) الاتصال بالمستقبل عبر الواي فاي وتبادل التعريف
            val realSize = screenSize()
            val (w, h) = encodedSize(realSize?.first ?: screenW, realSize?.second ?: screenH)
            currentSize = w to h
            val s = WifiNet.newSocket(this)
            socket = s
            s.connect(InetSocketAddress(host, port), 5000)
            s.tcpNoDelay = true
            s.soTimeout = 5000
            out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 16))
            this.out = out
            val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            val me = DeviceInfo("${Build.MANUFACTURER} ${Build.MODEL}", Build.MODEL, "sender", Protocol.VERSION, w, h, pin)
            out.writeMessage(Message(Protocol.MSG_HELLO, me.toJson().toByteArray(Charsets.UTF_8)))
            val ack = inp.readMessage()
            if (ack.type == Protocol.MSG_AUTH_FAIL) {
                PinStore.forgetPin(this, host)   // تُطلب من جديد في المرة القادمة
                throw AuthException("كلمة السر غير صحيحة — اضغط «عكس الشاشة» وأدخلها من جديد")
            }
            if (ack.type != Protocol.MSG_HELLO_ACK) throw IllegalStateException("ردّ غير متوقع من المستقبل")
            PinStore.savePin(this, host, pin)
            val peer = DeviceInfo.fromJson(String(ack.payload, Charsets.UTF_8))
            s.soTimeout = 0

            // ٢) المشفّر
            val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_SEC)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L) // الشاشة الثابتة تُرسل إطاراً كل ٠.١ ث
                if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= 26) setInteger(MediaFormat.KEY_LATENCY, 1)
            }
            var enc = MediaCodec.createEncoderByType("video/avc")
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            inputSurface = surface
            enc.start()
            encoder = enc

            // ٣) التقاط الشاشة إلى سطح المشفّر
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data) ?: throw IllegalStateException("تعذر الحصول على إذن التقاط الشاشة")
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCasting("أُوقف التقاط الشاشة من النظام")
                }
            }, main)
            virtualDisplay = mp.createVirtualDisplay(
                "ScreenCaster", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )

            // ٣ب) الصوت: التقاط صوت التشغيل وإرساله مع الفيديو
            val audioState = AudioCapture(this, mp, out).also { audio = it }.start()

            main.post {
                CastState.connecting = false
                CastState.active = true
                CastState.size = "$w × $h — ${bitrate / 1_000_000} ميجابت/ث"
                CastState.audio = audioState
                CastState.status = "يبثّ إلى ${peer.name}"
            }
            updateNotification("جارٍ العكس إلى $name")

            // ٤) حلقة الإرسال
            val info = MediaCodec.BufferInfo()
            var frames = 0L
            var bytesWindow = 0L
            var lastStat = System.currentTimeMillis()
            var lastSent = System.currentTimeMillis()
            val startAt = System.currentTimeMillis()
            while (running) {
                val ps = pendingSize
                if (ps != null && ps != currentSize) {
                    pendingSize = null
                    val (nw, nh) = ps
                    out.writeMessage(Message(Protocol.MSG_RESIZE, DeviceInfo("", "", "sender", Protocol.VERSION, nw, nh).toJson().toByteArray(Charsets.UTF_8)))
                    enc = swapEncoder(nw, nh)
                    continue
                }
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = enc.outputFormat
                        val csd0 = f.getByteBuffer("csd-0")
                        val csd1 = f.getByteBuffer("csd-1")
                        if (csd0 != null) {
                            val a = ByteArray(csd0.remaining()).also { csd0.get(it) }
                            val b = if (csd1 != null) ByteArray(csd1.remaining()).also { csd1.get(it) } else ByteArray(0)
                            out.writeMessage(Message(Protocol.MSG_CONFIG, a + b))
                        }
                    }
                    idx >= 0 -> {
                        val buf = enc.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size).also { buf.get(it) }
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                out.writeMessage(Message(Protocol.MSG_CONFIG, bytes))
                            } else {
                                val key = if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) Protocol.FLAG_KEYFRAME else 0
                                out.writeMessage(Message(Protocol.MSG_FRAME, bytes, key, info.presentationTimeUs))
                                frames++
                                bytesWindow += bytes.size
                                lastSent = System.currentTimeMillis()
                            }
                        }
                        enc.releaseOutputBuffer(idx, false)
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastSent >= 3000) {
                    out.writeMessage(Message(Protocol.MSG_PING, ByteArray(0)))
                    lastSent = now
                }
                if (now - lastStat >= 1000) {
                    val kbps = (bytesWindow * 8 / (now - lastStat)).toInt()
                    bytesWindow = 0
                    lastStat = now
                    val f = frames
                    val sec = (now - startAt) / 1000
                    main.post { CastState.frames = f; CastState.kbps = kbps; CastState.seconds = sec }
                }
            }
            try { out.writeMessage(Message(Protocol.MSG_BYE, ByteArray(0))) } catch (_: Exception) {}
        } catch (e: AuthException) {
            if (running) stopCasting(e.message ?: "كلمة السر غير صحيحة")
        } catch (e: Exception) {
            if (running) {
                var reason = "انقطع البث: ${e.message ?: e.javaClass.simpleName}"
                // أي عطل أثناء دقة أعلى من 720p ← نعود إلى 720p في المرة القادمة
                if (Qualities.resetToDefault(this)) reason += "\nأُعيدت الدقة إلى 720p"
                stopCasting(reason)
            }
        } finally {
            releaseAll()
        }
    }

    /** يحسب مقاس الفيديو: الضلع الأطول بحسب الدقة المختارة مع حفظ النسبة، ومضاعفات ١٦. */
    private fun encodedSize(sw: Int, sh: Int): Pair<Int, Int> {
        val longest = maxOf(sw, sh).toFloat()
        val ls = if (longSide > 0) longSide else longest.toInt()
        val scale = if (longest > ls) ls / longest else 1f
        var w = (sw * scale).toInt() / 16 * 16
        var h = (sh * scale).toInt() / 16 * 16
        if (w < 160) w = 160
        if (h < 160) h = 160
        return w to h
    }

    private fun stopCasting(reason: String) {
        val wasRunning = running
        running = false
        try { socket?.close() } catch (_: Exception) {}
        main.post {
            CastState.reset()
            CastState.status = reason
        }
        if (wasRunning || worker == null) {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun releaseAll() {
        try { audio?.stop() } catch (_: Exception) {}
        audio = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
        main.post {
            if (CastState.active || CastState.connecting) {
                CastState.reset()
                CastState.status = "انتهى البث"
            }
        }
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!running) return
        val (sw, sh) = screenSize() ?: return
        val size = encodedSize(sw, sh)
        if (size != currentSize) pendingSize = size
    }

    /** مقاس الشاشة الحالي (يتغيّر مع الدوران) — يُقرأ من مدير الشاشات لأن الخدمة بلا نافذة. */
    private fun screenSize(): Pair<Int, Int>? = try {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        d.getRealSize(p)
        if (p.x > 0 && p.y > 0) p.x to p.y else null
    } catch (_: Exception) { null }

    /** يبني مشفّراً جديداً بالمقاس المطلوب ويحوّل التقاط الشاشة إليه (بلا إذن جديد). */
    private fun swapEncoder(w: Int, h: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_SEC)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L) // الشاشة الثابتة تُرسل إطاراً كل ٠.١ ث
            if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 26) setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        val enc = MediaCodec.createEncoderByType("video/avc")
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        val vd = virtualDisplay
        if (vd != null) {
            vd.setSurface(null)
            vd.resize(w, h, dpi)
            vd.setSurface(surface)
        }
        val old = encoder
        val oldSurface = inputSurface
        encoder = enc
        inputSurface = surface
        try { old?.stop() } catch (_: Exception) {}
        try { old?.release() } catch (_: Exception) {}
        try { oldSurface?.release() } catch (_: Exception) {}
        currentSize = w to h
        main.post { CastState.size = "$w × $h" }
        return enc
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    // ---------- الإشعار ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "عكس الشاشة", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CastService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        b.setContentTitle(APP_NAME)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_receiver)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_receiver), "إيقاف", stopIntent).build())
        return b.build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
