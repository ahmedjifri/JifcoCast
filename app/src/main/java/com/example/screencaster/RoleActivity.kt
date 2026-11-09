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

/** شاشة البداية: الدور (مرسل/مستقبل) والاستعلامان (هذا الجهاز/أجهزة الشبكة). تُسأل في كل مرة. */
class RoleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestHomeShortcutOnce()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
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
    FramedScreen(title = "", onHome = null) {
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
        TvButton("Jifco Cast - Sender\nمرسل — يعرض شاشته على غيره", fontSize = 16.sp, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(72.dp), onClick = onSender)
        Spacer(Modifier.height(10.dp))
        TvButton("Jifco Cast - Receiver\nمستقبل — يستقبل شاشة غيره", fontSize = 16.sp, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(72.dp), onClick = onReceiver)

        Spacer(Modifier.height(24.dp))
        Text("استعلام", fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        TvButton("هذا الجهاز — هل يبثّ/يستقبل وبأي تقنية، وتطبيقات البث عليه", outlined = true, fontSize = 15.sp, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(64.dp), onClick = onDeviceReport)
        Spacer(Modifier.height(10.dp))
        TvButton("أجهزة الشبكة — ما يمكن البث إليه وكيف", outlined = true, fontSize = 15.sp, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(64.dp), onClick = onNetworkReport)

        Spacer(Modifier.height(24.dp))
        TvButton("خروج من البرنامج", outlined = true, fontSize = 16.sp, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(52.dp), onClick = onExit)
        Spacer(Modifier.height(8.dp))
    }
    }
}
