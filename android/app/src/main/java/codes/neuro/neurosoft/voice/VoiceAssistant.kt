package codes.neuro.neurosoft.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import codes.neuro.neurosoft.Lang
import codes.neuro.neurosoft.Prefs
import codes.neuro.neurosoft.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Кнопка на кулоне -> вопрос голосом -> ответ голосом в наушник.
 *
 * 1 нажатие: сигнал «слушаю», звук кулона идёт на сервер напрямую (/ws/ask), минуя очередь записи.
 * Сервер сам понимает, что вы договорили (пауза), — сигнал «принял», затем ответ голосом.
 * Повторное нажатие во время вопроса — «я закончил»; во время ответа — остановить ответ.
 * Нет связи — говорим об этом и ставим вопрос в очередь: агент ответит позже в чате.
 * Голос — системный синтезатор речи Android: бесплатно, без задержки, играет в подключённые наушники.
 */
class VoiceAssistant(
    private val app: Context,
    /** Контекст с языком приложения — для фраз ассистента. */
    private val strings: () -> Context,
    private val prefs: Prefs,
    private val deviceId: () -> String?,
    private val codec: () -> Int,
    private val onOffline: (tapTs: Long) -> Unit,
) {
    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

    data class State(
        val phase: Phase = Phase.IDLE,
        val question: String? = null,
        val answer: String? = null,
        val level: String? = null,
        val pending: Boolean = false,
        val at: Long = 0,
        val ttsReady: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = null
    private var tones: ToneGenerator? = null
    private var focus: AudioFocusRequest? = null

    private var ws: WebSocket? = null
    private var open = false
    private var session = 0
    private var tapTs = 0L
    private val pre = ArrayList<ByteArray>()
    private val watchdog = Runnable { timedOut() }

    fun init() {
        tts = TextToSpeech(app) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                tts?.setLanguage(Lang.locale(Lang.code(prefs)))
                tts?.setSpeechRate(prefs.voiceRate)
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = spoken()

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = spoken()
                })
            }
            update { it.copy(ttsReady = ok) }
        }
        tones = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        } catch (e: RuntimeException) {
            null
        }
    }

    // ------------------------------------------------------------------ события кнопки и звука (поток BLE)
    @Synchronized
    fun onTap(buffered: List<ByteArray>, ts: Long) {
        when (_state.value.phase) {
            Phase.LISTENING -> sendStop()
            Phase.SPEAKING -> {
                tts?.stop()
                spoken()
            }
            Phase.THINKING -> tone(ToneGenerator.TONE_PROP_ACK)
            Phase.IDLE -> start(buffered, ts)
        }
    }

    @Synchronized
    fun onFrame(frame: ByteArray) {
        if (_state.value.phase != Phase.LISTENING) return
        val socket = ws
        if (open && socket != null) socket.send(frame.toByteString())
        else if (pre.size < 1000) pre.add(frame)
    }

    /** Короткое голосовое подтверждение («Запомнил»). */
    fun confirm(text: String) {
        if (_state.value.phase == Phase.IDLE) speak(text, keepAnswer = true)
    }

    fun testVoice() = speak(str(R.string.voice_test), keepAnswer = true)

    /** Язык голоса следует за языком приложения. */
    fun applyLanguage() {
        tts?.setLanguage(Lang.locale(Lang.code(prefs)))
    }

    private fun str(id: Int): String = strings().getString(id)

    fun setRate(rate: Float) {
        prefs.voiceRate = rate
        tts?.setSpeechRate(rate)
    }

    // ------------------------------------------------------------------ вопрос
    private fun start(buffered: List<ByteArray>, ts: Long) {
        val server = prefs.serverUrl
        val device = deviceId()
        if (server.isBlank() || prefs.token.isBlank() || device == null) {
            speak(str(R.string.voice_no_server))
            return
        }
        session++
        val my = session
        tapTs = ts
        pre.clear()
        pre.addAll(buffered)
        open = false
        update { State(phase = Phase.LISTENING, at = System.currentTimeMillis(), ttsReady = it.ttsReady) }
        tone(ToneGenerator.TONE_PROP_BEEP)
        val url = server.trimEnd('/') + "/ws/ask?device_id=" + URLEncoder.encode(device, "UTF-8")
        val request = try {
            Request.Builder().url(url).header("Authorization", "Bearer ${prefs.token}").build()
        } catch (e: IllegalArgumentException) {
            fail(str(R.string.voice_bad_url), offline = false)
            return
        }
        ws = client.newWebSocket(request, Listener(my))
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 45_000)
    }

    private fun sendStop() {
        ws?.send(JSONObject().put("type", "stop").toString())
    }

    private inner class Listener(private val my: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@VoiceAssistant) {
                if (my != session) return
                webSocket.send(JSONObject().put("type", "start").put("id", "q_$tapTs").put("codec", codec())
                    .put("lang", Lang.code(prefs)).toString())
                pre.forEach { webSocket.send(it.toByteString()) }
                pre.clear()
                open = true
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try {
                JSONObject(text)
            } catch (e: Exception) {
                return
            }
            synchronized(this@VoiceAssistant) {
                if (my != session) return
                when (msg.optString("type")) {
                    "heard" -> {
                        update { it.copy(phase = Phase.THINKING) }
                        tone(ToneGenerator.TONE_PROP_ACK)
                    }
                    "answer" -> {
                        main.removeCallbacks(watchdog)
                        val answer = msg.optString("answer").ifBlank { str(R.string.voice_no_answer) }
                        update {
                            it.copy(
                                question = msg.optString("question").ifBlank { null },
                                answer = answer,
                                level = msg.optString("level").ifBlank { null },
                                pending = msg.optBoolean("pending"),
                            )
                        }
                        webSocket.close(1000, null)
                        ws = null
                        speak(answer, keepAnswer = true)
                    }
                    "error" -> fail(msg.optString("message").ifBlank { str(R.string.voice_server_error) }, offline = false)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(this@VoiceAssistant) {
                if (my != session) return
                val phase = _state.value.phase
                if (phase == Phase.LISTENING || phase == Phase.THINKING) {
                    val auth = response?.code == 401 || response?.code == 403
                    fail(str(if (auth) R.string.voice_auth else R.string.voice_offline), offline = !auth)
                }
            }
        }
    }

    private fun timedOut() {
        synchronized(this) {
            val phase = _state.value.phase
            if (phase == Phase.LISTENING || phase == Phase.THINKING) {
                fail(str(R.string.voice_slow), offline = false)
            }
        }
    }

    private fun fail(text: String, offline: Boolean) {
        main.removeCallbacks(watchdog)
        session++ // старые ответы этого вопроса больше не нужны
        ws?.cancel()
        ws = null
        pre.clear()
        tone(ToneGenerator.TONE_PROP_NACK)
        if (offline) onOffline(tapTs)
        update { it.copy(answer = text) }
        speak(text, keepAnswer = true)
    }

    // ------------------------------------------------------------------ звук
    private fun speak(text: String, keepAnswer: Boolean = false) {
        val engine = tts
        if (engine == null || !_state.value.ttsReady) {
            update { it.copy(phase = Phase.IDLE, answer = if (keepAnswer) text else it.answer) }
            return
        }
        update { it.copy(phase = Phase.SPEAKING) }
        requestFocus()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "neuro_${System.nanoTime()}")
    }

    private fun spoken() {
        abandonFocus()
        update { if (it.phase == Phase.SPEAKING) it.copy(phase = Phase.IDLE) else it }
    }

    private fun tone(type: Int) {
        if (!prefs.earcons) return
        try {
            tones?.startTone(type, 120)
        } catch (e: RuntimeException) {
            Log.w("Mnemo.Voice", "tone failed", e)
        }
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        focus = req
        audio?.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun update(f: (State) -> State) {
        _state.value = f(_state.value)
    }
}
