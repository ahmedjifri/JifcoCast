package com.example.screencaster

import android.os.Build
import android.view.Surface
import com.example.screencaster.shared.DeviceInfo
import com.example.screencaster.shared.Message
import com.example.screencaster.shared.Protocol
import com.example.screencaster.shared.readMessage
import com.example.screencaster.shared.writeMessage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * خادم المستقبل: ينتظر اتصال المرسل، يتبادل التعريف، ثم يستقبل إعدادات المشفّر والإطارات
 * ويمرّرها إلى فاكّ الفيديو. مرسل واحد في كل مرة.
 */
class ReceiverServer(
    private val surfaceProvider: () -> Surface?,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(text: String)
        fun onConnected(peer: DeviceInfo)
        fun onStreaming(peer: DeviceInfo)
        fun onResize(width: Int, height: Int)
        fun onMode(mode: String)
        fun onAudio(ok: Boolean)
        fun onIdle()
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var current: Socket? = null

    var port: Int = 0
        private set

    fun start(): Int {
        val ss = try {
            ServerSocket(Protocol.DEFAULT_PORT)
        } catch (_: IOException) {
            ServerSocket(0)
        }
        serverSocket = ss
        port = ss.localPort
        running = true
        Thread({ acceptLoop(ss) }, "receiver-accept").start()
        return port
    }

    fun stop() {
        running = false
        try { current?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun me(): DeviceInfo = DeviceInfo(
        name = "Jifco Cast على ${Build.MANUFACTURER} ${Build.MODEL}",
        model = Build.MODEL,
        role = "receiver",
        version = Protocol.VERSION,
        width = screenWidth,
        height = screenHeight
    )

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val client = try {
                ss.accept()
            } catch (_: IOException) {
                break
            }
            current = client
            handle(client)
            current = null
        }
    }

    private fun handle(client: Socket) {
        var decoder: VideoDecoder? = null
        var audio: AudioPlayer? = null
        var frames = 0L
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000
            val inp = DataInputStream(BufferedInputStream(client.getInputStream(), 1 shl 16))
            val out = DataOutputStream(BufferedOutputStream(client.getOutputStream()))

            val hello = inp.readMessage()
            if (hello.type != Protocol.MSG_HELLO) return
            var peer = DeviceInfo.fromJson(String(hello.payload, Charsets.UTF_8))
            var lastConfig: ByteArray? = null
            listener.onConnected(peer)
            listener.onStatus("متصل بـ ${peer.name}\nينتظر بدء البث…")
            out.writeMessage(Message(Protocol.MSG_HELLO_ACK, me().toJson().toByteArray(Charsets.UTF_8)))

            while (running) {
                val m = inp.readMessage()
                when (m.type) {
                    Protocol.MSG_CONFIG -> {
                        val same = lastConfig != null && lastConfig!!.contentEquals(m.payload) && decoder != null
                        if (same) {
                            decoder?.feed(m.payload, 0L, true)
                        } else {
                            val surface = surfaceProvider()
                            if (surface != null) {
                                decoder?.release()
                                decoder = VideoDecoder(surface).also {
                                    it.start(peer.width, peer.height)
                                    it.feed(m.payload, 0L, true)
                                }
                                lastConfig = m.payload
                                listener.onStreaming(peer)
                            } else {
                                listener.onStatus("لا يوجد سطح عرض جاهز")
                            }
                        }
                    }
                    Protocol.MSG_RESIZE -> {
                        val info = DeviceInfo.fromJson(String(m.payload, Charsets.UTF_8))
                        peer = DeviceInfo(peer.name, peer.model, peer.role, peer.version, info.width, info.height)
                        lastConfig = null
                        decoder?.release(); decoder = null
                        listener.onResize(info.width, info.height)
                    }
                    Protocol.MSG_MODE -> listener.onMode(String(m.payload, Charsets.UTF_8))
                    Protocol.MSG_FRAME -> {
                        decoder?.feed(m.payload, m.timestampUs, false)
                        frames++
                    }
                    Protocol.MSG_AUDIO_CONFIG -> {
                        val o = org.json.JSONObject(String(m.payload, Charsets.UTF_8))
                        audio?.release()
                        audio = try {
                            AudioPlayer(o.optInt("sampleRate", Protocol.AUDIO_SAMPLE_RATE), o.optInt("channels", Protocol.AUDIO_CHANNELS)).also { it.start() }
                        } catch (_: Exception) { null }
                        listener.onAudio(audio != null)
                    }
                    Protocol.MSG_AUDIO -> audio?.feed(m.payload)
                    Protocol.MSG_PING -> { /* نبضة حياة من المرسل — لا ردّ كي لا يمتلئ مخزن المرسل */ }
                    Protocol.MSG_BYE -> return
                    else -> { /* تجاهل */ }
                }
            }
        } catch (_: Exception) {
            // انقطاع أو مهلة — نعود للانتظار
        } finally {
            audio?.release()
            decoder?.release()
            try { client.close() } catch (_: Exception) {}
            listener.onIdle()
        }
    }
}
