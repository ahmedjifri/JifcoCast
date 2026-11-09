package com.example.screencaster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** حالة البث الجارية — تكتبها خدمة البث وتقرؤها الواجهة. */
object CastState {
    var active by mutableStateOf(false)
    var connecting by mutableStateOf(false)
    var target by mutableStateOf("")
    var status by mutableStateOf("")
    var frames by mutableStateOf(0L)
    var kbps by mutableStateOf(0)
    var seconds by mutableStateOf(0L)
    var size by mutableStateOf("")
    var audio by mutableStateOf("")

    fun reset() {
        active = false
        connecting = false
        frames = 0
        kbps = 0
        seconds = 0
        size = ""
        audio = ""
    }
}
