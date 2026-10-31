package codes.neuro.neurosoft.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import codes.neuro.neurosoft.Lang
import codes.neuro.neurosoft.Prefs
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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
        /** Что агент делает сейчас («🔎 Ищу в интернете…»), пока вы ждёте ответ. */
        val progress: String? = null,
        val cancelled: Boolean = false,
        /** Запуск агента на сервере: долгий ответ потом берём из чата по нему. */
        val runId: Long? = null,
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
    // вопрос идёт до второго нажатия: через 90 с — тихий сигнал «я ещё слушаю», через 2 мин — конец
    private val listenWarn = Runnable { synchronized(this) { if (_state.value.phase == Phase.LISTENING) tone(ToneGenerator.TONE_PROP_BEEP2) } }
    private val listenLimit = Runnable { synchronized(this) { if (_state.value.phase == Phase.LISTENING) sendStop() } }
    // пока агент ищет ответ — тихий «тик» раз в 20 с: слышно, что он ещё работает
    private val thinkingTick: Runnable = object : Runnable {
        override fun run() {
            synchronized(this@VoiceAssistant) {
                if (_state.value.phase != Phase.THINKING) return
                tone(ToneGenerator.TONE_PROP_BEEP, 60)
                main.postDelayed(this, THINKING_TICK_MS)
            }
        }
    }

    /** Когда закончился последний озвученный ответ: звук с кулона в это время не пишем (это голос Mnemo). */
    @Volatile var lastSpokeAt = 0L
        private set

    /** Mnemo сейчас говорит (или только что закончил): кулон слышит динамик, а не людей. */
    fun speakingNow(): Boolean =
        _state.value.phase == Phase.SPEAKING || System.currentTimeMillis() - lastSpokeAt < SPEECH_TAIL_MS

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
                stopPlayer()
                spoken()
            }
            Phase.THINKING -> tone(ToneGenerator.TONE_PROP_ACK)
            Phase.IDLE -> start(buffered, ts)
        }
    }

    /** «Стоп» в приложении: пока слушаем — вопрос отменяется, пока ищем ответ — агент останавливается. */
    @Synchronized
    fun cancel() {
        when (_state.value.phase) {
            Phase.LISTENING -> {
                ws?.send(JSONObject().put("type", "cancel").toString())
                session++
                ws?.close(1000, null)
                ws = null
                pre.clear()
                clearTimers()
                tone(ToneGenerator.TONE_PROP_NACK)
                update { it.copy(phase = Phase.IDLE, progress = null) }
            }
            Phase.THINKING -> {
                val socket = ws
                if (socket != null && socket.send(JSONObject().put("type", "cancel").toString())) {
                    update { it.copy(progress = str(R.string.voice_stopping)) }
                    main.removeCallbacks(watchdog)
                    main.postDelayed(watchdog, 15_000)
                } else {
                    session++
                    clearTimers()
                    update { it.copy(phase = Phase.IDLE, progress = null, answer = str(R.string.voice_stopped), cancelled = true) }
                }
            }
            Phase.SPEAKING -> {
                tts?.stop()
                stopPlayer()
                spoken()
            }
            Phase.IDLE -> Unit
        }
    }

    /** Убрать карточку ответа с главной. */
    fun dismissAnswer() = update { it.copy(answer = null, question = null, pending = false, progress = null) }

    private fun clearTimers() {
        main.removeCallbacks(watchdog)
        main.removeCallbacks(listenWarn)
        main.removeCallbacks(listenLimit)
        main.removeCallbacks(thinkingTick)
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
        if (_state.value.phase == Phase.IDLE) speak(text, keepAnswer = true, notify = false)
    }

    fun testVoice() = speak(str(R.string.voice_test), keepAnswer = true, force = true)

    /** Ответ готов, но вслух не озвучен (настройка «только в наушниках» без наушников или «не озвучивать»). */
    @Volatile var onSilentAnswer: () -> Unit = {}

    private val headphoneTypes: Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        if (Build.VERSION.SDK_INT >= 28) add(AudioDeviceInfo.TYPE_HEARING_AID)
        if (Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /** Подключены ли наушники (проводные, Bluetooth, USB) или аудиосистема машины. */
    fun headphonesConnected(): Boolean = try {
        audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { it.type in headphoneTypes } == true
    } catch (e: Exception) {
        false
    }

    /** Можно ли сейчас говорить вслух: always — всегда, headphones — только в наушниках, off — никогда. */
    fun mayTalk(): Boolean = when (prefs.voiceReply) {
        "off" -> false
        "headphones" -> headphonesConnected()
        else -> true
    }

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
        speakSeq++ // недоговорённое подтверждение не должно прозвучать поверх нового вопроса
        stopPlayer()
        val my = session
        tapTs = ts
        pre.clear()
        pre.addAll(buffered)
        open = false
        update { State(phase = Phase.LISTENING, at = System.currentTimeMillis(), ttsReady = it.ttsReady) }
        main.removeCallbacks(thinkingTick)
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
        main.postDelayed(watchdog, LISTEN_LIMIT_MS + 45_000)
        main.removeCallbacks(listenWarn)
        main.removeCallbacks(listenLimit)
        main.postDelayed(listenWarn, LISTEN_WARN_MS)
        main.postDelayed(listenLimit, LISTEN_LIMIT_MS)
    }

    private fun sendStop() {
        ws?.send(JSONObject().put("type", "stop").toString())
    }

    private inner class Listener(private val my: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@VoiceAssistant) {
                if (my != session) return
                webSocket.send(JSONObject().put("type", "start").put("id", "q_$tapTs").put("codec", codec())
                    .put("lang", Lang.code(prefs)).put("mode", "press")
                    // нейроголос: сервер начнёт озвучивать ответ сразу, как он будет готов
                    .put("voice", if (prefs.ttsVoice == "system") "" else prefs.ttsVoice)
                    .put("rate", prefs.voiceRate.toDouble()).toString())
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
                        main.removeCallbacks(listenWarn)
                        main.removeCallbacks(listenLimit)
                        main.removeCallbacks(watchdog)
                        // сервер ждёт ответ агента до 4 минут и присылает шаги; молчит дольше — что-то не так
                        main.postDelayed(watchdog, THINK_LIMIT_MS)
                        update { it.copy(phase = Phase.THINKING, progress = null, answer = null, cancelled = false) }
                        tone(ToneGenerator.TONE_PROP_ACK)
                        main.removeCallbacks(thinkingTick)
                        main.postDelayed(thinkingTick, THINKING_TICK_MS)
                    }
                    "question" -> update {
                        it.copy(question = msg.optString("question").ifBlank { null },
                            level = msg.optString("level").ifBlank { null },
                            runId = msg.optLong("run_id").takeIf { id -> id > 0 })
                    }
                    "progress" -> {
                        main.removeCallbacks(watchdog)
                        main.postDelayed(watchdog, THINK_LIMIT_MS)
                        update { it.copy(progress = msg.optString("text").ifBlank { null }) }
                    }
                    "answer" -> {
                        main.removeCallbacks(watchdog)
                        main.removeCallbacks(thinkingTick)
                        val answer = msg.optString("answer").ifBlank { str(R.string.voice_no_answer) }
                        val cancelled = msg.optBoolean("cancelled")
                        update {
                            it.copy(
                                question = msg.optString("question").ifBlank { it.question },
                                answer = answer,
                                level = msg.optString("level").ifBlank { null },
                                pending = msg.optBoolean("pending"),
                                progress = null,
                                cancelled = cancelled,
                                runId = msg.optLong("run_id").takeIf { id -> id > 0 } ?: it.runId,
                            )
                        }
                        webSocket.close(1000, null)
                        ws = null
                        if (cancelled) {
                            tone(ToneGenerator.TONE_PROP_NACK)
                            update { it.copy(phase = Phase.IDLE) }
                        } else {
                            speak(answer, keepAnswer = true)
                        }
                    }
                    "error" -> fail(msg.optString("message").ifBlank { str(R.string.voice_server_error) }, offline = false)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(this@VoiceAssistant) {
                if (my != session) return
                val phase = _state.value.phase
                val auth = response?.code == 401 || response?.code == 403
                if (phase == Phase.LISTENING) {
                    fail(str(if (auth) R.string.voice_auth else R.string.voice_offline), offline = !auth)
                } else if (phase == Phase.THINKING) {
                    // вопрос сервер уже получил: агент доработает, ответ будет в чате — переспрашивать не нужно
                    fail(str(if (auth) R.string.voice_auth else R.string.voice_lost), offline = false)
                    update { it.copy(pending = !auth) }
                }
            }
        }
    }

    private fun timedOut() {
        synchronized(this) {
            val phase = _state.value.phase
            if (phase == Phase.LISTENING || phase == Phase.THINKING) {
                fail(str(if (phase == Phase.THINKING) R.string.voice_lost else R.string.voice_slow), offline = false)
                if (phase == Phase.THINKING) update { it.copy(pending = true) }
            }
        }
    }

    private fun fail(text: String, offline: Boolean) {
        clearTimers()
        session++ // старые ответы этого вопроса больше не нужны
        ws?.cancel()
        ws = null
        pre.clear()
        tone(ToneGenerator.TONE_PROP_NACK)
        if (offline) onOffline(tapTs)
        update { it.copy(answer = text, progress = null) }
        speak(text, keepAnswer = true)
    }

    // ------------------------------------------------------------------ звук
    private fun speak(text: String, keepAnswer: Boolean = false, force: Boolean = false, notify: Boolean = true) {
        if (!force && !mayTalk()) {
            // тихий режим: ответ остаётся в приложении и в чате, кулон коротко вибрирует
            update { it.copy(phase = Phase.IDLE, answer = if (keepAnswer) text else it.answer) }
            if (notify) onSilentAnswer()
            return
        }
        if (keepAnswer) update { it.copy(answer = text) }
        val voice = prefs.ttsVoice
        val my = ++speakSeq
        if (voice == "system" || !prefs.serverConfigured) {
            speakSystem(text)
            return
        }
        // нейроголос с сервера; нет сети или сервер ещё качает модель — системный голос, без паузы дольше 6 с
        bg.launch {
            val wav = withTimeoutOrNull(6_000) { Api.tts(text, voice, Lang.code(prefs), prefs.voiceRate) }
            main.post {
                synchronized(this@VoiceAssistant) {
                    if (my != speakSeq) return@post
                    if (wav == null || !playWav(wav)) speakSystem(text)
                }
            }
        }
    }

    @Volatile private var speakSeq = 0
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var player: MediaPlayer? = null

    private fun playWav(data: ByteArray): Boolean = try {
        val file = File(app.cacheDir, "mnemo-voice.wav")
        file.writeBytes(data)
        stopPlayer()
        val mp = MediaPlayer()
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        mp.setDataSource(file.absolutePath)
        mp.setOnCompletionListener { synchronized(this) { stopPlayer(); spoken() } }
        mp.setOnErrorListener { _, _, _ -> synchronized(this) { stopPlayer(); spoken() }; true }
        mp.prepare()
        update { it.copy(phase = Phase.SPEAKING) }
        requestFocus()
        mp.start()
        player = mp
        true
    } catch (e: Exception) {
        Log.w("Mnemo.Voice", "neural voice playback failed", e)
        stopPlayer()
        false
    }

    private fun stopPlayer() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    private fun speakSystem(text: String) {
        val engine = tts
        if (engine == null || !_state.value.ttsReady) {
            update { it.copy(phase = Phase.IDLE) }
            return
        }
        update { it.copy(phase = Phase.SPEAKING) }
        requestFocus()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "neuro_${System.nanoTime()}")
    }

    private fun spoken() {
        lastSpokeAt = System.currentTimeMillis()
        abandonFocus()
        update { if (it.phase == Phase.SPEAKING) it.copy(phase = Phase.IDLE) else it }
    }

    private fun tone(type: Int, ms: Int = 120) {
        if (!prefs.earcons || !mayTalk()) return
        try {
            tones?.startTone(type, ms)
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

    companion object {
        const val LISTEN_WARN_MS = 90_000L
        const val LISTEN_LIMIT_MS = 120_000L
        /** Сервер ждёт агента до 4 минут; без вестей дольше — считаем, что связь пропала. */
        const val THINK_LIMIT_MS = 270_000L
        const val THINKING_TICK_MS = 20_000L
        /** Хвост после ответа: динамик ещё звучит, а «готово» уже пришло. */
        const val SPEECH_TAIL_MS = 800L
    }
}
