package com.example.screencaster.shared

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * بروتوكول «عاكس» بين المرسل (الجوال) والمستقبل (التلفاز).
 * رسالة واحدة على الشبكة = [magic:int][type:byte][flags:int][pts:long][len:int][payload:len bytes]
 */
object Protocol {
    const val VERSION = "0.7.5"

    /** نوع الخدمة الذي يعلن به المستقبل عن نفسه على الشبكة (إم دي إن إس). */
    const val SERVICE_TYPE = "_screencaster._tcp."

    /** المنفذ الافتراضي للمستقبل. إن كان مشغولاً يختار المستقبل منفذاً آخر ويعلنه. */
    const val DEFAULT_PORT = 47337

    const val MAGIC = 0x53435231 // "SCR1"

    const val MSG_HELLO: Byte = 1      // المرسل → المستقبل: تعريف (DeviceInfo json)
    const val MSG_HELLO_ACK: Byte = 2  // المستقبل → المرسل: تعريف (DeviceInfo json)
    const val MSG_CONFIG: Byte = 3     // المرسل → المستقبل: إعدادات المشفّر (SPS/PPS)
    const val MSG_FRAME: Byte = 4      // المرسل → المستقبل: إطار فيديو إتش ٢٦٤
    const val MSG_PING: Byte = 5
    const val MSG_PONG: Byte = 6
    const val MSG_BYE: Byte = 7
    const val MSG_RESIZE: Byte = 8     // المرسل → المستقبل: تغيّر مقاس الفيديو (json: width,height)
    const val MSG_MODE: Byte = 9       // المرسل → المستقبل: وضع العرض ("fit" | "fill" | "stretch")
    const val MSG_AUDIO_CONFIG: Byte = 10 // المرسل → المستقبل: إعداد الصوت (json: sampleRate, channels)
    const val MSG_AUDIO: Byte = 11     // المرسل → المستقبل: قطعة صوت خام بي سي إم ١٦ بت
    const val MSG_AUTH_FAIL: Byte = 12 // المستقبل → المرسل: كلمة السر غير صحيحة (ثم يُغلق الاتصال)
    const val MSG_AUDIO_DELAY: Byte = 13 // المرسل → المستقبل: تزامن الصوت بالمللي ثانية (نص رقمي موقّع: موجب يؤخّر الصوت، سالب يؤخّر الصورة)
    const val SYNC_MIN_MS = -1000
    const val SYNC_MAX_MS = 2000
    const val SYNC_STEP_MS = 50

    /** كلمة السر الافتراضية للمستقبل. */
    const val DEFAULT_PIN = "1234"

    /** إعدادات الصوت الافتراضية: ٤٨ كيلوهرتز، ستيريو، ١٦ بت. */
    const val AUDIO_SAMPLE_RATE = 48000
    const val AUDIO_CHANNELS = 2

    const val FLAG_KEYFRAME = 1

    const val MAX_PAYLOAD = 8 * 1024 * 1024
}

class Message(
    val type: Byte,
    val payload: ByteArray,
    val flags: Int = 0,
    val timestampUs: Long = 0L
)

fun DataOutputStream.writeMessage(m: Message) {
    synchronized(this) {
        writeInt(Protocol.MAGIC)
        writeByte(m.type.toInt())
        writeInt(m.flags)
        writeLong(m.timestampUs)
        writeInt(m.payload.size)
        write(m.payload)
        flush()
    }
}

fun DataInputStream.readMessage(): Message {
    val magic = readInt()
    if (magic != Protocol.MAGIC) throw IOException("bad magic: $magic")
    val type = readByte()
    val flags = readInt()
    val pts = readLong()
    val len = readInt()
    if (len < 0 || len > Protocol.MAX_PAYLOAD) throw IOException("bad length: $len")
    val buf = ByteArray(len)
    readFully(buf)
    return Message(type, buf, flags, pts)
}

/** تعريف الجهاز الذي يتبادله الطرفان عند بدء الاتصال. */
class DeviceInfo(
    val name: String,
    val model: String,
    val role: String,       // "sender" أو "receiver"
    val version: String,
    val width: Int,
    val height: Int,
    val pin: String = ""   // كلمة سر المستقبل (يرسلها المرسل في التعريف فقط)
) {
    fun toJson(): String = JSONObject()
        .put("name", name)
        .put("model", model)
        .put("role", role)
        .put("version", version)
        .put("width", width)
        .put("height", height)
        .put("pin", pin)
        .toString()

    companion object {
        fun fromJson(s: String): DeviceInfo {
            val o = JSONObject(s)
            return DeviceInfo(
                name = o.optString("name", "?"),
                model = o.optString("model", ""),
                role = o.optString("role", ""),
                version = o.optString("version", ""),
                width = o.optInt("width", 0),
                height = o.optInt("height", 0),
                pin = o.optString("pin", "")
            )
        }
    }
}
