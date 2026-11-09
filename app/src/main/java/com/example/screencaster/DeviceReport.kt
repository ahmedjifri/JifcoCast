package com.example.screencaster

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.os.Build
import android.provider.Settings

/** سطر حكم: التقنية، هل متاحة، وكيف/لماذا. */
class VerdictRow(val tech: String, val available: Boolean, val how: String)

/** تقرير «هذا الجهاز»: هل يصلح مرسلاً ومستقبلاً وبأي تقنيات. */
class DeviceReport(
    val deviceName: String,
    val isTv: Boolean,
    val senderVerdicts: List<VerdictRow>,
    val receiverVerdicts: List<VerdictRow>,
    val details: List<CapabilityRow>,
    val apps: List<CastApp>
) {
    val canSend: Boolean get() = senderVerdicts.any { it.available }
    val canReceive: Boolean get() = receiverVerdicts.any { it.available }

    val senderSummary: String
        get() = if (canSend) "نعم — عبر: " + senderVerdicts.filter { it.available }.joinToString("، ") { it.tech }
        else "لا — لا تتوافر أي تقنية بث على هذا الجهاز"

    val receiverSummary: String
        get() = if (canReceive) "نعم — عبر: " + receiverVerdicts.filter { it.available }.joinToString("، ") { it.tech }
        else "لا"

    companion object {
        fun build(ctx: Context): DeviceReport {
            val pm = ctx.packageManager
            val caps = Capabilities.collect(ctx)
            val apps = CastApps.scan(ctx)

            val isTv = pm.hasSystemFeature("android.software.leanback") ||
                pm.hasSystemFeature("android.hardware.type.television")
            val gms = CastApps.isInstalled(pm, "com.google.android.gms")
            val home = CastApps.isInstalled(pm, "com.google.android.apps.chromecast.app")
            val p2p = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
            val castSettings = pm.resolveActivity(Intent(Settings.ACTION_CAST_SETTINGS), 0) != null
            val samsungSmartView = CastApps.isInstalled(pm, "com.samsung.android.smartmirroring")
            val miRemote = CastApps.isInstalled(pm, "com.xiaomi.mitv.phone.remotecontroller")
            val enc = hasCodec(encoder = true)
            val dec = hasCodec(encoder = false)

            val sender = ArrayList<VerdictRow>()
            sender += VerdictRow(
                "عاكس", enc,
                if (enc) "التقاط الشاشة + مشفّر إتش ٢٦٤ متاحان — يبثّ إلى أي جهاز عليه عاكس (مستقبل)"
                else "لا يوجد مشفّر إتش ٢٦٤ على هذا الجهاز"
            )
            sender += VerdictRow(
                "كروم كاست الرسمي", gms && home,
                when {
                    gms && home -> "خدمات جوجل بلاي + جوجل هوم موجودان — من جوجل هوم: الجهاز ← «بث شاشتي»"
                    gms -> "خدمات جوجل بلاي موجودة، ينقص تطبيق جوجل هوم (ركّبه من المتجر)"
                    else -> "لا توجد خدمات جوجل بلاي — البث الرسمي غير ممكن"
                }
            )
            sender += VerdictRow(
                "ميراكاست (من النظام)", p2p && castSettings,
                when {
                    p2p && castSettings -> "واي فاي دايركت مدعوم وشاشة «بث الشاشة» موجودة في الإعدادات — إلى تلفزيونات وبروجكتورات ميراكاست"
                    p2p -> "واي فاي دايركت مدعوم لكن النظام لا يوفّر شاشة بث — غالباً معطّل من الشركة المصنّعة"
                    else -> "واي فاي دايركت غير مدعوم"
                }
            )
            if (Build.MANUFACTURER.equals("samsung", true) || samsungSmartView) {
                sender += VerdictRow("سمارت فيو (سامسونج)", samsungSmartView,
                    if (samsungSmartView) "موجود — من لوحة الإعدادات السريعة ← Smart View" else "غير مركّب")
            }
            if (Build.MANUFACTURER.equals("xiaomi", true) || miRemote) {
                sender += VerdictRow("بث شاومي (مي ريموت)", miRemote,
                    if (miRemote) "موجود — إلى تلفزيونات وصناديق شاومي" else "غير مركّب — متاح في المتجر")
            }

            val receiver = ArrayList<VerdictRow>()
            receiver += VerdictRow("مستقبل عاكس", dec,
                if (dec) "فاكّ إتش ٢٦٤ متاح — اختر «مستقبل» في شاشة البداية" else "لا يوجد فاكّ إتش ٢٦٤")
            receiver += VerdictRow("كروم كاست مدمج", isTv,
                if (isTv) "هذا جهاز تلفاز بأندرويد — غالباً فيه كروم كاست مدمج ويظهر للمرسلين تلقائياً"
                else "ليس جهاز تلفاز — لا يستقبل كاست إلا بتطبيق مستقبل مثل إير سكرين")
            val airScreen = CastApps.isInstalled(pm, "com.ionitech.airscreen")
            if (airScreen) receiver += VerdictRow("إير سكرين", true, "مركّب — يستقبل إير بلاي وكاست وميراكاست")

            return DeviceReport(
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}" + if (isTv) " (تلفاز)" else "",
                isTv = isTv,
                senderVerdicts = sender,
                receiverVerdicts = receiver,
                details = caps,
                apps = apps
            )
        }

        private fun hasCodec(encoder: Boolean): Boolean = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                info.isEncoder == encoder && info.supportedTypes.any { it.equals("video/avc", true) }
            }
        } catch (_: Exception) { false }
    }
}
