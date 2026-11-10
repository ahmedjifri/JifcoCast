package com.example.screencaster

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.screencaster.shared.Protocol

/** شاشة المرسل: البحث عن مستقبلات عاكس فقط، والبث إليها. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SenderScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderScreen() {
    val ctx = LocalContext.current
    val scanner = remember { NetworkScanner(ctx) }
    val receivers = remember { mutableStateListOf<DiscoveredDevice>() }
    val probeResults = remember { mutableStateMapOf<String, String>() }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("اضغط «ابحث عن المستقبلات» — يظهر هنا كل جهاز مفتوح عليه «Jifrico Cast - Receiver»") }
    var pendingTarget by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var qualityIndex by remember { mutableStateOf(Qualities.index(ctx)) }
    var pinDialogFor by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var pinInput by remember { mutableStateOf("") }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val target = pendingTarget
        if (result.resultCode == Activity.RESULT_OK && data != null && target != null) {
            val dm = ctx.resources.displayMetrics
            val pin = PinStore.savedPin(ctx, target.host) ?: ""
            CastService.start(ctx, result.resultCode, data, target.host, target.port, target.name, dm.widthPixels, dm.heightPixels, dm.densityDpi, pin)
        } else {
            CastState.status = "لم تُمنح الموافقة على التقاط الشاشة"
        }
    }
    fun launchProjection() {
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // أندرويد ١٤+: نطلب الشاشة كاملة حصراً فلا يعرض الحوار خيار «تطبيق واحد»
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        projectionLauncher.launch(intent)
    }
    // إذن الصوت (لالتقاط صوت التشغيل، لا الميكروفون) — إن رُفض يستمر البث بلا صوت
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { launchProjection() }
    fun askAudioThenProject() {
        if (Build.VERSION.SDK_INT >= 29 &&
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        else launchProjection()
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askAudioThenProject() }

    /** الترتيب: إذن الإشعارات (أندرويد ١٣+) ← إذن الصوت (أندرويد ١٠+) ← حوار التقاط الشاشة. كل إذن يُطلب مرة واحدة. */
    fun startPermissions(dev: DiscoveredDevice) {
        pendingTarget = dev
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else askAudioThenProject()
    }

    /** كلمة سر المستقبل: محفوظة لكل عنوان؛ إن لم تكن محفوظة (أو رُفضت) تُطلب في مربع حوار. */
    fun startCast(dev: DiscoveredDevice) {
        if (PinStore.savedPin(ctx, dev.host) != null) startPermissions(dev)
        else { pinInput = ""; pinDialogFor = dev }
    }

    pinDialogFor?.let { dev ->
        AlertDialog(
            onDismissRequest = { pinDialogFor = null },
            title = { Text("كلمة سر المستقبل") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("أدخل كلمة سر المستقبل «${dev.name}» (الافتراضية 1234، تُغيَّر من شاشة المستقبل). تُحفظ بعد أول اتصال ناجح.", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinInput, onValueChange = { pinInput = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
            },
            confirmButton = {
                Button(enabled = pinInput.length >= 4, onClick = {
                    PinStore.savePin(ctx, dev.host, pinInput)
                    pinDialogFor = null
                    startPermissions(dev)
                }) { Text("اتصال") }
            },
            dismissButton = { TextButton(onClick = { pinDialogFor = null }) { Text("إلغاء") } }
        )
    }

    fun scan() {
        scanning = true
        receivers.clear()
        probeResults.clear()
        status = "جارٍ البحث (نحو ٧ ثوانٍ)…"
        scanner.scan(
            onUpdate = { list ->
                receivers.clear()
                receivers.addAll(list.filter { DeviceKind.OURS in it.kinds })
            },
            onLog = { },
            onDone = {
                scanning = false
                status = if (receivers.isEmpty())
                    "لم يُوجد مستقبل. افتح «Jifrico Cast» على الشاشة واختر «Receiver»، وتأكد أن الجهازين على الواي فاي نفسه."
                else "وُجد ${receivers.size} مستقبل"
            }
        )
    }

    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    FramedScreen(title = "Ready to Send", onHome = { (ctx as? Activity)?.finish() }) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (CastState.active || CastState.connecting || CastState.status.isNotBlank()) {
                item { CastStatusCard(onStop = { CastService.stop(ctx) }, onMode = { m -> CastService.setMode(ctx, m) }, onAudioDelay = { ms -> CastService.setAudioDelay(ctx, ms) }) }
            }
            item {
                // بعد أي عطل تعود الخدمة إلى 720p — نعرض القيمة المحفوظة الحالية
                qualityIndex = Qualities.index(ctx)
                QualityPicker(selected = qualityIndex, enabled = !(CastState.active || CastState.connecting)) { i ->
                    qualityIndex = i; Qualities.set(ctx, i)
                }
            }
            item { SectionTitle("المستقبلات على الشبكة") }
            item {
                BusyButton(if (scanning) "جارٍ البحث…" else "ابحث عن المستقبلات", busy = scanning, modifier = Modifier.fillMaxWidth()) { scan() }
            }
            item { Text(status, style = MaterialTheme.typography.bodyMedium) }
            items(receivers, key = { it.id }) { dev ->
                DeviceCard(
                    dev = dev,
                    probe = probeResults[dev.id],
                    onProbe = {
                        probeResults[dev.id] = PROBING
                        scanner.probeReceiver(dev) { res -> probeResults[dev.id] = res }
                    },
                    onCast = { startCast(dev) }
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
