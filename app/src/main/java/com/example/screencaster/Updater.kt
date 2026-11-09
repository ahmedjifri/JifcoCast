package com.example.screencaster

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.screencaster.shared.Protocol
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * التحديث داخل التطبيق: يسأل جيت هب عن آخر إصدار، يقارنه بالنسخة الحالية،
 * ويحمّل ملف التثبيت ثم يفتح مثبّت النظام. «لاحقاً» = لا يُسأل عن هذه النسخة ٢٤ ساعة.
 */
object Updater {
    const val REPO = "ahmedjifri/JifcoCast"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val SNOOZE_MS = 24 * 60 * 60 * 1000L

    class Release(val version: String, val apkUrl: String, val notes: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("aakes", Context.MODE_PRIVATE)

    /** يجلب آخر إصدار (يُستدعى من خيط خلفي). null إن تعذّر. */
    fun fetchLatest(): Release? = try {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "JifcoCast/${Protocol.VERSION}")
        }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val o = JSONObject(body)
        val tag = o.optString("tag_name").removePrefix("v")
        var url = ""
        val assets = o.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
        }
        if (tag.isBlank() || url.isBlank()) null else Release(tag, url, o.optString("body"))
    } catch (_: Exception) { null }

    /** هل النسخة a أحدث من b؟ مقارنة رقمية جزءاً جزءاً (0.7.10 > 0.7.9). */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    fun isSnoozed(ctx: Context, version: String): Boolean {
        val p = prefs(ctx)
        return p.getString("update_snoozed_version", "") == version &&
            System.currentTimeMillis() - p.getLong("update_snoozed_at", 0L) < SNOOZE_MS
    }

    fun snooze(ctx: Context, version: String) {
        prefs(ctx).edit().putString("update_snoozed_version", version).putLong("update_snoozed_at", System.currentTimeMillis()).apply()
    }

    /** يحمّل ملف التثبيت إلى مجلد التخزين المؤقت مع نسبة التقدّم (0..100). يُستدعى من خيط خلفي. */
    fun download(ctx: Context, rel: Release, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "JifcoCast-${rel.version}.apk")
        var url = rel.apkUrl
        // جيت هب يحوّل رابط الأصل إلى خادم الملفات — نتبع التحويلات يدوياً (قد تغيّر المضيف)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 30000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "JifcoCast/${Protocol.VERSION}")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) { url = conn.getHeaderField("Location"); conn.disconnect(); hops++; continue }
            if (code != 200) throw IllegalStateException("فشل التنزيل (رمز $code)")
            break
        }
        val total = conn.contentLength.toLong()
        conn.inputStream.use { inp ->
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    os.write(buf, 0, n); done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != last) { last = pct; onProgress(pct) }
                }
            }
        }
        conn.disconnect()
        return out
    }

    /** يفتح مثبّت النظام للملف المحمّل. */
    fun install(ctx: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(i)
    }

    /** هل يسمح النظام لهذا التطبيق بتثبيت الحزم؟ (أندرويد ٨+ يحتاج موافقة مرة واحدة) */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
