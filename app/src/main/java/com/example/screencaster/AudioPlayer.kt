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
    private val maxQueued = bytesPerSec / 2 // نصف ثانية
    @Volatile private var queuedBytes = 0

    fun start() {
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
    }

    fun feed(data: ByteArray) {
        if (!running) return
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
