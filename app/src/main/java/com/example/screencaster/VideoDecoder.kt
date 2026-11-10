package com.example.screencaster

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * يفكّ إطارات إتش ٢٦٤ ويعرضها على سطح الشاشة مباشرة.
 * تأخير الصورة (للقيم السالبة من تزامن الصوت): تُحجز الإطارات المضغوطة في طابور قبل الفاكّ
 * وتُدخل إليه بعد مرور التأخير. ما دام التأخير صفراً ولم يُطلب قط، يبقى المسار المباشر كما كان.
 */
class VideoDecoder(private val surface: Surface) {
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()

    private class Item(val data: ByteArray, val ptsUs: Long, val isConfig: Boolean, val arrivedMs: Long)
    private val pending = LinkedBlockingQueue<Item>(600) // ممتلئ = ينتظر القارئ (لا إسقاط لإطارات مضغوطة)
    @Volatile private var delayMs = 0
    @Volatile private var worker: Thread? = null
    @Volatile private var workerRunning = false

    /** تأخير عرض الصورة بالمللي ثانية (٠ = فوري). يُطبّق فوراً بلا قطع البث. */
    @Synchronized fun setDelay(ms: Int) {
        delayMs = ms.coerceIn(0, 1000)
        if (delayMs > 0 && worker == null && codec != null) {
            workerRunning = true
            worker = Thread({ delayLoop() }, "video-delay").also { it.start() }
        }
    }

    private fun delayLoop() {
        try {
            while (workerRunning) {
                val item = pending.poll(100, TimeUnit.MILLISECONDS) ?: continue
                while (workerRunning) {
                    val wait = item.arrivedMs + delayMs - SystemClock.elapsedRealtime()
                    if (wait <= 0L) break
                    Thread.sleep(minOf(wait, 20L))
                }
                if (workerRunning) feedNow(item.data, item.ptsUs, item.isConfig)
            }
        } catch (_: Exception) {}
    }

    fun start(width: Int, height: Int) {
        val w = if (width > 0) width else 1280
        val h = if (height > 0) height else 720
        val format = MediaFormat.createVideoFormat("video/avc", w, h)
        val c = MediaCodec.createDecoderByType("video/avc")
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
    }

    /** نقطة الدخول: مباشر إن لم يُفعَّل تأخير الصورة، وإلا عبر طابور التأخير (حفاظاً على الترتيب). */
    fun feed(data: ByteArray, ptsUs: Long, isConfig: Boolean) {
        if (worker == null) { feedNow(data, ptsUs, isConfig); return }
        try { pending.put(Item(data, ptsUs, isConfig, SystemClock.elapsedRealtime())) } catch (_: InterruptedException) {}
    }

    /** يُدخل الإطار بلا إسقاط (ينتظر حتى يتوفر مكان)، ثم يعرض آخر إطار جاهز ويطرح ما قبله لتقليل التأخير. */
    private fun feedNow(data: ByteArray, ptsUs: Long, isConfig: Boolean) {
        val c = codec ?: return
        var idx = -1
        var tries = 0
        while (idx < 0 && tries < 50) {
            idx = c.dequeueInputBuffer(20_000)
            if (idx < 0) { drain(c); tries++ }
        }
        if (idx >= 0) {
            val buf = c.getInputBuffer(idx)
            if (buf != null && buf.capacity() >= data.size) {
                buf.clear()
                buf.put(data)
                val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                c.queueInputBuffer(idx, 0, data.size, ptsUs, flags)
            } else {
                c.queueInputBuffer(idx, 0, 0, ptsUs, 0)
            }
        }
        drain(c)
    }

    private fun drain(c: MediaCodec) {
        var last = -1
        while (true) {
            val out = c.dequeueOutputBuffer(info, 0)
            if (out >= 0) {
                if (last >= 0) c.releaseOutputBuffer(last, false)
                last = out
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue
            } else {
                break
            }
        }
        if (last >= 0) c.releaseOutputBuffer(last, true)
    }

    fun release() {
        workerRunning = false
        pending.clear()
        worker?.let { t -> try { t.interrupt(); t.join(1500) } catch (_: Exception) {} }
        worker = null
        pending.clear()
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }
}
