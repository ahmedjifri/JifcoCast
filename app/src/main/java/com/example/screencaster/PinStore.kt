package com.example.screencaster

import android.content.Context
import com.example.screencaster.shared.Protocol

/** كلمات السر: كلمة هذا الجهاز كمستقبل (افتراضية 1234)، وكلمات المستقبلات المحفوظة عند المرسل لكل عنوان. */
object PinStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("aakes", Context.MODE_PRIVATE)

    fun receiverPin(ctx: Context): String = prefs(ctx).getString("receiver_pin", Protocol.DEFAULT_PIN) ?: Protocol.DEFAULT_PIN
    fun setReceiverPin(ctx: Context, pin: String) { prefs(ctx).edit().putString("receiver_pin", pin).apply() }

    fun savedPin(ctx: Context, host: String): String? = prefs(ctx).getString("pin_$host", null)
    fun savePin(ctx: Context, host: String, pin: String) { prefs(ctx).edit().putString("pin_$host", pin).apply() }
    fun forgetPin(ctx: Context, host: String) { prefs(ctx).edit().remove("pin_$host").apply() }
}
