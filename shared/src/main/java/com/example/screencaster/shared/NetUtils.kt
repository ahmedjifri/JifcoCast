package com.example.screencaster.shared

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** عنوان الجهاز على الشبكة المحلية (يفضّل واجهة الواي فاي). */
    fun localIpv4(): String? {
        var fallback: String? = null
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (nif.name.startsWith("wlan")) return ip
                        if (fallback == null) fallback = ip
                    }
                }
            }
        } catch (_: Exception) {
        }
        return fallback
    }
}
