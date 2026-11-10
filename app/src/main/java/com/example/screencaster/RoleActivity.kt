package com.example.screencaster

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.screencaster.shared.Protocol
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/** شاشة البداية: الدور (مرسل/مستقبل) والاستعلامان (هذا الجهاز/أجهزة الشبكة). تُسأل في كل مرة. */
class RoleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Updater.installCrashRecorder(this)
        requestHomeShortcutOnce()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    UpdateDialogs(autoCheck = true)
                    RoleScreen(
                        onSender = { startActivity(Intent(this, MainActivity::class.java)) },
                        onReceiver = { startActivity(Intent(this, ReceiverActivity::class.java)) },
                        onDeviceReport = { startActivity(Intent(this, DeviceReportActivity::class.java)) },
                        onNetworkReport = { startActivity(Intent(this, NetworkReportActivity::class.java)) },
                        onExit = { exitApp() }
                    )
                }
            }
        }
    }

    /** يوقف البث إن كان جارياً ويغلق كل شاشات التطبيق. */
    private fun exitApp() {
        try { CastService.stop(this) } catch (_: Exception) {}
        finishAffinity()
    }

    /** عند أول تشغيل: يطلب من النظام تثبيت الأيقونة في الشاشة الرئيسة (أندرويد ٨+، ليس على التلفاز). */
    private fun requestHomeShortcutOnce() {
        if (Build.VERSION.SDK_INT < 26) return
        val prefs = getSharedPreferences("aakes", Context.MODE_PRIVATE)
        if (prefs.getBoolean("pin_asked", false)) return
        prefs.edit().putBoolean("pin_asked", true).apply()
        try {
            val sm = getSystemService(ShortcutManager::class.java) ?: return
            if (!sm.isRequestPinShortcutSupported) return
            val info = ShortcutInfo.Builder(this, "aakes-home")
                .setShortLabel(APP_NAME)
                .setLongLabel("$APP_NAME — عكس الشاشة")
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(Intent(this, RoleActivity::class.java).setAction(Intent.ACTION_MAIN))
                .build()
            sm.requestPinShortcut(info, null)
        } catch (_: Exception) {
        }
    }
}

@Composable
fun RoleScreen(
    onSender: () -> Unit,
    onReceiver: () -> Unit,
    onDeviceReport: () -> Unit,
    onNetworkReport: () -> Unit,
    onExit: () -> Unit
) {
    var manualCheck by androidx.compose.runtime.remember { mutableStateOf(false) }
    var showSettings by androidx.compose.runtime.remember { mutableStateOf(false) }
    var showLog by androidx.compose.runtime.remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    if (showLog) AlertDialog(
        onDismissRequest = { showLog = false },
        title = { Text("سجل التحديث") },
        text = {
            val crash = Updater.lastCrash(ctx)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("النسخة ${Protocol.VERSION} · إذن التثبيت: ${if (Updater.canInstall(ctx)) "ممنوح" else "غير ممنوح"}", fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(Updater.readLog(ctx).ifBlank { "لا شيء بعد" }, fontSize = 11.sp)
                if (crash.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text("آخر انهيار:", fontSize = 12.sp); Text(crash, fontSize = 10.sp) }
            }
        },
        confirmButton = { Button(onClick = { showLog = false }) { Text("إغلاق") } },
        dismissButton = { TextButton(onClick = { Updater.clearLog(ctx); showLog = false }) { Text("مسح") } }
    )
    if (manualCheck) UpdateDialogs(autoCheck = false, onDone = { manualCheck = false })
    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        title = { Text("⚙ الإعدادات والاستعلام") },
        text = {
            Column {
                TvButton("هذا الجهاز — هل يبثّ/يستقبل وبأي تقنية، وتطبيقات البث عليه", outlined = true, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().height(64.dp)) { showSettings = false; onDeviceReport() }
                Spacer(Modifier.height(8.dp))
                TvButton("أجهزة الشبكة — ما يمكن البث إليه وكيف", outlined = true, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().height(64.dp)) { showSettings = false; onNetworkReport() }
                Spacer(Modifier.height(8.dp))
                TvButton("سجل التحديث (للتشخيص)", outlined = true, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().height(52.dp)) { showSettings = false; showLog = true }
            }
        },
        confirmButton = { TextButton(onClick = { showSettings = false }) { Text("إغلاق") } }
    )
    FramedScreen(title = "", onHome = null) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    // الشعار في الركن العلوي الأول، وزر الإعدادات في الركن المقابل
    androidx.compose.foundation.layout.Box(Modifier.align(Alignment.TopStart)) { Logo(72.dp) }
    androidx.compose.foundation.layout.Box(Modifier.align(Alignment.TopEnd)) {
        TvButton("⚙ الإعدادات", outlined = true, fontSize = 14.sp) { showSettings = true }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(APP_NAME, fontSize = 36.sp, textAlign = TextAlign.Center)
        Text("${Build.MANUFACTURER} ${Build.MODEL}", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(24.dp))

        Text("ما دور هذا الجهاز الآن؟", fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        TvButton("Jifrico Cast - Sender\nمرسل — يعرض شاشته على غيره", fontSize = 16.sp, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(72.dp), onClick = onSender)
        Spacer(Modifier.height(10.dp))
        TvButton("Jifrico Cast - Receiver\nمستقبل — يستقبل شاشة غيره", fontSize = 16.sp, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(72.dp), onClick = onReceiver)

        Spacer(Modifier.height(32.dp))
        TvButton("التحقق من التحديثات", outlined = true, fontSize = 15.sp, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(52.dp), onClick = { manualCheck = true })
        Spacer(Modifier.height(10.dp))
        TvButton("خروج من البرنامج", outlined = true, fontSize = 16.sp, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(52.dp), onClick = onExit)
        Spacer(Modifier.height(8.dp))
    }
    }
    }
}


/**
 * مربعات التحديث: فحص (تلقائي عند التشغيل أو يدوي) ← «تحديث متاح» مع تحديث الآن/لاحقاً ← تنزيل بشريط تقدّم ← مثبّت النظام.
 * في الفحص التلقائي لا تظهر أي رسالة إن لم يوجد جديد أو تعذّر الاتصال.
 */
@Composable
fun UpdateDialogs(autoCheck: Boolean, onDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    var state by androidx.compose.runtime.remember { mutableStateOf("checking") } // checking | none | error | offer | downloading | failed | done
    var release by androidx.compose.runtime.remember { mutableStateOf<Updater.Release?>(null) }
    var progress by androidx.compose.runtime.remember { mutableStateOf(0) }
    var error by androidx.compose.runtime.remember { mutableStateOf("") }
    var needPermission by androidx.compose.runtime.remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Thread {
            val rel = Updater.fetchLatest()
            val newer = rel != null && Updater.isNewer(rel.version, Protocol.VERSION)
            val snoozed = rel != null && autoCheck && Updater.isSnoozed(ctx, rel.version)
            release = rel
            state = when {
                rel == null -> "error"
                newer && !snoozed -> "offer"
                else -> "none"
            }
        }.start()
    }

    fun startDownload() {
        val rel = release ?: return
        state = "downloading"; progress = 0
        Updater.log(ctx, "بدء التنزيل ${rel.version} — إذن التثبيت: ${if (Updater.canInstall(ctx)) "ممنوح" else "غير ممنوح"}")
        Thread {
            try {
                val f = Updater.download(ctx, rel) { p -> progress = p }
                Updater.log(ctx, "اكتمل التنزيل ${f.length() / 1024} ك.ب")
                state = "done"
                val err = Updater.install(ctx, f)
                Updater.log(ctx, if (err == null) "فُتح مثبّت النظام" else "فشل فتح المثبّت: $err")
                if (err != null) { error = err; state = "failed" }
                else if (!Updater.canInstall(ctx)) needPermission = true   // التلفاز: قد يحتاج الإذن قبل أن يقبل المثبّت
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                Updater.log(ctx, "فشل: $error")
                state = "failed"
            }
        }.start()
    }
    fun installAgain() {
        val r = release ?: return
        val f = java.io.File(ctx.cacheDir, "updates/JifcoCast-${r.version}.apk")
        if (!f.exists()) { startDownload(); return }
        val err = Updater.install(ctx, f)
        Updater.log(ctx, if (err == null) "إعادة فتح المثبّت" else "فشل فتح المثبّت: $err")
        if (err != null) { error = err; state = "failed" }
    }

    when {
        needPermission -> AlertDialog(
            onDismissRequest = { needPermission = false },
            title = { Text("إذن التثبيت") },
            text = { Text("إن لم يظهر مثبّت النظام أو رفض: يحتاج الجهاز موافقتك مرة واحدة على السماح لـ Jifrico Cast بتثبيت التطبيقات. اضغط «فتح الإعداد»، فعّل «السماح من هذا المصدر» (في بعض الأجهزة: الأمان ← التطبيقات غير المعروفة ← Jifrico Cast)، ثم ارجع واضغط «تثبيت».") },
            confirmButton = { Button(onClick = { val err = Updater.openInstallPermission(ctx); if (err != null) { error = err; state = "failed" }; needPermission = false }) { Text("فتح الإعداد") } },
            dismissButton = { TextButton(onClick = { needPermission = false; installAgain() }) { Text("تثبيت") } }
        )
        state == "offer" -> AlertDialog(
            onDismissRequest = { },
            title = { Text("تحديث متاح: ${release?.version}") },
            text = {
                Column {
                    Text("النسخة الحالية ${Protocol.VERSION}. التحديث يحمّل ملف التثبيت (نحو ٨ ميجابايت) ثم يفتح مثبّت النظام.", fontSize = 13.sp)
                    val granted = Updater.canInstall(ctx)
                    Spacer(Modifier.height(6.dp))
                    Text(if (granted) "إذن التثبيت: ممنوح ✓" else "إذن التثبيت: غير ممنوح — اضغط «منح الإذن» أولاً (مرة واحدة)، ثم «تحديث الآن»", fontSize = 12.sp)
                    val notes = release?.notes?.trim().orEmpty()
                    if (notes.isNotBlank() && notes != "null") { Spacer(Modifier.height(6.dp)); Text(notes.take(400), fontSize = 12.sp) }
                }
            },
            confirmButton = {
                androidx.compose.foundation.layout.Row {
                    if (!Updater.canInstall(ctx)) {
                        TextButton(onClick = { Updater.log(ctx, "فتح شاشة الإذن"); val err = Updater.openInstallPermission(ctx); if (err != null) { Updater.log(ctx, err); error = err; state = "failed" } }) { Text("منح الإذن") }
                    }
                    Button(onClick = { startDownload() }) { Text("تحديث الآن") }
                }
            },
            dismissButton = { TextButton(onClick = { release?.let { Updater.snooze(ctx, it.version) }; state = "none"; onDone() }) { Text("لاحقاً") } }
        )
        state == "downloading" -> AlertDialog(
            onDismissRequest = { },
            title = { Text("جارٍ تنزيل التحديث…") },
            text = {
                Column {
                    if (progress >= 0) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(if (progress >= 0) "$progress٪" else "…", fontSize = 13.sp)
                }
            },
            confirmButton = { }
        )
        state == "failed" -> AlertDialog(
            onDismissRequest = { state = "none"; onDone() },
            title = { Text("تعذر التحديث") },
            text = { Text(error) },
            confirmButton = { Button(onClick = { startDownload() }) { Text("إعادة المحاولة") } },
            dismissButton = { TextButton(onClick = { state = "none"; onDone() }) { Text("إغلاق") } }
        )
        state == "done" -> AlertDialog(
            onDismissRequest = { state = "none"; onDone() },
            title = { Text("اكتمل التنزيل") },
            text = { Text("اتبع مثبّت النظام لإتمام التحديث. إن لم يظهر أو رفض، اضغط «تثبيت» مرة أخرى، أو «الإذن» لفتح إعداد السماح بالتثبيت.") },
            confirmButton = { Button(onClick = { installAgain() }) { Text("تثبيت") } },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { needPermission = true }) { Text("الإذن") }
                    TextButton(onClick = { state = "none"; onDone() }) { Text("إغلاق") }
                }
            }
        )
        !autoCheck && state == "none" -> AlertDialog(
            onDismissRequest = { onDone() },
            title = { Text("لا يوجد تحديث") },
            text = { Text("أنت على آخر نسخة (${Protocol.VERSION}).") },
            confirmButton = { Button(onClick = { onDone() }) { Text("حسناً") } }
        )
        !autoCheck && state == "error" -> AlertDialog(
            onDismissRequest = { onDone() },
            title = { Text("تعذر الفحص") },
            text = { Text("لم أستطع الوصول إلى جيت هب. تأكد من الإنترنت وحاول مجدداً.") },
            confirmButton = { Button(onClick = { onDone() }) { Text("حسناً") } }
        )
        !autoCheck && state == "checking" -> AlertDialog(
            onDismissRequest = { },
            title = { Text("جارٍ الفحص…") },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = { }
        )
    }
}
