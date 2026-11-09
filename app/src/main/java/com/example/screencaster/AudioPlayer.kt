package com.example.screencaster

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * يشغّل قطع الصوت الخام القادمة من المرسل على مكبّر المستقبل.
 * خيط مستقل مع طابور محدود: إن تراكم الصوت أكثر من نصف ثانية يُطرح الأقدم كي لا يتأخر عن الصورة.
 */
class AudioPlayer(private val sampleRate: Int, private val channels: Int) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val bytesPerSec = sampleRate * channels * 2
    private val maxQueued get() = bytesPerSec / 2 + bytesPerSec * delayMs / 1000 // نصف ثانية + التأخير المطلوب
    @Volatile private var queuedBytes = 0
    @Volatile private var delayMs = 0
    @Volatile private var bytesToDrop = 0

    /** تأخير الصوت: زيادة = حقن صمت بقدر الفرق، نقصان = طرح قطع بقدر الفرق. يُطبّق فوراً بلا قطع. */
    fun setDelay(ms: Int) {
        val target = ms.coerceIn(0, 2000)
        val diff = target - delayMs
        delayMs = target
        if (diff > 0) {
            val silence = ByteArray(bytesPerSec * diff / 1000 / 4 * 4)
            synchronized(queue) { queue.offer(silence); queuedBytes += silence.size }
        } else if (diff < 0) {
            bytesToDrop += bytesPerSec * (-diff) / 1000 / 4 * 4
        }
    }

    fun start(initialDelayMs: Int = 0) {
        delayMs = 0
        val chMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(chMask)
                .build())
            .setBufferSizeInBytes(maxOf(minBuf * 2, bytesPerSec / 5)) // ٢٠٠ مللي ثانية على الأقل
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        running = true
        thread = Thread({ loop(t) }, "audio-play").also { it.start() }
        if (initialDelayMs > 0) setDelay(initialDelayMs)
    }

    fun feed(data: ByteArray) {
        if (!running) return
        if (bytesToDrop > 0) {            // إنقاص التأخير: نطرح قطعاً حتى نستوفي الفرق
            val d = minOf(bytesToDrop, data.size)
            bytesToDrop -= d
            if (d == data.size) return
            return feed(data.copyOfRange(d, data.size))
        }
        synchronized(queue) {
            while (queuedBytes + data.size > maxQueued) {
                val old = queue.poll() ?: break
                queuedBytes -= old.size
            }
            queue.offer(data)
            queuedBytes += data.size
        }
    }

    private fun loop(t: AudioTrack) {
        try {
            while (running) {
                val d = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                synchronized(queue) { queuedBytes -= d.size }
                var off = 0
                while (off < d.size && running) {
                    val n = t.write(d, off, d.size - off)
                    if (n < 0) return
                    off += n
                }
            }
        } catch (_: Exception) {}
    }

    fun release() {
        running = false
        queue.clear()
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }
}
