package codes.neuro.neurosoft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import codes.neuro.neurosoft.ble.ConnState
import codes.neuro.neurosoft.ble.FoundDevice
import codes.neuro.neurosoft.ble.FrameAssembler
import codes.neuro.neurosoft.ble.OmiBle
import codes.neuro.neurosoft.ble.OmiProtocol
import codes.neuro.neurosoft.ble.OmiScanner
import codes.neuro.neurosoft.diary.LocationTracker
import codes.neuro.neurosoft.diary.PhotoSync
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.AudioSpool
import codes.neuro.neurosoft.net.UploadStatus
import codes.neuro.neurosoft.net.Uploader
import codes.neuro.neurosoft.voice.VoiceAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

data class DeviceStatus(
    val address: String? = null,
    val name: String? = null,
    val state: ConnState = ConnState.DISCONNECTED,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val codec: Int? = null,
    val firmware: String? = null,
    val model: String? = null,
    val hardware: String? = null,
    val manufacturer: String? = null,
    val mtu: Int = 23,
    val frames: Long = 0,
    val lostPackets: Long = 0,
    val lastAudioAt: Long = 0,
    val lastButton: Int? = null,
    val lastButtonAt: Long = 0,
    val ledDim: Int? = null,
    val micGain: Int? = null,
    val caps: Set<String> = emptySet(),
    /** С какого момента кулон без связи (0 — на связи или запись выключена). */
    val disconnectedSince: Long = 0,
    val bleKbps: Double = 0.0,
    val netKbps: Double = 0.0,
    /** Средний размер кадра Opus: в тишине кадры заметно меньше (кодек с переменным битрейтом). */
    val avgFrameBytes: Int = 0,
    val heardTodayMs: Long = 0,
)

/**
 * Ядро приложения: кулон (BLE) -> сборка кадров -> пакеты по 1 секунде на диск -> выгрузка на сервер.
 * Живёт в процессе приложения; фоновую работу удерживает OmiService.
 */
object Core {
    private const val TAG = "Mnemo"
    const val NOTIFICATION_CHANNEL = "capture"
    const val NOTIFICATION_ID = 1

    private lateinit var app: Context
    lateinit var prefs: Prefs
        private set
    lateinit var spool: AudioSpool
        private set
    lateinit var uploader: Uploader
        private set
    private lateinit var ble: OmiBle
    private lateinit var scanner: OmiScanner
    lateinit var voice: VoiceAssistant
        private set
    private lateinit var location: LocationTracker

    private val _device = MutableStateFlow(DeviceStatus())
    val device: StateFlow<DeviceStatus> = _device
    private val _upload = MutableStateFlow(UploadStatus())
    val upload: StateFlow<UploadStatus> = _upload
    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused
    private val _outages = MutableStateFlow<List<Outage>>(emptyList())
    val outages: StateFlow<List<Outage>> = _outages

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameLock = Any()
    private val frames = ArrayList<ByteArray>()
    private var batchStartedAt = 0L
    // пачки, закрытые раньше секунды (перед паузой на голос Mnemo), чтобы время кадров не съехало
    private val closedBatches = ArrayList<Pair<List<ByteArray>, Long>>()
    private val bleBytes = AtomicLong()

    @Volatile private var codec = OmiProtocol.CODEC_OPUS_FS320
    private val assembler = FrameAssembler { frame -> onFrame(frame) }

    // последние ~3 с кадров: вопрос начинается с момента нажатия, а не с момента, когда кулон
    // сообщил «одиночное нажатие» (он ждёт 600 мс — вдруг будет второе)
    private val recent = ArrayDeque<Pair<Long, ByteArray>>()
    @Volatile private var pressedAt = 0L

    // точки маршрута за сутки — чтобы привязать к месту фото без GPS в EXIF
    private val track = ArrayDeque<Triple<Long, Double, Double>>()
    private var lastSentLocation: Location? = null
    private var lastPhotoSync = 0L
    private var lastNotificationText: String? = null
    private var resCode: String? = null
    private var resContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = Prefs(app)
        spool = AudioSpool(File(app.filesDir, "spool"))
        uploader = Uploader(spool, prefs, ::deviceId, ::helloInfo) { s ->
            _upload.value = s
            if (s.connected) syncLanguage()
            if (s.error == "auth") autoConnect() // ключ на сервере сменили — подключаемся заново
            updateNotification()
        }
        ble = OmiBle(app, bleListener)
        scanner = OmiScanner(app)
        voice = VoiceAssistant(app, ::res, prefs, ::deviceId, { codec }) { ts ->
            // нет связи: вопрос записан вместе со всем звуком, агент ответит в чате, когда сеть появится
            try {
                spool.writeEvent("button", OmiProtocol.BUTTON_SINGLE_TAP, ts)
                uploader.urgent()
            } catch (e: Exception) {
                Log.e(TAG, "cannot queue offline question", e)
            }
        }
        voice.init()
        voice.onSilentAnswer = {
            ble.haptic(1) // кулон коротко вибрирует: ответ в приложении
            vibrate(1)
        }
        uploader.batchMode = { prefs.batterySaver && !foreground && voice.state.value.phase == VoiceAssistant.Phase.IDLE }
        location = LocationTracker(app) { loc -> onLocation(loc) }
        _paused.value = prefs.paused
        _outages.value = prefs.outages
        _device.value = DeviceStatus(address = prefs.deviceAddress, name = prefs.deviceName,
            heardTodayMs = prefs.heardToday(LocalDate.now().toString()))
        createNotificationChannel()
        uploader.start()
        scope.launch { batchLoop() }
        scope.launch { reminderLoop() }
        if (!prefs.serverConfigured) autoConnect()
    }

    @Volatile private var foreground = false

    /** Приложение на экране: отправляем сразу, чтобы всё было свежим; свёрнуто — пачками (экономия). */
    fun setForeground(value: Boolean) {
        foreground = value
        uploader.kick()
    }

    fun setBatterySaver(value: Boolean) {
        prefs.batterySaver = value
        uploader.kick()
    }

    /** Идентификатор кулона на сервере — его Bluetooth-адрес. */
    fun deviceId(): String? = prefs.deviceAddress?.replace(":", "-")

    /** Контекст с языком приложения: для уведомления и голоса, которые живут вне экрана. */
    @Synchronized
    fun res(): Context {
        val code = Lang.code(prefs)
        val cached = resContext
        if (cached != null && code == resCode) return cached
        val ctx = Lang.wrap(app)
        resCode = code
        resContext = ctx
        return ctx
    }

    // ------------------------------------------------------------------ язык
    @Volatile private var syncedLanguage: String? = null

    /** Сменить язык приложения: голос, уведомление и тексты сервера (итоги, дневник) переходят на него. */
    fun setLanguage(code: String) {
        prefs.language = code
        voice.applyLanguage()
        createNotificationChannel() // название канала уведомлений — тоже на новом языке
        lastNotificationText = null
        updateNotification()
        syncedLanguage = null
        syncLanguage()
    }

    /** Подключили сервер: сразу переподключаемся и отправляем накопленное. */
    fun connectServer(url: String, token: String) {
        prefs.setServer(url, token)
        uploader.reconnectNow()
        syncedLanguage = null
        syncLanguage()
        updateNotification()
        _pairing.value = PairState("connected")
    }

    // ------------------------------------------------------------------ напоминания о задачах
    /** Срок задачи: вибрация кулона (два раза по три) и телефона. */
    fun reminderBuzz() {
        scope.launch {
            repeat(2) { i ->
                ble.haptic(3)
                if (i == 0) delay(900)
            }
        }
        vibrate(2)
    }

    private suspend fun reminderLoop() {
        while (true) {
            if (prefs.serverConfigured) {
                try {
                    onTasks(Api.tasks("all"))
                } catch (_: Exception) {
                }
                Morning.check(app)
            }
            delay(3 * 60_000L)
        }
    }

    /** Задачи обновились в приложении — сразу пересчитать напоминания. */
    fun onTasks(tasks: List<codes.neuro.neurosoft.net.TaskItem>) {
        try {
            Reminders.schedule(app, tasks)
        } catch (_: Exception) {
        }
        try {
            TaskPrompts.onTasks(app, tasks)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ подключение без кода
    /** idle | connecting | waiting (ждём разрешения на другом телефоне) | rejected | offline | connected */
    data class PairState(val phase: String = "idle", val requestId: String? = null)

    private val _pairing = MutableStateFlow(PairState())
    val pairing: StateFlow<PairState> = _pairing
    @Volatile private var pairJob: kotlinx.coroutines.Job? = null
    @Volatile private var pairStoppedAt = 0L

    private fun phoneName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /**
     * Первый телефон получает ключ сам; если сервер уже подключён к другому телефону — просим разрешения
     * там и ждём ответа. Запускается, когда ключа нет или сервер его не принимает (ключ сменили).
     */
    fun autoConnect(manual: Boolean = false) {
        if (pairJob?.isActive == true) return
        // отказали — не засыпаем другой телефон запросами: снова только через 10 минут или вручную
        if (!manual && _pairing.value.phase == "rejected" && System.currentTimeMillis() - pairStoppedAt < 600_000) return
        pairJob = scope.launch {
            val url = prefs.serverUrl.ifBlank { prefs.defaultServerUrl }
            if (url.isBlank()) return@launch
            var requestId: String? = null
            var wait = 5_000L
            while (!prefs.serverConfigured || _upload.value.error == "auth") {
                try {
                    if (requestId == null) {
                        _pairing.value = PairState("connecting")
                        val token = Api.pairAuto(url, phoneName())
                        if (token != null) {
                            connectServer(url, token)
                            return@launch
                        }
                        requestId = Api.pairRequest(url, phoneName())
                    }
                    _pairing.value = PairState("waiting", requestId)
                    val (status, token) = Api.pairRequestStatus(url, requestId)
                    when {
                        status == "approved" && token != null -> {
                            connectServer(url, token)
                            return@launch
                        }
                        status == "rejected" -> {
                            pairStoppedAt = System.currentTimeMillis()
                            _pairing.value = PairState("rejected")
                            return@launch
                        }
                        status != "pending" -> requestId = null // истёк — попросим снова
                    }
                    wait = 4_000L
                } catch (e: Exception) {
                    _pairing.value = PairState("offline", requestId)
                    wait = (wait * 2).coerceAtMost(60_000L)
                }
                delay(wait)
            }
            _pairing.value = PairState("connected")
        }
    }

    private fun syncLanguage() {
        val code = Lang.code(prefs)
        if (syncedLanguage == code || !prefs.serverConfigured) return
        syncedLanguage = code
        scope.launch {
            try {
                Api.setFeatures(mapOf("language" to code))
            } catch (e: Exception) {
                syncedLanguage = null // попробуем при следующем подключении
            }
        }
    }

    // ------------------------------------------------------------------ запись (сервис)
    fun startCapture(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, OmiService::class.java))
    }

    fun stopCapture(context: Context) {
        context.stopService(Intent(context, OmiService::class.java))
    }

    internal fun onServiceStarted() {
        _serviceRunning.value = true
        prefs.deviceAddress?.let { ble.connect(it) }
        applyLocationSetting()
        updateNotification()
    }

    internal fun onServiceStopped() {
        _serviceRunning.value = false
        ble.disconnect()
        location.stop()
        lastNotificationText = null
        _device.update { it.copy(disconnectedSince = 0) }
    }

    /** Пауза: звук не сохраняется и не уходит на сервер, но кнопка-вопрос работает. */
    fun setPaused(value: Boolean) {
        prefs.paused = value
        _paused.value = value
        if (value) synchronized(frameLock) { frames.clear() }
        updateNotification()
    }

    /** Включить/выключить геолокацию по настройке (дневник). */
    fun applyLocationSetting() {
        if (_serviceRunning.value && prefs.locationEnabled && Permissions.granted(app, Permissions.location())) {
            location.start()
        } else {
            location.stop()
        }
    }

    fun locationPermissionGranted(): Boolean = Permissions.granted(app, Permissions.location())

    private fun onLocation(loc: Location) {
        val prev = lastSentLocation
        if (prev != null && loc.time - prev.time < 120_000 && loc.distanceTo(prev) < 75) return
        lastSentLocation = loc
        synchronized(track) {
            track.addLast(Triple(loc.time, loc.latitude, loc.longitude))
            while (track.size > 600) track.removeFirst()
        }
        if (_paused.value) return
        try {
            spool.writeEvent("location", null, loc.time, mapOf(
                "lat" to loc.latitude, "lon" to loc.longitude,
                "acc" to (if (loc.hasAccuracy()) loc.accuracy.toDouble() else null),
            ))
            uploader.kick()
        } catch (e: Exception) {
            Log.e(TAG, "cannot store location", e)
        }
    }

    /** Ближайшая точка маршрута к моменту съёмки (±30 минут). */
    fun locationNear(ts: Long): Pair<Double, Double>? = synchronized(track) {
        track.minByOrNull { kotlin.math.abs(it.first - ts) }
            ?.takeIf { kotlin.math.abs(it.first - ts) < 30 * 60_000 }
            ?.let { it.second to it.third }
    }

    /** Автоматически отправить новые снимки камеры за сегодня (если включено). */
    fun syncPhotosNow() {
        if (!prefs.photoAutoImport || !Permissions.granted(app, Permissions.photos())) return
        if (!prefs.serverConfigured) return
        lastPhotoSync = System.currentTimeMillis()
        scope.launch {
            val items = PhotoSync.todaysCameraPhotos(app, prefs.photoSyncedUntil)
            for ((uri, takenAt) in items) {
                val sent = PhotoSync.upload(app, listOf(uri), ::locationNear)
                if (sent == 0) break // нет сети — попробуем позже
                prefs.photoSyncedUntil = takenAt
            }
        }
    }

    // ------------------------------------------------------------------ поиск и выбор кулона
    fun startScan(): Boolean {
        _found.value = emptyList()
        val ok = scanner.start { d ->
            _found.update { list ->
                if (list.any { it.address == d.address }) list.map { if (it.address == d.address) d else it }
                else list + d
            }
        }
        _scanning.value = ok
        if (ok) scope.launch {
            delay(15_000)
            stopScan()
        }
        return ok
    }

    fun stopScan() {
        scanner.stop()
        _scanning.value = false
    }

    fun selectDevice(context: Context, d: FoundDevice) {
        stopScan()
        val changed = prefs.deviceAddress != d.address
        prefs.deviceAddress = d.address
        prefs.deviceName = d.name
        _device.value = DeviceStatus(address = d.address, name = d.name, heardTodayMs = _device.value.heardTodayMs)
        if (changed) uploader.reconnectNow()
        if (_serviceRunning.value) ble.connect(d.address) else startCapture(context)
    }

    fun forgetDevice(context: Context) {
        stopCapture(context)
        prefs.deviceAddress = null
        prefs.deviceName = null
        prefs.outages = emptyList()
        _outages.value = emptyList()
        _device.value = DeviceStatus()
    }

    // ------------------------------------------------------------------ управление кулоном
    /** «Найти»: три вибрации подряд. */
    fun findDevice() {
        scope.launch {
            repeat(3) { i ->
                ble.haptic(3)
                if (i < 2) delay(750)
            }
        }
    }

    fun setLedDim(percent: Int) {
        _device.update { it.copy(ledDim = percent) }
        ble.setLedDim(percent)
    }

    fun setMicGain(level: Int) {
        _device.update { it.copy(micGain = level) }
        ble.setMicGain(level)
    }

    // ------------------------------------------------------------------ звук
    private val frameBytes = AtomicLong()
    private val frameCount = AtomicLong()

    private fun onFrame(frame: ByteArray) {
        frameBytes.addAndGet(frame.size.toLong())
        frameCount.incrementAndGet()
        val now = System.currentTimeMillis()
        // пока Mnemo отвечает вслух, кулон слышит динамик телефона — это не разговор, в запись не идёт
        val ownVoice = voice.speakingNow()
        synchronized(frameLock) {
            if (ownVoice && frames.isNotEmpty()) {
                closedBatches.add(ArrayList(frames) to batchStartedAt)
                frames.clear()
            }
            if (!_paused.value && !ownVoice) {
                if (frames.isEmpty()) batchStartedAt = now - 20
                frames.add(frame)
            }
            recent.addLast(now to frame)
            while (recent.size > 150) recent.removeFirst()
        }
        voice.onFrame(frame)
    }

    private fun framesSince(ts: Long): List<ByteArray> = synchronized(frameLock) {
        recent.filter { it.first >= ts }.map { it.second }
    }

    private suspend fun batchLoop() {
        var tick = 0
        var lastBle = 0L
        var lastNet = 0L
        var bleRate = 0.0
        var netRate = 0.0
        while (true) {
            delay(1_000)
            assembler.flushStale()
            val batches = synchronized(frameLock) {
                val out = ArrayList(closedBatches)
                closedBatches.clear()
                if (frames.isNotEmpty()) {
                    out.add(ArrayList(frames) to batchStartedAt)
                    frames.clear()
                }
                out
            }
            val today = LocalDate.now().toString()
            for (pair in batches) {
                try {
                    spool.writeAudio(codec, pair.second, pair.first)
                    uploader.kick()
                } catch (e: Exception) {
                    Log.e(TAG, "cannot store audio", e)
                }
                prefs.addHeard(today, pair.first.size * 20L)
            }
            // скорость: сглаживаем, чтобы цифра внизу экрана устройства не прыгала
            val bleNow = bleBytes.get()
            val net = _upload.value.sentBytes
            bleRate = bleRate * 0.7 + (bleNow - lastBle) * 8 / 1000.0 * 0.3
            netRate = netRate * 0.7 + (if (lastNet == 0L) 0L else net - lastNet).coerceAtLeast(0) * 8 / 1000.0 * 0.3
            lastBle = bleNow
            lastNet = net
            _device.update {
                it.copy(
                    frames = assembler.frames,
                    lostPackets = assembler.lostPackets,
                    lastAudioAt = if (System.currentTimeMillis() - lastPacketAt < 2_000) System.currentTimeMillis()
                        else it.lastAudioAt,
                    bleKbps = bleRate, netKbps = netRate,
                    avgFrameBytes = frameCount.getAndSet(0).let { n -> if (n > 0) (frameBytes.getAndSet(0) / n).toInt() else it.avgFrameBytes },
                    heardTodayMs = prefs.heardToday(today),
                )
            }
            if (++tick % 5 == 0) updateNotification()
            if (_serviceRunning.value && System.currentTimeMillis() - lastPhotoSync > 30 * 60_000) syncPhotosNow()
        }
    }

    @Volatile private var lastPacketAt = 0L

    private fun onConnectionChanged(state: ConnState) {
        val now = System.currentTimeMillis()
        val prev = _device.value
        val since = when {
            state == ConnState.CONNECTED -> {
                if (prev.disconnectedSince > 0 && now - prev.disconnectedSince > 3_000) {
                    val list = (prefs.outages + Outage(prev.disconnectedSince, now)).takeLast(20)
                    prefs.outages = list
                    _outages.value = list
                }
                0L
            }
            !_serviceRunning.value -> 0L
            prev.disconnectedSince > 0 -> prev.disconnectedSince
            prev.state == ConnState.CONNECTED -> now
            else -> prev.disconnectedSince
        }
        _device.update { it.copy(state = state, disconnectedSince = since) }
    }

    private val bleListener = object : OmiBle.Listener {
        override fun onConnectionState(state: ConnState) {
            if (state != ConnState.CONNECTED) assembler.reset()
            onConnectionChanged(state)
            updateNotification()
        }

        override fun onCodec(codec: Int) {
            this@Core.codec = codec
            _device.update { it.copy(codec = codec) }
        }

        override fun onAudioPacket(packet: ByteArray) {
            bleBytes.addAndGet(packet.size.toLong())
            lastPacketAt = System.currentTimeMillis()
            assembler.onPacket(packet)
        }

        override fun onButton(code: Int) {
            val now = System.currentTimeMillis()
            if (code != OmiProtocol.BUTTON_PRESS && code != OmiProtocol.BUTTON_RELEASE) {
                _device.update { it.copy(lastButton = code, lastButtonAt = now) }
            }
            if (!prefs.buttonActions) return
            when (code) {
                OmiProtocol.BUTTON_PRESS -> pressedAt = now
                OmiProtocol.BUTTON_SINGLE_TAP -> {
                    // вопрос голосом: берём звук с момента нажатия (кулон сообщает о нажатии с задержкой)
                    val from = if (pressedAt > 0 && now - pressedAt < 3_000) pressedAt else now - 700
                    vibrate(1)
                    voice.onTap(framesSince(from), from)
                }
                OmiProtocol.BUTTON_DOUBLE_TAP -> when (prefs.doubleTap) {
                    "pause" -> {
                        val p = !_paused.value
                        setPaused(p)
                        vibrate(2)
                        voice.confirm(res().getString(if (p) R.string.voice_paused else R.string.voice_resumed))
                    }
                    "off" -> Unit
                    else -> {
                        try {
                            spool.writeEvent("button", code, now)
                            uploader.kick()
                        } catch (e: Exception) {
                            Log.e(TAG, "cannot store button event", e)
                        }
                        vibrate(2)
                        if (prefs.confirmRemember) voice.confirm(res().getString(R.string.voice_remembered))
                    }
                }
            }
        }

        override fun onBattery(level: Int) {
            _device.update { it.copy(battery = level) }
            updateNotification()
        }

        override fun onFirmware(version: String) {
            _device.update { it.copy(firmware = version) }
        }

        override fun onMtu(mtu: Int) {
            _device.update { it.copy(mtu = mtu) }
        }

        override fun onDeviceInfo(model: String?, hardware: String?, manufacturer: String?) {
            _device.update { it.copy(model = model, hardware = hardware, manufacturer = manufacturer) }
        }

        override fun onLedDim(percent: Int) {
            _device.update { it.copy(ledDim = percent) }
        }

        override fun onMicGain(level: Int) {
            _device.update { it.copy(micGain = level) }
        }

        override fun onCharging(charging: Boolean) {
            _device.update { it.copy(charging = charging) }
            updateNotification()
        }

        override fun onCapabilities(caps: Set<String>) {
            _device.update { it.copy(caps = caps) }
        }
    }

    private fun helloInfo(): JSONObject {
        val d = _device.value
        return JSONObject()
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("android", Build.VERSION.RELEASE)
            .put("phone", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("pendant_name", d.name ?: JSONObject.NULL)
            .put("firmware", d.firmware ?: JSONObject.NULL)
            .put("battery", d.battery ?: JSONObject.NULL)
            .put("codec", d.codec ?: JSONObject.NULL)
            .put("language", Lang.code(prefs))
    }

    @Suppress("DEPRECATION")
    private fun vibrate(times: Int) {
        try {
            val v = app.getSystemService(Vibrator::class.java) ?: return
            val pattern = if (times == 2) longArrayOf(0, 60, 80, 60) else longArrayOf(0, 60)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ уведомление
    private fun createNotificationChannel() {
        val r = res()
        val channel = NotificationChannel(NOTIFICATION_CHANNEL, r.getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW)
        channel.description = r.getString(R.string.notif_channel_desc)
        app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun notificationText(): String {
        val r = res()
        val d = _device.value
        val u = _upload.value
        val parts = mutableListOf<String>()
        parts += r.getString(when {
            _paused.value -> R.string.notif_paused
            d.state == ConnState.CONNECTED -> R.string.notif_recording
            d.state == ConnState.BLUETOOTH_OFF -> R.string.state_bluetooth_off
            else -> R.string.notif_searching
        })
        d.battery?.let { parts += r.getString(R.string.notif_battery, it) }
        if (!u.connected && !u.resting) parts += r.getString(R.string.notif_offline)
        else if (u.pendingFiles > 3) parts += r.getString(R.string.notif_catching_up)
        return parts.joinToString(", ")
    }

    fun buildNotification(context: Context): Notification {
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = notificationText()
        lastNotificationText = text
        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_neuro)
            .setContentTitle(res().getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(intent)
            .build()
    }

    private fun updateNotification() {
        if (!::app.isInitialized || !_serviceRunning.value) return
        if (notificationText() == lastNotificationText) return
        try {
            app.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(app))
        } catch (_: SecurityException) {
        }
    }
}
