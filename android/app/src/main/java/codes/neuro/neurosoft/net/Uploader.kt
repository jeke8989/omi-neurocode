package codes.neuro.neurosoft.net

import android.util.Log
import codes.neuro.neurosoft.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class UploadStatus(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val pendingFiles: Int = 0,
    val pendingBytes: Long = 0,
    val lastAckAt: Long = 0,
    val error: String? = null,
)

/**
 * Отправляет очередь с диска на сервер по WebSocket. Каждый файл удаляется только после
 * подтверждения {"type":"ack","id":...}. После обрыва всё неподтверждённое отправляется заново
 * (сервер отбрасывает дубли).
 */
class Uploader(
    private val spool: AudioSpool,
    private val prefs: Prefs,
    private val deviceId: () -> String?,
    private val helloInfo: () -> JSONObject,
    private val onStatus: (UploadStatus) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val inflight = ConcurrentHashMap<String, File>()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var nextAttemptAt = 0L
    @Volatile private var failures = 0
    @Volatile private var status = UploadStatus()

    fun start() {
        scope.launch {
            while (true) {
                try {
                    step()
                } catch (e: Exception) {
                    Log.w(TAG, "upload step failed", e)
                    publish(status.copy(error = e.message))
                }
                withTimeoutOrNull(3_000) { wake.receive() }
            }
        }
    }

    fun kick() {
        wake.trySend(Unit)
    }

    /** Переподключиться сразу (сменили адрес сервера, токен или кулон). */
    fun reconnectNow() {
        val old = ws
        ws = null
        open = false
        inflight.clear()
        old?.cancel()
        failures = 0
        nextAttemptAt = 0
        kick()
    }

    private fun step() {
        val (files, bytes) = spool.stats()
        val server = prefs.serverUrl
        val token = prefs.token
        val device = deviceId()
        val configured = server.isNotBlank() && token.isNotBlank() && device != null
        publish(status.copy(configured = configured, pendingFiles = files, pendingBytes = bytes))
        if (!configured) return
        if (!open) {
            if (ws == null && device != null && System.currentTimeMillis() >= nextAttemptAt) {
                connect(server, token, device)
            }
            return
        }
        sendPending()
    }

    private fun connect(server: String, token: String, device: String) {
        val url = server.trimEnd('/') + "/ws/audio?device_id=" + URLEncoder.encode(device, "UTF-8")
        val request = try {
            Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        } catch (e: IllegalArgumentException) {
            nextAttemptAt = System.currentTimeMillis() + 15_000
            publish(status.copy(connecting = false, error = "Неверный адрес сервера"))
            return
        }
        publish(status.copy(connecting = true))
        ws = client.newWebSocket(request, listener)
    }

    private fun sendPending() {
        val socket = ws ?: return
        for (f in spool.pending()) {
            if (inflight.size >= MAX_INFLIGHT) break
            val id = AudioSpool.ackId(f)
            if (inflight.containsKey(id)) continue
            val sent = try {
                if (f.name.endsWith(".bin")) {
                    val bytes = f.readBytes()
                    if (bytes.size < 10) {
                        f.delete()
                        continue
                    }
                    socket.send(bytes.toByteString())
                } else {
                    socket.send(f.readText())
                }
            } catch (e: java.io.IOException) {
                continue // файл удалили между листингом и чтением
            }
            if (!sent) break
            inflight[id] = f
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== ws) return
            open = true
            failures = 0
            inflight.clear()
            webSocket.send(JSONObject().put("type", "hello").put("info", helloInfo()).toString())
            publish(status.copy(connected = true, connecting = false, error = null))
            kick()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            val msg = try {
                JSONObject(text)
            } catch (e: Exception) {
                return
            }
            when (msg.optString("type")) {
                "ack" -> {
                    inflight.remove(msg.optString("id"))?.delete()
                    publish(status.copy(lastAckAt = System.currentTimeMillis()))
                    kick()
                }
                "error" -> publish(status.copy(error = "Сервер: " + msg.optString("error")))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            dropped(webSocket, "соединение закрыто ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            val reason = when (code) {
                401, 403 -> "Сервер не принял токен"
                404 -> "На этом адресе нет сервера NeuroSoft"
                null -> t.message ?: t.javaClass.simpleName
                else -> "Ошибка сервера $code"
            }
            dropped(webSocket, reason)
        }
    }

    private fun dropped(webSocket: WebSocket, reason: String) {
        if (webSocket !== ws) return
        ws = null
        open = false
        inflight.clear()
        failures++
        nextAttemptAt = System.currentTimeMillis() + min(60_000L, 2_000L shl min(failures, 5))
        publish(status.copy(connected = false, connecting = false, error = reason))
        kick()
    }

    @Synchronized
    private fun publish(s: UploadStatus) {
        status = s
        onStatus(s)
    }

    companion object {
        private const val TAG = "NeuroSoft.Upload"
        private const val MAX_INFLIGHT = 32
    }
}
