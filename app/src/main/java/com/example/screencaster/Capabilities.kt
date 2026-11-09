package com.example.screencaster

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.example.screencaster.shared.DeviceInfo
import com.example.screencaster.shared.NetUtils
import com.example.screencaster.shared.Protocol

class CapabilityRow(val item: String, val value: String, val verdict: String)

/** يفحص إمكانات هذا الجهاز (المرسل) ويحكم على كل بند. */
object Capabilities {

    fun collect(ctx: Context): List<CapabilityRow> {
        val rows = ArrayList<CapabilityRow>()
        val pm = ctx.packageManager

        rows += CapabilityRow("الجهاز", "${Build.MANUFACTURER} ${Build.MODEL}", "")

        rows += CapabilityRow(
            "إصدار أندرويد",
            "${Build.VERSION.RELEASE} (واجهة ${Build.VERSION.SDK_INT})",
            if (Build.VERSION.SDK_INT >= 29) "حديث — كل الميزات متاحة" else "أقدم من أندرويد ١٠ — التقاط الشاشة متاح مع بعض القيود"
        )

        val wifi = isOnWifi(ctx)
        rows += CapabilityRow(
            "الاتصال بالواي فاي",
            if (wifi) "متصل" else "غير متصل",
            if (wifi) "جاهز للبحث" else "يلزم الاتصال بشبكة الواي فاي نفسها التي عليها الشاشة"
        )

        rows += CapabilityRow("عنوان الجهاز على الشبكة", NetUtils.localIpv4() ?: "غير معروف", "")

        rows += CapabilityRow(
            "بوابة الشبكة (الموجّه الذي يخدم هذا الجهاز)",
            gateway(ctx) ?: "غير معروفة",
            "إن اختلفت البوابة بين جهازين على الشبكة نفسها فأحدهما خلف مقوٍّ/عقدة ميش"
        )

        val def = WifiNet.defaultTransport(ctx)
        rows += CapabilityRow(
            "الشبكة الافتراضية للنظام",
            def,
            if (def == "واي فاي" || def == "شبكة سلكية") "" else "النظام يفضّل $def — التطبيق يُجبر اتصالاته على الواي فاي، ويُستحسن إطفاء البيانات أثناء العكس"
        )

        val p2p = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        rows += CapabilityRow(
            "واي فاي دايركت (ميراكاست)",
            if (p2p) "مدعوم" else "غير مدعوم",
            if (p2p) "عكس الشاشة بميراكاست ممكن من إعدادات النظام" else "ميراكاست غير متاح على هذا الجهاز"
        )

        val gms = hasPackage(pm, "com.google.android.gms")
        rows += CapabilityRow(
            "خدمات جوجل بلاي",
            if (gms) "موجودة" else "غير موجودة",
            if (gms) "البث الرسمي إلى كروم كاست ممكن (مرحلة لاحقة)" else "البث الرسمي إلى كروم كاست غير ممكن — يُستعمل مستقبل عاكس"
        )

        val enc = hasAvcEncoder()
        rows += CapabilityRow(
            "مشفّر فيديو إتش ٢٦٤",
            if (enc) "متاح" else "غير متاح",
            if (enc) "البث من التطبيق ممكن" else "لا يمكن البث من التطبيق على هذا الجهاز"
        )

        rows += CapabilityRow("التقاط الشاشة (ميديا بروجكشن)", "متاح", "بموافقتك عند بدء كل جلسة")

        val dm = ctx.resources.displayMetrics
        rows += CapabilityRow("دقة الشاشة", "${dm.widthPixels} × ${dm.heightPixels}", "")

        rows += CapabilityRow("اكتشاف الخدمات على الشبكة (إم دي إن إس)", "متاح", "")

        rows += CapabilityRow("نسخة التطبيق", Protocol.VERSION, "")
        return rows
    }

    fun me(ctx: Context): DeviceInfo {
        val dm = ctx.resources.displayMetrics
        return DeviceInfo(
            name = "${Build.MANUFACTURER} ${Build.MODEL}",
            model = Build.MODEL,
            role = "sender",
            version = Protocol.VERSION,
            width = dm.widthPixels,
            height = dm.heightPixels
        )
    }

    @Suppress("DEPRECATION")
    private fun gateway(ctx: Context): String? = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val g = wm.dhcpInfo?.gateway ?: 0
        if (g == 0) null else "${g and 0xff}.${(g shr 8) and 0xff}.${(g shr 16) and 0xff}.${(g shr 24) and 0xff}"
    } catch (_: Exception) {
        null
    }

    private fun isOnWifi(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasPackage(pm: PackageManager, name: String): Boolean = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(name, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun hasAvcEncoder(): Boolean = try {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
        }
    } catch (_: Exception) {
        false
    }
}
