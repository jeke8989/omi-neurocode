package codes.neuro.neurosoft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import android.location.Location
import codes.neuro.neurosoft.diary.LocationTracker
import codes.neuro.neurosoft.diary.PhotoSync
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.AudioSpool
import codes.neuro.neurosoft.net.UploadStatus
import codes.neuro.neurosoft.net.Uploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import codes.neuro.neurosoft.voice.VoiceAssistant
import org.json.JSONObject
import java.io.File

data class DeviceStatus(
    val address: String? = null,
    val name: String? = null,
    val state: ConnState = ConnState.DISCONNECTED,
    val battery: Int? = null,
    val codec: Int? = null,
    val firmware: String? = null,
    val mtu: Int = 23,
    val frames: Long = 0,
    val lostPackets: Long = 0,
    val lastAudioAt: Long = 0,
    val lastButton: String? = null,
    val lastButtonAt: Long = 0,
)

/**
 * Ядро приложения: кулон (BLE) -> сборка кадров -> пакеты по 1 секунде на диск -> выгрузка на сервер.
 * Живёт в процессе приложения; фоновую работу удерживает OmiService.
 */
object Core {
    private const val TAG = "NeuroSoft"
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameLock = Any()
    private val frames = ArrayList<ByteArray>()
    private var batchStartedAt = 0L

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

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = Prefs(app)
        spool = AudioSpool(File(app.filesDir, "spool"))
        uploader = Uploader(spool, prefs, ::deviceId, ::helloInfo) { s ->
            _upload.value = s
            updateNotification()
        }
        ble = OmiBle(app, bleListener)
        scanner = OmiScanner(app)
        voice = VoiceAssistant(app, prefs, ::deviceId, { codec }) { ts ->
            // нет связи: вопрос записан вместе со всем звуком, агент ответит в чате, когда сеть появится
            try {
                spool.writeEvent("button", OmiProtocol.BUTTON_SINGLE_TAP, ts)
                uploader.kick()
            } catch (e: Exception) {
                Log.e(TAG, "cannot queue offline question", e)
            }
        }
        voice.init()
        location = LocationTracker(app) { loc -> onLocation(loc) }
        _device.value = DeviceStatus(address = prefs.deviceAddress, name = prefs.deviceName)
        createNotificationChannel()
        uploader.start()
        scope.launch { batchLoop() }
    }

    /** Идентификатор кулона на сервере — его Bluetooth-адрес. */
    fun deviceId(): String? = prefs.deviceAddress?.replace(":", "-")

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
        if (prefs.serverUrl.isBlank()) return
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
        _device.value = DeviceStatus(address = d.address, name = d.name)
        if (changed) uploader.reconnectNow()
        if (_serviceRunning.value) ble.connect(d.address) else startCapture(context)
    }

    fun forgetDevice(context: Context) {
        stopCapture(context)
        prefs.deviceAddress = null
        prefs.deviceName = null
        _device.value = DeviceStatus()
    }

    // ------------------------------------------------------------------ звук
    private fun onFrame(frame: ByteArray) {
        val now = System.currentTimeMillis()
        synchronized(frameLock) {
            if (frames.isEmpty()) batchStartedAt = now - 20
            frames.add(frame)
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
        while (true) {
            delay(1_000)
            assembler.flushStale()
            val pair = synchronized(frameLock) {
                if (frames.isEmpty()) null
                else {
                    val copy = ArrayList(frames)
                    frames.clear()
                    copy to batchStartedAt
                }
            }
            if (pair != null) {
                try {
                    spool.writeAudio(codec, pair.second, pair.first)
                    uploader.kick()
                } catch (e: Exception) {
                    Log.e(TAG, "cannot store audio", e)
                }
            }
            _device.update {
                it.copy(
                    frames = assembler.frames,
                    lostPackets = assembler.lostPackets,
                    lastAudioAt = if (pair != null) System.currentTimeMillis() else it.lastAudioAt,
                )
            }
            if (++tick % 5 == 0) updateNotification()
            if (_serviceRunning.value && System.currentTimeMillis() - lastPhotoSync > 30 * 60_000) syncPhotosNow()
        }
    }

    private val bleListener = object : OmiBle.Listener {
        override fun onConnectionState(state: ConnState) {
            if (state != ConnState.CONNECTED) assembler.reset()
            _device.update { it.copy(state = state) }
            updateNotification()
        }

        override fun onCodec(codec: Int) {
            this@Core.codec = codec
            _device.update { it.copy(codec = codec) }
        }

        override fun onAudioPacket(packet: ByteArray) = assembler.onPacket(packet)

        override fun onButton(code: Int) {
            _device.update {
                it.copy(lastButton = OmiProtocol.buttonName(code), lastButtonAt = System.currentTimeMillis())
            }
            val now = System.currentTimeMillis()
            when (code) {
                OmiProtocol.BUTTON_PRESS -> pressedAt = now
                OmiProtocol.BUTTON_SINGLE_TAP -> {
                    // вопрос голосом: берём звук с момента нажатия (кулон сообщает о нажатии с задержкой)
                    val from = if (pressedAt > 0 && now - pressedAt < 3_000) pressedAt else now - 700
                    vibrate(1)
                    voice.onTap(framesSince(from), from)
                }
                OmiProtocol.BUTTON_DOUBLE_TAP -> {
                    try {
                        spool.writeEvent("button", code, now)
                        uploader.kick()
                    } catch (e: Exception) {
                        Log.e(TAG, "cannot store button event", e)
                    }
                    vibrate(2)
                    if (prefs.confirmRemember) voice.confirm("Запомнил")
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
        val channel = NotificationChannel(NOTIFICATION_CHANNEL, "Запись с кулона", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Показывает, что NeuroSoft принимает звук с кулона"
        app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun stateText(state: ConnState): String = when (state) {
        ConnState.CONNECTED -> "Кулон подключён"
        ConnState.SETTING_UP -> "Настраиваю кулон…"
        ConnState.CONNECTING -> "Ищу кулон…"
        ConnState.BLUETOOTH_OFF -> "Bluetooth выключен"
        ConnState.DISCONNECTED -> "Кулон не подключён"
    }

    private fun notificationText(): String {
        val d = _device.value
        val u = _upload.value
        return buildString {
            append(stateText(d.state))
            d.battery?.let { append(" · батарея $it%") }
            append(if (u.connected) " · сервер ✓" else " · сервер ✗")
            if (u.pendingFiles > 3) append(" · в очереди ${u.pendingFiles}")
        }
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
            .setContentTitle("NeuroSoft")
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
