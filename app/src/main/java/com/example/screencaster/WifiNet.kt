package com.example.screencaster

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Socket

/**
 * يختار شبكة الواي فاي صراحةً ويُنشئ المقابس عبرها،
 * حتى لا يحوّل أندرويد اتصالاتنا المحلية إلى شبكة الجوال حين تكون الواي فاي بلا إنترنت.
 */
object WifiNet {

    fun wifiNetwork(ctx: Context): Network? {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull { n ->
                val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** الشبكة الافتراضية الحالية: "واي فاي" أو "بيانات الجوال" أو غيرها — للتشخيص. */
    fun defaultTransport(ctx: Context): String {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return "لا شبكة"
        val c = cm.getNetworkCapabilities(net) ?: return "غير معروفة"
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "واي فاي"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "شبكة سلكية"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "بيانات الجوال"
            c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "في بي إن"
            else -> "أخرى"
        }
    }

    /** مقبس مربوط بشبكة الواي فاي إن وُجدت، وإلا مقبس عادي. */
    fun newSocket(ctx: Context): Socket {
        val net = wifiNetwork(ctx) ?: return Socket()
        return try {
            net.socketFactory.createSocket()
        } catch (_: Exception) {
            Socket()
        }
    }
}
