package com.example.screencaster

import android.content.Context

/** مستويات الدقة للمرسل. الضلع الأطول بالبكسل (0 = دقة الشاشة الأصلية) ومعدل البت. يُحفظ آخر اختيار، ويعود إلى 720p عند أي عطل. */
class Quality(val label: String, val longSide: Int, val bitrate: Int)

object Qualities {
    val list = listOf(
        Quality("720p — ٥ ميجابت (الافتراضي)", 1280, 5_000_000),
        Quality("900p — ٨ ميجابت", 1600, 8_000_000),
        Quality("1080p — ١٠ ميجابت", 1920, 10_000_000),
        Quality("1080p — ١٥ ميجابت (أوضح، أثقل)", 1920, 15_000_000),
        Quality("الأصلية — ٢٠ ميجابت", 0, 20_000_000),
    )
    private const val KEY = "quality_index"

    fun index(ctx: Context): Int =
        ctx.getSharedPreferences("aakes", Context.MODE_PRIVATE).getInt(KEY, 0).coerceIn(0, list.size - 1)

    fun set(ctx: Context, i: Int) {
        ctx.getSharedPreferences("aakes", Context.MODE_PRIVATE).edit().putInt(KEY, i.coerceIn(0, list.size - 1)).apply()
    }

    fun current(ctx: Context): Quality = list[index(ctx)]

    /** يعيد الدقة إلى 720p (يُستدعى عند تعطّل البث). يعيد true إن كان هناك ما يُعاد. */
    fun resetToDefault(ctx: Context): Boolean {
        if (index(ctx) == 0) return false
        set(ctx, 0); return true
    }
}
