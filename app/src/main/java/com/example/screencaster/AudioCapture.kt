package com.example.screencaster

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import com.example.screencaster.shared.Message
import com.example.screencaster.shared.Protocol
import com.example.screencaster.shared.writeMessage
import java.io.DataOutputStream
import org.json.JSONObject

/**
 * يلتقط صوت التشغيل (ما تصدره التطبيقات، لا الميكروفون) عبر إذن التقاط الشاشة نفسه
 * ويرسله خاماً (بي سي إم ١٦ بت) بقطع صغيرة كل ٢٠ مللي ثانية. أندرويد ١٠ فما فوق.
 */
@android.annotation.SuppressLint("MissingPermission")
class AudioCapture(private val ctx: Context, private val projection: MediaProjection, private val out: DataOutputStream) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** يعيد وصفاً للحالة: "يعمل" أو سبب التعطّل. */
    fun start(): String {
        if (Build.VERSION.SDK_INT < 29) return "يحتاج أندرويد ١٠ أو أحدث"
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return "لم يُمنح إذن الصوت"
        return try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Protocol.AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(Protocol.AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf * 2, 32 * 1024))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return "تعذر تهيئة الالتقاط" }
            record = rec
            out.writeMessage(Message(Protocol.MSG_AUDIO_CONFIG, JSONObject()
                .put("sampleRate", Protocol.AUDIO_SAMPLE_RATE)
                .put("channels", Protocol.AUDIO_CHANNELS)
                .toString().toByteArray(Charsets.UTF_8)))
            rec.startRecording()
            running = true
            thread = Thread({ loop(rec) }, "audio-capture").also { it.start() }
            "يعمل"
        } catch (e: Exception) {
            "تعذر: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun loop(rec: AudioRecord) {
        // قطعة كل ٢٠ مللي ثانية: ٤٨٠٠٠ × ٢ قناة × ٢ بايت × ٠.٠٢ = ٣٨٤٠ بايت
        val chunk = ByteArray(Protocol.AUDIO_SAMPLE_RATE * Protocol.AUDIO_CHANNELS * 2 / 50)
        var pts = 0L
        val usPerChunk = 20_000L
        try {
            while (running) {
                var got = 0
                while (got < chunk.size && running) {
                    val n = rec.read(chunk, got, chunk.size - got)
                    if (n <= 0) break
                    got += n
                }
                if (got <= 0) continue
                val payload = if (got == chunk.size) chunk.copyOf() else chunk.copyOf(got)
                out.writeMessage(Message(Protocol.MSG_AUDIO, payload, 0, pts))
                pts += usPerChunk
            }
        } catch (_: Exception) {
            // انقطع الاتصال — تتولى الخدمة الإيقاف
        }
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
    }
}
