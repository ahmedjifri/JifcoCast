package com.example.screencaster

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
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

/** تقرير «أجهزة الشبكة»: كل جهاز على الشبكة وهل يمكن البث إليه وبأي تقنية. */
class NetworkReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    NetworkReportScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkReportScreen() {
    val ctx = LocalContext.current
    val scanner = remember { NetworkScanner(ctx) }
    val devices = remember { mutableStateListOf<DiscoveredDevice>() }
    val log = remember { mutableStateListOf<String>() }
    val probeResults = remember { mutableStateMapOf<String, String>() }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("اضغط «ابحث» لاستكشاف كل ما على شبكتك") }

    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    FramedScreen(title = "أجهزة الشبكة", onHome = { (ctx as? Activity)?.finish() }) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BusyButton(if (scanning) "جارٍ البحث…" else "ابحث", busy = scanning, modifier = Modifier.weight(1f)) {
                        scanning = true; scanned = false
                        devices.clear(); log.clear(); probeResults.clear()
                        status = "جارٍ البحث (نحو ٧ ثوانٍ)…"
                        scanner.scan(
                            onUpdate = { list -> devices.clear(); devices.addAll(list); status = "وُجد ${list.size} جهاز حتى الآن…" },
                            onLog = { log.add(it) },
                            onDone = {
                                scanning = false; scanned = true
                                val castable = devices.count { it.canCast == "نعم" }
                                status = if (devices.isEmpty()) "لم يُوجد أي جهاز. تأكد من الاتصال بالواي فاي."
                                else "انتهى البحث: ${devices.size} جهاز، منها $castable يمكن البث إليه"
                            }
                        )
                    }
                    BusyButton("تصدير إكسل", busy = exporting, enabled = scanned, outlined = true, modifier = Modifier.weight(1f)) {
                        exporting = true
                        status = "جارٍ بناء ملف إكسل (هذا الجهاز + الشبكة)…"
                        ReportExporter.export(ctx, devices.toList(), scanner.rawSsdp(), log.toList()) { r -> exporting = false; status = r }
                    }
                }
            }
            item { Text(status, style = MaterialTheme.typography.bodyMedium) }
            items(devices, key = { it.id }) { dev ->
                DeviceCard(
                    dev = dev,
                    probe = probeResults[dev.id],
                    onProbe = {
                        probeResults[dev.id] = PROBING
                        scanner.probeReceiver(dev) { res -> probeResults[dev.id] = res }
                    }
                )
            }
            if (log.isNotEmpty()) {
                item { SectionTitle("سجل البحث") }
                items(log) { line -> Text(line, fontSize = 12.sp) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
