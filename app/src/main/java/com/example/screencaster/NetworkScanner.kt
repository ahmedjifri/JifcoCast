package com.example.screencaster

import android.content.Context
import android.os.Handler
import android.os.Looper
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

/**
 * ينسّق المكتشفات كلها (إم دي إن إس + إس إس دي بي)، ويدمج النتائج حسب عنوان الجهاز،
 * ويعيد كل التحديثات على الخيط الرئيس كي تستهلكها الواجهة مباشرة.
 */
class NetworkScanner(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())

    private val devices = LinkedHashMap<String, DiscoveredDevice>() // المفتاح: عنوان الجهاز
    private val raw = ArrayList<Map<String, String>>()

    private var nsd: NsdScanner? = null
    private var ssdp: SsdpScanner? = null
    private var onUpdate: ((List<DiscoveredDevice>) -> Unit)? = null
    private var doneRunnable: Runnable? = null

    fun rawSsdp(): List<Map<String, String>> = synchronized(raw) { ArrayList(raw) }

    fun scan(
        durationMs: Long = 7000L,
        onUpdate: (List<DiscoveredDevice>) -> Unit,
        onLog: (String) -> Unit,
        onDone: () -> Unit
    ) {
        stop()
        synchronized(devices) { devices.clear() }
        synchronized(raw) { raw.clear() }
        this.onUpdate = onUpdate

        val log: (String) -> Unit = { s -> main.post { onLog(s) } }

        nsd = NsdScanner(ctx, ::add, log).also { it.start() }
        ssdp = SsdpScanner(ctx, ::add, { h -> synchronized(raw) { raw.add(h) } }, log)
            .also { it.start(durationMs - 1500L) }
        log("بدأ البحث: إم دي إن إس (عاكس، كروم كاست، إير بلاي) + إس إس دي بي (يو بي إن بي)")

        val r = Runnable {
            stop()
            onDone()
        }
        doneRunnable = r
        main.postDelayed(r, durationMs)
    }

    fun stop() {
        doneRunnable?.let { main.removeCallbacks(it) }
        doneRunnable = null
        nsd?.stop(); nsd = null
        ssdp?.stop(); ssdp = null
    }

    private fun add(d: DiscoveredDevice) {
        val snapshot: List<DiscoveredDevice>
        synchronized(devices) {
            val existing = devices[d.host]
            if (existing == null) {
                devices[d.host] = d
            } else if (d.kind.priority < existing.kind.priority) {
                devices[d.host] = d.merge(existing)
            } else {
                devices[d.host] = existing.merge(d)
            }
            snapshot = ArrayList(devices.values)
        }
        main.post { onUpdate?.invoke(snapshot) }
    }

    /** يجرّب الاتصال بمستقبل عاكس: تعريف ← ردّ تعريف ← وداع. */
    fun probeReceiver(dev: DiscoveredDevice, callback: (String) -> Unit) {
        Thread({
            val result = try {
                WifiNet.newSocket(ctx).use { s ->
                    s.connect(InetSocketAddress(dev.host, dev.port), 3000)
                    s.soTimeout = 3000
                    if (dev.kind != DeviceKind.OURS) {
                        return@use "✓ المنفذ ${dev.port} يستجيب — الاتصال المباشر بهذا الجهاز يعمل"
                    }
                    val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                    val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
                    out.writeMessage(Message(Protocol.MSG_HELLO, Capabilities.me(ctx).toJson().toByteArray(Charsets.UTF_8)))
                    val ack = inp.readMessage()
                    out.writeMessage(Message(Protocol.MSG_BYE, ByteArray(0)))
                    if (ack.type == Protocol.MSG_HELLO_ACK) {
                        val info = DeviceInfo.fromJson(String(ack.payload, Charsets.UTF_8))
                        "✓ متصل — ${info.name} (نسخة المستقبل ${info.version})"
                    } else {
                        "✗ ردّ غير متوقع (نوع ${ack.type})"
                    }
                }
            } catch (e: Exception) {
                "✗ فشل الاتصال عبر ${WifiNet.defaultTransport(ctx)}: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post { callback(result) }
        }, "probe-receiver").start()
    }
}
