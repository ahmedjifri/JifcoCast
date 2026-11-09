package com.example.screencaster

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.screencaster.shared.Protocol

/**
 * اكتشاف الأجهزة عبر إم دي إن إس (خدمة اكتشاف الشبكة في أندرويد):
 * مستقبل عاكس، وأجهزة كروم كاست/جوجل تي في، وأجهزة إير بلاي.
 */
class NsdScanner(
    ctx: Context,
    private val onDevice: (DiscoveredDevice) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val types = linkedMapOf(
        Protocol.SERVICE_TYPE to DeviceKind.OURS,
        "_googlecast._tcp." to DeviceKind.CAST,
        "_airplay._tcp." to DeviceKind.AIRPLAY
    )

    private val listeners = ArrayList<NsdManager.DiscoveryListener>()
    private val queue = ArrayDeque<Pair<NsdServiceInfo, DeviceKind>>()
    private var resolving = false
    private val seen = HashSet<String>()
    @Volatile private var stopped = false

    fun start() {
        stopped = false
        for ((type, kind) in types) {
            val l = makeListener(kind)
            listeners += l
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
            } catch (e: Exception) {
                onLog("تعذر بدء اكتشاف $type: ${e.message}")
            }
        }
    }

    fun stop() {
        stopped = true
        for (l in listeners) {
            try { nsd.stopServiceDiscovery(l) } catch (_: Exception) {}
        }
        listeners.clear()
    }

    private fun makeListener(kind: DeviceKind) = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            onLog("فشل بدء الاكتشاف $serviceType (رمز $errorCode)")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onDiscoveryStarted(serviceType: String) {
            onLog("بدأ اكتشاف $serviceType")
        }
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            onLog("وُجدت خدمة: ${serviceInfo.serviceName} (${kind.label})")
            synchronized(queue) { queue.addLast(serviceInfo to kind) }
            pump()
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
    }

    private fun pump() {
        if (stopped) return
        val next = synchronized(queue) {
            if (resolving) return
            val n = queue.removeFirstOrNull() ?: return
            resolving = true
            n
        }
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(next.first, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    onLog("تعذر تحليل ${serviceInfo.serviceName} (رمز $errorCode)")
                    done()
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    handle(serviceInfo, next.second)
                    done()
                }
            })
        } catch (e: Exception) {
            onLog("خطأ في التحليل: ${e.message}")
            done()
        }
    }

    private fun done() {
        synchronized(queue) { resolving = false }
        pump()
    }

    private fun handle(info: NsdServiceInfo, kind: DeviceKind) {
        val host = info.host?.hostAddress ?: return
        val port = info.port
        val details = LinkedHashMap<String, String>()
        details["serviceName"] = info.serviceName ?: ""
        details["serviceType"] = info.serviceType ?: ""
        details["source"] = "mDNS"
        try {
            for ((k, v) in info.attributes) {
                details["txt.$k"] = if (v == null) "" else String(v, Charsets.UTF_8)
            }
        } catch (_: Exception) {}

        val friendly = details["txt.fn"]?.takeIf { it.isNotBlank() }
            ?: details["txt.name"]?.takeIf { it.isNotBlank() }
            ?: info.serviceName ?: host
        val id = "$host:$port"
        if (seen.add(id)) {
            onDevice(DiscoveredDevice(id, friendly, kind, host, port, details))
        }
    }
}
