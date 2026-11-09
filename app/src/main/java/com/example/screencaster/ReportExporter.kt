package com.example.screencaster

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.example.screencaster.shared.MiniXlsx
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * يبني ملف إكسل واحداً فيه تقرير «هذا الجهاز» وتقرير «أجهزة الشبكة»
 * (فهرس + ورقة لكل باب) ويحفظه في مجلد التنزيلات/ScreenCaster.
 */
object ReportExporter {

    fun export(
        ctx: Context,
        devices: List<DiscoveredDevice>,
        rawSsdp: List<Map<String, String>>,
        log: List<String>,
        onDone: (String) -> Unit
    ) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = try {
                val report = DeviceReport.build(ctx)
                val xlsx = build(report, devices, rawSsdp, log)
                val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
                val name = "ScreenCaster-تقرير-$stamp.xlsx"
                val where = save(ctx, name, xlsx)
                "تم الحفظ: $where"
            } catch (e: Exception) {
                "فشل التصدير: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post { onDone(result) }
        }, "export-xlsx").start()
    }

    private fun yesNo(b: Boolean) = if (b) "نعم" else "لا"

    private fun build(
        r: DeviceReport,
        devices: List<DiscoveredDevice>,
        rawSsdp: List<Map<String, String>>,
        log: List<String>
    ): MiniXlsx {
        val x = MiniXlsx()

        x.addSheet(
            "فهرس", listOf(
                listOf("الورقة", "المحتوى", "عدد الصفوف"),
                listOf("هذا الجهاز", "هل يصلح مرسلاً/مستقبلاً وبأي تقنيات، ثم تفاصيل العتاد", (r.senderVerdicts.size + r.receiverVerdicts.size + r.details.size).toString()),
                listOf("تطبيقات البث", "تطبيقات البث المعروفة: المركّب منها وتقنيته وما يبثّ إليه", r.apps.size.toString()),
                listOf("الأجهزة", "الأجهزة المكتشفة على الشبكة: هل يمكن البث إليها وكيف", devices.size.toString()),
                listOf("تفاصيل الأجهزة", "كل خاصية أُعلنت عن كل جهاز", devices.sumOf { it.details.size }.toString()),
                listOf("استجابات SSDP", "الاستجابات الخام لبحث إس إس دي بي", rawSsdp.size.toString()),
                listOf("سجل البحث", "ما جرى أثناء البحث", log.size.toString()),
                listOf("الجهاز", r.deviceName, ""),
                listOf("تاريخ الملف", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()), "")
            )
        )

        val dev = ArrayList<List<String>>()
        dev += listOf("القسم", "التقنية / البند", "متاح / القيمة", "كيف / الحكم")
        dev += listOf("خلاصة", "يصلح مرسلاً؟", r.senderSummary, "")
        dev += listOf("خلاصة", "يصلح مستقبلاً؟", r.receiverSummary, "")
        for (v in r.senderVerdicts) dev += listOf("مرسل", v.tech, yesNo(v.available), v.how)
        for (v in r.receiverVerdicts) dev += listOf("مستقبل", v.tech, yesNo(v.available), v.how)
        for (c in r.details) dev += listOf("تفاصيل", c.item, c.value, c.verdict)
        x.addSheet("هذا الجهاز", dev)

        x.addSheet(
            "تطبيقات البث",
            listOf(listOf("التطبيق", "مركّب؟", "النسخة", "الدور", "التقنية", "يبثّ إلى", "ما يستطيعه", "الحزمة")) +
                r.apps.map { listOf(it.name, yesNo(it.installed), it.versionName, it.role, it.tech, it.targets, it.notes, it.pkg) }
        )

        x.addSheet(
            "الأجهزة",
            listOf(listOf("الاسم", "النوع", "العنوان", "المنفذ", "يمكن البث إليه؟", "بأي تقنية", "الأنواع المعلنة", "المصدر")) +
                devices.map {
                    listOf(it.name, it.kind.label, it.host, it.port.toString(), it.canCast, it.castHow,
                        it.kinds.joinToString("، ") { k -> k.label }, it.details["source"] ?: "")
                }
        )

        val detailRows = ArrayList<List<String>>()
        detailRows += listOf("الجهاز", "العنوان", "الخاصية", "القيمة")
        for (d in devices) for ((k, v) in d.details) detailRows += listOf(d.name, d.host, k, v)
        x.addSheet("تفاصيل الأجهزة", detailRows)

        val keys = LinkedHashSet<String>()
        for (row in rawSsdp) keys.addAll(row.keys)
        val keyList = keys.toList()
        val rawRows = ArrayList<List<String>>()
        rawRows += if (keyList.isEmpty()) listOf("لا استجابات") else keyList
        for (row in rawSsdp) rawRows += keyList.map { row[it] ?: "" }
        x.addSheet("استجابات SSDP", rawRows)

        x.addSheet("سجل البحث", listOf(listOf("السطر")) + log.map { listOf(it) })
        return x
    }

    private fun save(ctx: Context, name: String, xlsx: MiniXlsx): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScreenCaster")
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("تعذر إنشاء الملف في التنزيلات")
            resolver.openOutputStream(uri)?.use { xlsx.write(it) }
                ?: throw IllegalStateException("تعذر فتح الملف للكتابة")
            return "التنزيلات/ScreenCaster/$name"
        } else {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val f = File(dir, name)
            FileOutputStream(f).use { xlsx.write(it) }
            return f.absolutePath
        }
    }
}
