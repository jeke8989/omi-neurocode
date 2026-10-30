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
    /** Код ошибки: bad_url, auth, not_found, server:<код>, closed:<код> или текст исключения. */
    val error: String? = null,
    /** Время самого старого неотправленного файла (0 — очередь пуста). */
    val oldestAt: Long = 0,
    /** Всего отправлено байт за время работы (для скорости сети). */
    val sentBytes: Long = 0,
    /** Экономия батареи: соединение закрыто намеренно до следующей пачки (это не ошибка). */
    val resting: Boolean = false,
    /** Когда уйдёт следующая пачка (0 — не ждём). */
    val nextBurstAt: Long = 0,
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
    private val sentCounter = java.util.concurrent.atomic.AtomicLong()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var nextAttemptAt = 0L
    @Volatile private var failures = 0
    @Volatile private var status = UploadStatus()
    @Volatile private var urgent = false

    /**
     * Экономия батареи: пока приложение свёрнуто, записи уходят пачками раз в [BURST_MS], а между пачками
     * соединение закрыто — радиомодуль телефона спит. Вопросы с кнопки идут своим соединением и сразу.
     */
    @Volatile var batchMode: () -> Boolean = { false }

    /** Отправить сразу, не дожидаясь пачки (вопрос с кнопки без связи). */
    fun urgent() {
        urgent = true
        kick()
    }

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
        val oldest = spool.pending().firstOrNull()?.let { AudioSpool.timeOf(it) } ?: 0L
        val server = prefs.serverUrl
        val token = prefs.token
        val device = deviceId()
        val configured = server.isNotBlank() && token.isNotBlank() && device != null
        val now = System.currentTimeMillis()
        val batching = batchMode()
        val nextBurst = if (batching && oldest > 0) oldest + BURST_MS else 0L
        publish(status.copy(configured = configured, pendingFiles = files, pendingBytes = bytes, oldestAt = oldest,
            sentBytes = sentCounter.get(), nextBurstAt = if (!open) nextBurst else 0L,
            resting = status.resting && batching && !open))
        if (!configured) return
        if (!open) {
            // в экономном режиме подключаемся, только когда накопилась пачка (или есть срочное)
            val due = !batching || urgent || (oldest > 0 && now >= oldest + BURST_MS) || files >= BURST_FILES
            if (ws == null && device != null && now >= nextAttemptAt && due) {
                urgent = false
                connect(server, token, device)
            }
            return
        }
        sendPending()
        if (batching && !urgent && inflight.isEmpty() && spool.pending().isEmpty()) rest()
    }

    /** Пачка отправлена и подтверждена — закрываем соединение до следующей. */
    private fun rest() {
        val old = ws ?: return
        ws = null
        open = false
        old.close(1000, "rest")
        publish(status.copy(connected = false, connecting = false, resting = true, error = null))
    }

    private fun connect(server: String, token: String, device: String) {
        val url = server.trimEnd('/') + "/ws/audio?device_id=" + URLEncoder.encode(device, "UTF-8")
        val request = try {
            Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        } catch (e: IllegalArgumentException) {
            nextAttemptAt = System.currentTimeMillis() + 15_000
            publish(status.copy(connecting = false, error = "bad_url"))
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
            sentCounter.addAndGet(f.length())
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
            publish(status.copy(connected = true, connecting = false, error = null, resting = false))
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
                "error" -> publish(status.copy(error = msg.optString("error")))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            dropped(webSocket, "closed:$code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            val reason = when (code) {
                401, 403 -> "auth"
                404 -> "not_found"
                null -> t.message ?: t.javaClass.simpleName
                else -> "server:$code"
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
        publish(status.copy(connected = false, connecting = false, error = reason, resting = false))
        kick()
    }

    @Synchronized
    private fun publish(s: UploadStatus) {
        status = s
        onStatus(s)
    }

    companion object {
        private const val TAG = "Mnemo.Upload"
        private const val MAX_INFLIGHT = 32
        const val BURST_MS = 2 * 60_000L
        private const val BURST_FILES = 150
    }
}
