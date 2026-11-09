package com.example.screencaster

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

/** يفكّ إطارات إتش ٢٦٤ ويعرضها على سطح الشاشة مباشرة. */
class VideoDecoder(private val surface: Surface) {
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()

    fun start(width: Int, height: Int) {
        val w = if (width > 0) width else 1280
        val h = if (height > 0) height else 720
        val format = MediaFormat.createVideoFormat("video/avc", w, h)
        val c = MediaCodec.createDecoderByType("video/avc")
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
    }

    /** يُدخل الإطار بلا إسقاط (ينتظر حتى يتوفر مكان)، ثم يعرض آخر إطار جاهز ويطرح ما قبله لتقليل التأخير. */
    fun feed(data: ByteArray, ptsUs: Long, isConfig: Boolean) {
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
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }
}
