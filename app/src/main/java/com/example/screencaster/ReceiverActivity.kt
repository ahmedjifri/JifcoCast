package com.example.screencaster

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.example.screencaster.shared.DeviceInfo
import com.example.screencaster.shared.NetUtils
import com.example.screencaster.shared.Protocol

/** شاشة المستقبل: تعرض الحالة وتنتظر المرسل، وعند بدء البث تعرض الفيديو ملء الشاشة. */
class ReceiverActivity : Activity(), TextureView.SurfaceTextureListener {

    /** سطح العرض ثابت الحجم على الشاشة كلها؛ الملاءمة/الملء/التمديد بمصفوفة تحويل لا تمسّ السطح. */
    private lateinit var textureView: TextureView
    private var surface: Surface? = null
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private val main = Handler(Looper.getMainLooper())

    private var server: ReceiverServer? = null
    private var advertiser: NsdAdvertiser? = null
    private var surfaceReady = false
    private var videoW = 0
    private var videoH = 0
    private var streaming = false
    private lateinit var backButton: Button
    private lateinit var pinButton: Button
    private lateinit var idleBg: View
    private lateinit var modeText: TextView

    /** وضع العرض: fit (ملاءمة) / fill (ملء) / stretch (تمديد) — يُحفظ آخر اختيار. */
    private var mode: String
        get() = getSharedPreferences("aakes", MODE_PRIVATE).getString("display_mode", "fit") ?: "fit"
        set(v) { getSharedPreferences("aakes", MODE_PRIVATE).edit().putString("display_mode", v).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // طبقة الانتظار: خلفية كحلية تغطي آخر إطار، وداخلها برواز فاتح بحدّ ذهبي فيه البيانات
        idleBg = View(this).apply { setBackgroundColor(0xFF0F1F3D.toInt()) }
        root.addView(idleBg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        statusText = TextView(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF6F3EA.toInt()); cornerRadius = 48f; setStroke(8, 0xFFC9A227.toInt())
            }
            setTextColor(0xFF111111.toInt())
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(statusText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(40, 40, 40, 40) })

        logText = TextView(this).apply {
            setTextColor(0xFF555555.toInt())
            textSize = 12f
            gravity = Gravity.START or Gravity.BOTTOM
            setPadding(24, 24, 24, 24)
        }
        root.addView(logText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(60, 0, 60, 60) })

        modeText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 20f
            setPadding(32, 16, 32, 16)
            visibility = View.GONE
        }
        root.addView(modeText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(24, 24, 24, 24) })

        backButton = Button(this).apply {
            text = "الرئيسية"
            setOnClickListener { finish() }
            isFocusable = true
            isFocusableInTouchMode = false
            val normal = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFE0E0E0.toInt()); cornerRadius = 24f }
            val focusedBg = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFE0E0E0.toInt()); cornerRadius = 24f; setStroke(10, 0xFFFFD600.toInt()) }
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusedBg)
                addState(intArrayOf(), normal)
            }
            setTextColor(Color.BLACK)
            setPadding(40, 20, 40, 20)
        }
        root.addView(backButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(60, 60, 60, 60) })

        pinButton = Button(this).apply {
            text = "تغيير كلمة السر"
            setOnClickListener { showChangePinDialog() }
            isFocusable = true
            isFocusableInTouchMode = false
            val normal = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFE0E0E0.toInt()); cornerRadius = 24f }
            val focusedBg = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFE0E0E0.toInt()); cornerRadius = 24f; setStroke(10, 0xFFFFD600.toInt()) }
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusedBg)
                addState(intArrayOf(), normal)
            }
            setTextColor(Color.BLACK)
            setPadding(40, 20, 40, 20)
        }
        root.addView(pinButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(60, 60, 60, 60) })

        setContentView(root)
        textureView.surfaceTextureListener = this
        showIdle()
    }

    private fun showIdle() {
        val port = server?.port ?: Protocol.DEFAULT_PORT
        statusText.text = "Jifco Cast - Receiver\nReady to Receive\n\n" +
            "الجهاز: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "العنوان: ${NetUtils.localIpv4() ?: "غير معروف"} : $port\n\n" +
            "افتح «Jifco Cast - Sender» على الجوال أو الحاسب واضغط «ابحث عن المستقبلات»\n" +
            "محمي بكلمة سر" + (if (PinStore.receiverPin(this) == Protocol.DEFAULT_PIN) " (الافتراضية 1234 — غيّرها من الزر أعلى اليسار)" else "") + "\n\n" +
            "النسخة ${Protocol.VERSION}\n\nأثناء البث: أزرار ◀ ▶ في الريموت تبدّل وضع العرض (ملاءمة / ملء / تمديد)"
        idleBg.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        logText.visibility = View.VISIBLE
        backButton.visibility = View.VISIBLE
        pinButton.visibility = View.VISIBLE
        streaming = false
        // إخفاء طبقة الفيديو وإجبار الشاشة على إعادة الرسم فوراً (بعض الأجهزة لا تعيد الرسم إلا بحدث)
        textureView.alpha = 0f
        idleBg.bringToFront()
        statusText.bringToFront()
        logText.bringToFront()
        backButton.bringToFront()
        pinButton.bringToFront()
        statusText.invalidate()
        window.decorView.postInvalidate()
        main.postDelayed({ statusText.invalidate(); window.decorView.postInvalidate() }, 300)
    }

    /** تغيير كلمة السر: القديمة ثم الجديدة مرتين (أرقام فقط، ٤ خانات فأكثر). */
    private fun showChangePinDialog() {
        val wrap = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        fun field(hint: String) = android.widget.EditText(this).apply {
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            wrap.addView(this)
        }
        val old = field("كلمة السر الحالية")
        val new1 = field("الكلمة الجديدة (أرقام، ٤ فأكثر)")
        val new2 = field("أعد الكلمة الجديدة")
        android.app.AlertDialog.Builder(this)
            .setTitle("تغيير كلمة السر")
            .setView(wrap)
            .setPositiveButton("حفظ") { _, _ ->
                val o = old.text.toString(); val a = new1.text.toString(); val b = new2.text.toString()
                val msg = when {
                    o != PinStore.receiverPin(this) -> "كلمة السر الحالية غير صحيحة"
                    a.length < 4 || !a.all { it.isDigit() } -> "الكلمة الجديدة يجب أن تكون أرقاماً، ٤ خانات فأكثر"
                    a != b -> "الكلمتان الجديدتان غير متطابقتين"
                    else -> { PinStore.setReceiverPin(this, a); "تم تغيير كلمة السر" }
                }
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                if (!streaming) showIdle()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun modeLabel(m: String) = when (m) { "fill" -> "ملء الشاشة (قصّ الأطراف)"; "stretch" -> "تمديد"; else -> "ملاءمة" }

    private fun showModeHint() {
        modeText.text = "وضع العرض: ${modeLabel(mode)}"
        modeText.visibility = View.VISIBLE
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint, 2000)
    }
    private val hideHint = Runnable { modeText.visibility = View.GONE }

    /** تأخير الصوت (مللي ثانية) — يُحفظ ويُطبّق على كل بث. */
    private var audioDelay: Int
        get() = getSharedPreferences("aakes", MODE_PRIVATE).getInt("audio_delay", 0)
        set(v) = getSharedPreferences("aakes", MODE_PRIVATE).edit().putInt("audio_delay", v.coerceIn(0, 2000)).apply()

    private fun changeAudioDelay(delta: Int, fromSender: Boolean = false, absolute: Int? = null) {
        val v = (absolute ?: (audioDelay + delta)).coerceIn(0, 2000)
        audioDelay = v
        if (!fromSender) server?.applyAudioDelay(v)
        modeText.text = "تأخير الصوت: $v مللي ثانية"
        modeText.visibility = View.VISIBLE
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint, 2500)
    }

    private fun cycleMode(forward: Boolean) {
        val order = listOf("fit", "fill", "stretch")
        val i = order.indexOf(mode).coerceAtLeast(0)
        mode = order[(i + (if (forward) 1 else order.size - 1)) % order.size]
        fitSurface(videoW, videoH)
        showModeHint()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (streaming) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_MENU -> { cycleMode(true); return true }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_UP -> { cycleMode(false); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { changeAudioDelay(+50); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { changeAudioDelay(-50); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun log(s: String) {
        main.post {
            val old = logText.text.toString()
            val lines = (old.split("\n") + s).filter { it.isNotBlank() }.takeLast(4)
            logText.text = lines.joinToString("\n")
        }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        surfaceReady = true
        startServer()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        applyTransform()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        surfaceReady = false
        stopServer()
        surface?.release(); surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun startServer() {
        if (server != null) return
        val dm = resources.displayMetrics
        val s = ReceiverServer(
            pinProvider = { PinStore.receiverPin(this) },
            audioDelayProvider = { audioDelay },
            surfaceProvider = { if (surfaceReady) surface else null },
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            listener = object : ReceiverServer.Listener {
                override fun onStatus(text: String) {
                    main.post { statusText.text = text; statusText.visibility = View.VISIBLE }
                }
                override fun onConnected(peer: DeviceInfo) {
                    main.post { fitSurface(peer.width, peer.height) }
                }
                override fun onStreaming(peer: DeviceInfo) {
                    main.post {
                        streaming = true
                        textureView.alpha = 1f
                        idleBg.visibility = View.GONE
                        statusText.visibility = View.GONE
                        logText.visibility = View.GONE
                        backButton.visibility = View.GONE
                        pinButton.visibility = View.GONE
                        fitSurface(peer.width, peer.height)
                    }
                    log("بدأ البث من ${peer.name}")
                }
                override fun onResize(width: Int, height: Int) {
                    main.post { fitSurface(width, height) }
                }
                override fun onMode(m: String) {
                    main.post { mode = m; fitSurface(videoW, videoH); showModeHint() }
                }
                override fun onAudio(ok: Boolean) {
                    log(if (ok) "الصوت: يعمل (تأخير $audioDelay مللي ثانية)" else "الصوت: تعذر تشغيله")
                }
                override fun onAudioDelay(ms: Int) {
                    main.post { changeAudioDelay(0, fromSender = true, absolute = ms) }
                }
                override fun onIdle() {
                    main.post {
                        videoW = 0; videoH = 0
                        textureView.setTransform(Matrix())
                        showIdle()
                    }
                    log("انتهى الاتصال — في الانتظار")
                }
            }
        )
        val port = s.start()
        server = s
        log("الخادم يستمع على المنفذ $port")
        advertiser = NsdAdvertiser(this, ::log).also { it.register(port) }
        showIdle()
    }

    /** يسجّل مقاس فيديو المرسل ويطبّق وضع العرض عليه. */
    private fun fitSurface(vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0) return
        videoW = vw; videoH = vh
        applyTransform()
    }

    /**
     * يحوّل محتوى السطح (الممدود افتراضياً على الشاشة كلها) إلى الوضع المطلوب:
     * ملاءمة = أكبر حجم يُظهر الصورة كاملة؛ ملء = يغطي الشاشة مع قصّ؛ تمديد = بلا تحويل.
     */
    private fun applyTransform() {
        val vw = videoW; val vh = videoH
        if (vw <= 0 || vh <= 0) return
        val sw = textureView.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val sh = textureView.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        val m = Matrix()
        if (mode != "stretch") {
            val scale = if (mode == "fill") maxOf(sw / vw, sh / vh) else minOf(sw / vw, sh / vh)
            val w = vw * scale
            val h = vh * scale
            m.setScale(w / sw, h / sh)
            m.postTranslate((sw - w) / 2f, (sh - h) / 2f)
        }
        textureView.setTransform(m)
        textureView.invalidate()
    }

    private fun stopServer() {
        advertiser?.unregister(); advertiser = null
        server?.stop(); server = null
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
