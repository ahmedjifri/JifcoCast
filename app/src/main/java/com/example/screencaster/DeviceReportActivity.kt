package com.example.screencaster

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.screencaster.shared.Protocol

/** تقرير «هذا الجهاز»: هل يصلح مرسلاً/مستقبلاً وبأي تقنيات، وتطبيقات البث المركّبة. */
class DeviceReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    DeviceReportScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceReportScreen() {
    val ctx = LocalContext.current
    val report = remember { DeviceReport.build(ctx) }
    var exporting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    FramedScreen(title = "هذا الجهاز", onHome = { (ctx as? Activity)?.finish() }) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(report.deviceName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("يصلح مرسلاً؟ ${report.senderSummary}", fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("يصلح مستقبلاً؟ ${report.receiverSummary}", fontSize = 14.sp)
                    }
                }
            }
            item {
                BusyButton("تصدير إكسل (هذا الجهاز + تطبيقات البث)", busy = exporting, outlined = true, modifier = Modifier.fillMaxWidth()) {
                    exporting = true
                    status = "جارٍ بناء ملف إكسل…"
                    ReportExporter.export(ctx, emptyList(), emptyList(), emptyList()) { r -> exporting = false; status = r }
                }
            }
            if (status.isNotBlank()) item { Text(status, style = MaterialTheme.typography.bodyMedium) }

            item { SectionTitle("كمرسل — بأي تقنية يبثّ هذا الجهاز") }
            items(report.senderVerdicts) { VerdictCard(it) }

            item { SectionTitle("كمستقبل — بأي تقنية يستقبل") }
            items(report.receiverVerdicts) { VerdictCard(it) }

            item { SectionTitle("تطبيقات البث على هذا الجهاز") }
            items(report.apps.sortedByDescending { it.installed }) { app -> CastAppCard(app) }

            item { SectionTitle("تفاصيل العتاد والشبكة") }
            items(report.details) { CapabilityCard(it) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun CastAppCard(app: CastApp) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (app.installed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(10.dp)) {
            Text((if (app.installed) "✓ مركّب — " else "✗ غير مركّب — ") + app.name + (if (app.versionName.isNotBlank()) " (${app.versionName})" else ""), fontWeight = FontWeight.Bold)
            Text("الدور: ${app.role} · التقنية: ${app.tech}", fontSize = 13.sp)
            Text("يبثّ إلى: ${app.targets}", fontSize = 13.sp)
            Text(app.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            if (app.pkg != "com.example.screencaster") {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (app.installed) Button(onClick = { CastApps.open(ctx, app) }) { Text("افتح") }
                    else OutlinedButton(onClick = { CastApps.store(ctx, app) }) { Text("المتجر") }
                }
            }
        }
    }
}
