package com.example.screencaster

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** عناصر واجهة مشتركة بين الشاشات. */

const val APP_NAME = "Jifco Cast"
val ScreenBg = Color(0xFF0F1F3D)      // خلفية الشاشة (كحلي غامق)
val FrameBg = Color(0xFFF6F3EA)       // داخل البرواز (فاتح)
val FrameBorder = Color(0xFFC9A227)   // حد البرواز (ذهبي)

/**
 * برواز موحّد لكل الشاشات: خلفية كحلية، وداخلها إطار فاتح بحدّ ذهبي يحوي العنوان وزر «الرئيسية» والمحتوى ورقم النسخة.
 * onHome = null في الشاشة الرئيسة نفسها.
 */
@Composable
fun FramedScreen(title: String, onHome: (() -> Unit)?, content: @Composable () -> Unit) {
    // safeDrawingPadding: لا يتداخل البرواز مع شريط الساعة وأيقونات النظام (أندرويد ١٥ يرسم من حافة إلى حافة)
    Box(Modifier.fillMaxSize().background(ScreenBg).safeDrawingPadding().padding(14.dp)) {
        Column(
            Modifier.fillMaxSize()
                .border(3.dp, FrameBorder, RoundedCornerShape(22.dp))
                .background(FrameBg, RoundedCornerShape(22.dp))
                .padding(14.dp)
        ) {
            if (title.isNotBlank() || onHome != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                    if (onHome != null) TvButton("الرئيسية", outlined = true, fontSize = 14.sp, onClick = onHome)
                }
                Spacer(Modifier.height(8.dp))
            }
            Box(Modifier.weight(1f)) { content() }
            Text("$APP_NAME · النسخة ${com.example.screencaster.shared.Protocol.VERSION}", modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center, fontSize = 12.sp)
        }
    }
}

const val PROBING = "جارٍ الاتصال…"

/** يُبرز الزر الذي عليه تركيز الريموت/لوحة المفاتيح: إطار أصفر سميك وتكبير خفيف. */
@Composable
fun Modifier.tvFocus(interaction: MutableInteractionSource): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    val yellow = androidx.compose.ui.graphics.Color(0xFFFFD600)
    return this
        .scale(if (focused) 1.06f else 1f)
        .then(if (focused) Modifier.border(6.dp, yellow, RoundedCornerShape(50)) else Modifier)
}

/** اختيار الدقة: زر ممتلئ للمختار، والباقي مفرّغ. يُقفل أثناء البث. */
@Composable
fun QualityPicker(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text("الدقة (تعود إلى 720p تلقائياً عند أي عطل)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Qualities.list.forEachIndexed { i, q ->
                TvButton(q.label, outlined = i != selected, enabled = enabled, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) { onSelect(i) }
            }
        }
    }
}

/** زر عادي يُظهر التركيز بوضوح على التلفاز. */
@Composable
fun TvButton(text: String, modifier: Modifier = Modifier, outlined: Boolean = false, enabled: Boolean = true, fontSize: androidx.compose.ui.unit.TextUnit = 16.sp, onClick: () -> Unit) {
    val i = remember { MutableInteractionSource() }
    if (outlined) OutlinedButton(onClick = onClick, enabled = enabled, interactionSource = i, modifier = modifier.tvFocus(i)) { Text(text, fontSize = fontSize, textAlign = TextAlign.Center) }
    else Button(onClick = onClick, enabled = enabled, interactionSource = i, modifier = modifier.tvFocus(i)) { Text(text, fontSize = fontSize, textAlign = TextAlign.Center) }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
fun CapabilityCard(row: CapabilityRow) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(row.item, fontWeight = FontWeight.Bold)
            Text(row.value)
            if (row.verdict.isNotBlank()) Text(row.verdict, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun VerdictCard(v: VerdictRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (v.available) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(10.dp)) {
            Text((if (v.available) "✓ " else "✗ ") + v.tech, fontWeight = FontWeight.Bold)
            Text(v.how, fontSize = 13.sp)
        }
    }
}

@Composable
fun LtrText(text: String, fontSize: androidx.compose.ui.unit.TextUnit = 12.sp) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(text, fontSize = fontSize, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
    }
}

/** بطاقة جهاز على الشبكة مع حكم البث وأزرار الاختبار (وزر العكس اختياري). */
@Composable
fun DeviceCard(
    dev: DiscoveredDevice,
    probe: String?,
    onProbe: () -> Unit,
    onCast: (() -> Unit)? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(dev.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(dev.kinds.joinToString(" + ") { it.label }, color = MaterialTheme.colorScheme.primary)
            LtrText("${dev.host}:${dev.port}")
            Text("يمكن البث إليه: ${dev.canCast}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(dev.castHow, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onCast != null && DeviceKind.OURS in dev.kinds) {
                    val busy = CastState.active || CastState.connecting
                    Button(onClick = onCast, enabled = !busy) { Text(if (busy) "يبثّ…" else "عكس الشاشة") }
                }
                OutlinedButton(onClick = onProbe, enabled = probe != PROBING) {
                    if (probe == PROBING) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (DeviceKind.OURS in dev.kinds) "اختبار الاتصال" else "اختبار الوصول للمنفذ")
                }
            }
            if (probe != null) Text(probe, fontSize = 12.sp)
        }
    }
}

@Composable
fun CastStatusCard(onStop: () -> Unit, onMode: ((String) -> Unit)? = null, onAudioDelay: ((Int) -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                when {
                    CastState.active -> "جارٍ عكس الشاشة إلى ${CastState.target}"
                    CastState.connecting -> "جارٍ الاتصال بـ ${CastState.target}…"
                    else -> "البث"
                },
                fontWeight = FontWeight.Bold
            )
            if (CastState.status.isNotBlank()) Text(CastState.status, fontSize = 13.sp)
            if (CastState.active) {
                val m = CastState.seconds / 60
                val sec = CastState.seconds % 60
                Text("المدة ${"%d:%02d".format(m, sec)} · الإطارات ${CastState.frames} · ${CastState.kbps} كيلوبت/ث · ${CastState.size}", fontSize = 12.sp)
                if (CastState.audio.isNotBlank()) Text("الصوت: ${CastState.audio}", fontSize = 12.sp)
            }
            if (CastState.active && onMode != null) {
                Spacer(Modifier.height(6.dp))
                Text("وضع العرض على الشاشة:", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { onMode("fit") }) { Text("ملاءمة") }
                    OutlinedButton(onClick = { onMode("fill") }) { Text("ملء") }
                    OutlinedButton(onClick = { onMode("stretch") }) { Text("تمديد") }
                }
                Text("اقلب الجهاز أفقياً لتملأ الصورة الشاشة", fontSize = 11.sp)
            }
            if (CastState.active && onAudioDelay != null) {
                Spacer(Modifier.height(6.dp))
                Text("تزامن الصوت — تأخير الصوت: ${CastState.audioDelay} مللي ثانية", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { onAudioDelay((CastState.audioDelay - 50).coerceAtLeast(0)) }) { Text("− ٥٠") }
                    OutlinedButton(onClick = { onAudioDelay((CastState.audioDelay + 50).coerceAtMost(2000)) }) { Text("+ ٥٠") }
                    OutlinedButton(onClick = { onAudioDelay(0) }) { Text("صفر") }
                }
                Text("زد التأخير إن سبق الصوتُ الصورةَ حتى تنطبق الشفاه على الكلام", fontSize = 11.sp)
            }
            if (CastState.active || CastState.connecting) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (CastState.connecting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Button(onClick = onStop) { Text("إيقاف البث") }
                }
            }
        }
    }
}

/** زر يُظهر دوراناً ويُقفل أثناء العمل. */
@Composable
fun BusyButton(text: String, busy: Boolean, enabled: Boolean = true, outlined: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
    val i = remember { MutableInteractionSource() }
    if (outlined) OutlinedButton(onClick = onClick, enabled = enabled && !busy, interactionSource = i, modifier = modifier.tvFocus(i)) { content() }
    else Button(onClick = onClick, enabled = enabled && !busy, interactionSource = i, modifier = modifier.tvFocus(i)) { content() }
}
