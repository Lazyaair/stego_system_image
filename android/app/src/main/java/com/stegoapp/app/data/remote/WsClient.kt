package com.stegoapp.app.data.remote

import com.google.gson.Gson
import com.stegoapp.app.api.ApiClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import java.util.concurrent.TimeUnit

data class WsMessage(
    val type: String,
    val id: String? = null,
    val timestamp: Long? = null,
    val payload: Map<String, Any?>? = null
)

class WsClient {
    private var ws: WebSocket? = null
    private val gson = Gson()
    private var shouldReconnect = false
    private var reconnectAttempts = 0
    private var token: String = ""

    private val _messages = MutableSharedFlow<WsMessage>(extraBufferCapacity = 64)
    val messages = _messages.asSharedFlow()

    private val _connected = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
    val connected = _connected.asSharedFlow()

    private val _kicked = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val kicked = _kicked.asSharedFlow()

    fun connect(token: String) {
        this.token = token
        shouldReconnect = true
        reconnectAttempts = 0
        doConnect()
    }

    private fun doConnect() {
        val baseUrl = ApiClient.getBaseUrl()
            .replace("http://", "ws://")
            .replace("https://", "wss://")
        val url = "${baseUrl}ws?token=$token"

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(url).build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                _connected.tryEmit(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = gson.fromJson(text, WsMessage::class.java)
                    _messages.tryEmit(msg)
                } catch (_: Exception) {}
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Server initiated close — complete the handshake
                webSocket.close(code, reason)
                _connected.tryEmit(false)
                if (code == 4001) {
                    shouldReconnect = false
                    _kicked.tryEmit(Unit)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connected.tryEmit(false)
                if (code == 4001) {
                    shouldReconnect = false
                    _kicked.tryEmit(Unit)
                } else {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connected.tryEmit(false)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectAttempts >= 10) return
        val delay = minOf(1000L * (1 shl reconnectAttempts), 30000L)
        reconnectAttempts++
        Thread {
            Thread.sleep(delay)
            if (shouldReconnect) doConnect()
        }.start()
    }

    fun send(message: WsMessage) {
        ws?.send(gson.toJson(message))
    }

    fun disconnect() {
        shouldReconnect = false
        ws?.close(1000, "Bye")
        ws = null
    }

    companion object {
        val instance = WsClient()
    }
}
