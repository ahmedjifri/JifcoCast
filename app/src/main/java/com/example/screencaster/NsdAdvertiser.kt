package com.example.screencaster

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.example.screencaster.shared.Protocol

/** يعلن عن المستقبل على الشبكة كي يجده تطبيق الجوال. */
class NsdAdvertiser(ctx: Context, private val onLog: (String) -> Unit) {
    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register(port: Int) {
        unregister()
        val model = Build.MODEL.replace(Regex("[^A-Za-z0-9\\-]"), "").ifEmpty { "TV" }
        val info = NsdServiceInfo().apply {
            serviceName = "ScreenCaster-$model"
            serviceType = Protocol.SERVICE_TYPE
            this.port = port
            setAttribute("fn", "Jifco Cast على ${Build.MANUFACTURER} ${Build.MODEL}")
            setAttribute("md", Build.MODEL)
            setAttribute("v", Protocol.VERSION)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                onLog("أُعلن عن الخدمة: ${serviceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onLog("فشل الإعلان عن الخدمة (رمز $errorCode)")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            onLog("خطأ في الإعلان: ${e.message}")
        }
    }

    fun unregister() {
        val l = listener ?: return
        try { nsd.unregisterService(l) } catch (_: Exception) {}
        listener = null
    }
}
