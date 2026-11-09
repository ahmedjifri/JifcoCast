package com.example.screencaster

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * اكتشاف الأجهزة عبر إس إس دي بي (يو بي إن بي / دي إل إن إيه):
 * التلفزيونات الذكية، والراوترات، ومشغّلات الوسائط، وكروم كاست أيضاً يستجيب له.
 */
class SsdpScanner(
    private val ctx: Context,
    private val onDevice: (DiscoveredDevice) -> Unit,
    private val onRaw: (Map<String, String>) -> Unit,
    private val onLog: (String) -> Unit
) {
    @Volatile private var running = false

    fun start(durationMs: Long) {
        running = true
        Thread({ run(durationMs) }, "ssdp-scan").start()
    }

    fun stop() {
        running = false
    }

    private fun run(durationMs: Long) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("screencaster-ssdp")
        lock.setReferenceCounted(false)
        try { lock.acquire() } catch (_: Exception) {}

        val seenLocation = HashSet<String>()
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 700
                val group = InetAddress.getByName("239.255.255.250")
                val msearch = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: ssdp:all\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                repeat(2) {
                    sock.send(DatagramPacket(msearch, msearch.size, group, 1900))
                }

                val end = System.currentTimeMillis() + durationMs
                val buf = ByteArray(8192)
                while (running && System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(p.data, 0, p.length, Charsets.ISO_8859_1)
                    val headers = parseHeaders(text)
                    val from = p.address?.hostAddress ?: continue
                    headers["from"] = from
                    headers["source"] = "SSDP"
                    val loc = headers["LOCATION"] ?: continue
                    if (!seenLocation.add(loc)) continue

                    headers.putAll(fetchDescription(loc))
                    onRaw(headers)
                    onDevice(toDevice(headers, from, loc))
                }
            }
        } catch (e: Exception) {
            onLog("خطأ في بحث إس إس دي بي: ${e.message}")
        } finally {
            try { lock.release() } catch (_: Exception) {}
        }
    }

    private fun parseHeaders(text: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        val lines = text.split("\r\n", "\n")
        if (lines.isNotEmpty()) map["status"] = lines[0].trim()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            val k = line.substring(0, i).trim().uppercase()
            val v = line.substring(i + 1).trim()
            if (k.isNotEmpty()) map[k] = v
        }
        return map
    }

    /** يجلب ملف وصف الجهاز (XML) ويستخرج الاسم والشركة والطراز والنوع. */
    private fun fetchDescription(location: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val conn = URL(location).openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val xml = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            for (tag in listOf("friendlyName", "manufacturer", "modelName", "modelNumber", "deviceType", "UDN", "presentationURL")) {
                val m = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)
                if (m != null) out[tag] = m.groupValues[1].trim()
            }
            val services = Regex("<serviceType>(.*?)</serviceType>").findAll(xml).map { it.groupValues[1].trim() }.toList()
            if (services.isNotEmpty()) out["services"] = services.joinToString(" | ")
        } catch (e: Exception) {
            out["descriptionError"] = e.message ?: e.javaClass.simpleName
        }
        return out
    }

    private fun toDevice(h: Map<String, String>, host: String, location: String): DiscoveredDevice {
        val name = h["friendlyName"]?.takeIf { it.isNotBlank() }
            ?: h["SERVER"]?.takeIf { it.isNotBlank() }
            ?: host
        val typeText = (h["deviceType"] ?: "") + " " + (h["ST"] ?: "") + " " + (h["services"] ?: "")
        val kind = if (typeText.contains("MediaRenderer", ignoreCase = true)) DeviceKind.DLNA else DeviceKind.OTHER
        val port = try {
            val u = URL(location)
            if (u.port > 0) u.port else 80
        } catch (_: Exception) {
            80
        }
        return DiscoveredDevice("$host:$port", name, kind, host, port, h)
    }
}
