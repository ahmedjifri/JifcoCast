package com.example.screencaster

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** تطبيق بث معروف: اسمه وحزمته وتقنيته وما يبثّ إليه. */
class CastApp(
    val name: String,
    val pkg: String,
    val role: String,       // مرسل / مستقبل / مرسل ومستقبل
    val tech: String,       // التقنية
    val targets: String,    // إلى ماذا يبثّ
    val notes: String       // ما يستطيعه
) {
    var installed: Boolean = false
    var versionName: String = ""
}

/** قائمة تطبيقات البث المعروفة، وفحص أيّها مركّب على هذا الجهاز. */
object CastApps {

    val catalog: List<CastApp> = listOf(
        CastApp("جوجل هوم", "com.google.android.apps.chromecast.app", "مرسل", "جوجل كاست",
            "كروم كاست، أندرويد تي في / جوجل تي في، تلفزيونات فيها كروم كاست مدمج",
            "عكس الشاشة كاملة (زر «بث شاشتي») وبث الوسائط — الطريقة الرسمية"),
        CastApp("كروم (المتصفح)", "com.android.chrome", "مرسل", "جوجل كاست",
            "كروم كاست وأندرويد تي في", "بث تبويب المتصفح أو ملف فيديو إلى الشاشة"),
        CastApp("يوتيوب", "com.google.android.youtube", "مرسل", "جوجل كاست + داي إل",
            "كروم كاست، أندرويد تي في، أغلب التلفزيونات الذكية", "بث الفيديو فقط (لا عكس شاشة)"),
        CastApp("خدمات جوجل بلاي", "com.google.android.gms", "أساس", "جوجل كاست",
            "—", "لازمة لعمل أي بث رسمي إلى كروم كاست"),
        CastApp("سمارت فيو (سامسونج)", "com.samsung.android.smartmirroring", "مرسل", "ميراكاست + سامسونج",
            "تلفزيونات سامسونج وأي مستقبل ميراكاست", "عكس الشاشة من لوحة الإعدادات السريعة"),
        CastApp("سمارت ثينغز (سامسونج)", "com.samsung.android.oneconnect", "مرسل", "سامسونج",
            "تلفزيونات سامسونج", "تحكم بالتلفاز وبث وسائط"),
        CastApp("مي ريموت (شاومي)", "com.xiaomi.mitv.phone.remotecontroller", "مرسل", "شاومي + ميراكاست",
            "تلفزيونات وصناديق شاومي", "تحكم وعكس شاشة إلى أجهزة شاومي"),
        CastApp("مي هوم (شاومي)", "com.xiaomi.smarthome", "مرسل", "شاومي",
            "أجهزة شاومي الذكية", "تحكم بالأجهزة؛ بث محدود"),
        CastApp("إل جي ثينك", "com.lgeha.nuts", "مرسل", "إل جي",
            "تلفزيونات إل جي", "تحكم وبث وسائط إلى إل جي"),
        CastApp("إبسون آي بروجكشن", "com.epson.iprojection", "مرسل", "بروتوكول إبسون",
            "بروجكتورات إبسون الشبكية", "عرض الصور والمستندات وعكس الشاشة على طرز إبسون المدعومة"),
        CastApp("إير سكرين", "com.ionitech.airscreen", "مستقبل", "إير بلاي + كاست + ميراكاست + دي إل إن إيه",
            "يحوّل هذا الجهاز إلى مستقبل", "يستقبل من الآيفون والأندرويد والحاسب"),
        CastApp("ويب فيديو كاستر", "com.instantbits.cast.webvideo", "مرسل", "جوجل كاست + دي إل إن إيه",
            "كروم كاست والتلفزيونات الذكية", "بث فيديو من مواقع الويب"),
        CastApp("بابل يو بي إن بي", "com.bubblesoft.android.bubbleupnp", "مرسل", "دي إل إن إيه + كاست",
            "مشغّلات دي إل إن إيه وكروم كاست", "بث ملفات الوسائط"),
        CastApp("بلكس", "com.plexapp.android", "مرسل", "جوجل كاست + دي إل إن إيه",
            "كروم كاست والتلفزيونات الذكية", "بث مكتبة الوسائط"),
        CastApp("جوجل تي في ريموت", "com.google.android.tv.remote.service", "مرسل", "جوجل",
            "أندرويد تي في / جوجل تي في", "ريموت فقط (لا بث)"),
        CastApp("روكو", "com.roku.remote", "مرسل", "روكو", "أجهزة روكو", "ريموت وبث وسائط"),
        CastApp("فاير تي في ريموت", "com.amazon.storm.lightning.client.aosp", "مرسل", "أمازون",
            "فاير تي في", "ريموت فقط"),
        CastApp("Jifrico Cast (هذا البرنامج)", "com.example.screencaster", "مرسل ومستقبل", "عاكس (إتش ٢٦٤ عبر الشبكة المحلية)",
            "أي جهاز أندرويد مركّب عليه عاكس", "عكس الشاشة مباشرة بلا وسيط")
    )

    fun scan(ctx: Context): List<CastApp> {
        val pm = ctx.packageManager
        for (app in catalog) {
            try {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(app.pkg, 0)
                app.installed = true
                app.versionName = info.versionName ?: ""
            } catch (_: Exception) {
                app.installed = false
                app.versionName = ""
            }
        }
        return catalog
    }

    fun open(ctx: Context, app: CastApp) {
        val i = ctx.packageManager.getLaunchIntentForPackage(app.pkg) ?: return
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun store(ctx: Context, app: CastApp) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${app.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }
}
