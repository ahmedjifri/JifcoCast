package com.example.screencaster

/** أنواع الأجهزة التي يكتشفها التطبيق، مرتّبة بالأولوية (الأصغر أهم). */
enum class DeviceKind(val label: String, val priority: Int) {
    OURS("مستقبل عاكس", 0),
    CAST("كروم كاست / جوجل تي في", 1),
    AIRPLAY("إير بلاي (آبل)", 2),
    DLNA("دي إل إن إيه (مشغّل وسائط)", 3),
    OTHER("جهاز شبكة آخر", 4)
}

class DiscoveredDevice(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val host: String,
    val port: Int,
    val details: Map<String, String>,
    val kinds: Set<DeviceKind> = setOf(kind)
) {
    /** هل يمكن البث إليه؟ */
    val canCast: String
        get() = when {
            DeviceKind.OURS in kinds || DeviceKind.CAST in kinds -> "نعم"
            DeviceKind.DLNA in kinds -> "جزئياً (ملفات وسائط فقط)"
            else -> "لا"
        }

    /** بأي تقنيات، بالترتيب. */
    val castHow: String
        get() {
            val ways = ArrayList<String>()
            if (DeviceKind.OURS in kinds) ways += "عاكس (عكس الشاشة مباشرة من هذا البرنامج)"
            if (DeviceKind.CAST in kinds) ways += "جوجل كاست الرسمي (جوجل هوم ← بث شاشتي، أو يوتيوب/كروم للوسائط)" +
                (if (DeviceKind.OURS !in kinds) "؛ ويمكن تركيب عاكس عليه إن كان أندرويد تي في" else "")
            if (DeviceKind.DLNA in kinds) ways += "دي إل إن إيه (إرسال ملفات وسائط من تطبيق دي إل إن إيه — لا عكس شاشة)"
            if (DeviceKind.AIRPLAY in kinds) ways += "إير بلاي (من أجهزة آبل فقط)"
            if (ways.isEmpty()) ways += "لا تقنية بث معروفة — جهاز شبكة عادي"
            return ways.joinToString(" | ")
        }

    /** ما يستطيع التطبيق عمله مع هذا الجهاز. */
    val action: String
        get() = when (kind) {
            DeviceKind.OURS -> "عكس الشاشة من التطبيق مباشرة"
            DeviceKind.CAST -> "يعمل بأندرويد تي في غالباً: ركّب عليه «مستقبل عاكس» ليظهر هنا كمستقبل"
            DeviceKind.AIRPLAY -> "غير مدعوم — جهاز آبل"
            DeviceKind.DLNA -> "إرسال ملفات وسائط فقط (مرحلة لاحقة) — لا عكس شاشة"
            DeviceKind.OTHER -> "لا يدعم عكس الشاشة من التطبيق — جرّب عكس الشاشة من إعدادات النظام"
        }

    fun merge(other: DiscoveredDevice): DiscoveredDevice =
        DiscoveredDevice(id, name, kind, host, port, other.details + details, kinds + other.kinds)
}
