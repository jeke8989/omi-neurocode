package codes.neuro.neurosoft.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.min

enum class ConnState { DISCONNECTED, BLUETOOTH_OFF, CONNECTING, SETTING_UP, CONNECTED }

data class FoundDevice(val address: String, val name: String, val rssi: Int)

/**
 * Подключение к кулону по BLE: MTU, чтение кодека, подписка на звук/кнопку/батарею, автопереподключение.
 * Все GATT-операции выполняются строго по очереди (Android не умеет параллельно).
 */
@SuppressLint("MissingPermission")
class OmiBle(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onConnectionState(state: ConnState)
        fun onCodec(codec: Int)
        fun onAudioPacket(packet: ByteArray)
        fun onButton(code: Int)
        fun onBattery(level: Int)
        fun onFirmware(version: String)
        fun onMtu(mtu: Int)
    }

    private sealed class Op {
        class Read(val service: UUID, val ch: UUID) : Op()
        class Notify(val service: UUID, val ch: UUID) : Op()
        class Write(val service: UUID, val ch: UUID, val value: ByteArray) : Op()
        class Run(val block: () -> Unit) : Op()
    }

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var wantConnected = false
    private var attempts = 0
    private var servicesRequested = false
    private val ops = ArrayDeque<Op>()
    private var current: Op? = null

    private val opTimeout = Runnable {
        log("GATT operation timed out")
        current = null
        nextOp()
    }
    private val reconnect = Runnable {
        if (wantConnected && gatt == null) open(autoConnect = attempts > 2)
    }
    private val mtuFallback = Runnable { gatt?.let { requestServices(it) } }

    // ------------------------------------------------------------------ публичное API (любой поток)
    fun connect(address: String) = main.post {
        wantConnected = true
        if (this.address == address && gatt != null) return@post
        close()
        this.address = address
        attempts = 0
        open(autoConnect = false)
    }

    fun disconnect() = main.post {
        wantConnected = false
        main.removeCallbacks(reconnect)
        close()
        listener.onConnectionState(ConnState.DISCONNECTED)
    }

    // ------------------------------------------------------------------ внутреннее (главный поток)
    private fun open(autoConnect: Boolean) {
        val adapter = manager?.adapter
        val addr = address ?: return
        if (adapter == null || !adapter.isEnabled) {
            listener.onConnectionState(ConnState.BLUETOOTH_OFF)
            scheduleReconnect()
            return
        }
        val device = try {
            adapter.getRemoteDevice(addr)
        } catch (e: IllegalArgumentException) {
            log("bad address $addr")
            return
        }
        listener.onConnectionState(ConnState.CONNECTING)
        servicesRequested = false
        gatt = try {
            device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            log("no bluetooth permission: ${e.message}")
            null
        }
        if (gatt == null) scheduleReconnect()
    }

    private fun close() {
        clearOps()
        main.removeCallbacks(mtuFallback)
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        attempts++
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, min(30_000L, 2_000L * attempts))
    }

    private fun requestServices(g: BluetoothGatt) {
        if (servicesRequested) return
        servicesRequested = true
        main.removeCallbacks(mtuFallback)
        if (!g.discoverServices()) g.disconnect()
    }

    private fun setup(g: BluetoothGatt) {
        clearOps()
        fun has(service: UUID, ch: UUID) = g.getService(service)?.getCharacteristic(ch) != null

        enqueue(Op.Read(OmiProtocol.AUDIO_SERVICE, OmiProtocol.AUDIO_CODEC))
        if (has(OmiProtocol.DEVICE_INFO_SERVICE, OmiProtocol.FIRMWARE_REVISION)) {
            enqueue(Op.Read(OmiProtocol.DEVICE_INFO_SERVICE, OmiProtocol.FIRMWARE_REVISION))
        }
        if (has(OmiProtocol.TIME_SYNC_SERVICE, OmiProtocol.TIME_SYNC_WRITE)) {
            val epoch = (System.currentTimeMillis() / 1000).toInt()
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(epoch).array()
            enqueue(Op.Write(OmiProtocol.TIME_SYNC_SERVICE, OmiProtocol.TIME_SYNC_WRITE, bytes))
        }
        if (has(OmiProtocol.BATTERY_SERVICE, OmiProtocol.BATTERY_LEVEL)) {
            enqueue(Op.Read(OmiProtocol.BATTERY_SERVICE, OmiProtocol.BATTERY_LEVEL))
            enqueue(Op.Notify(OmiProtocol.BATTERY_SERVICE, OmiProtocol.BATTERY_LEVEL))
        }
        if (has(OmiProtocol.BUTTON_SERVICE, OmiProtocol.BUTTON_TRIGGER)) {
            enqueue(Op.Notify(OmiProtocol.BUTTON_SERVICE, OmiProtocol.BUTTON_TRIGGER))
        }
        enqueue(Op.Notify(OmiProtocol.AUDIO_SERVICE, OmiProtocol.AUDIO_DATA))
        enqueue(Op.Run { listener.onConnectionState(ConnState.CONNECTED) })
    }

    private fun enqueue(op: Op) {
        ops.addLast(op)
        if (current == null) nextOp()
    }

    private fun clearOps() {
        ops.clear()
        current = null
        main.removeCallbacks(opTimeout)
    }

    private fun nextOp() {
        main.removeCallbacks(opTimeout)
        val g = gatt
        if (g == null) {
            clearOps()
            return
        }
        val op = ops.removeFirstOrNull()
        current = op
        if (op == null) return
        val started = when (op) {
            is Op.Run -> {
                op.block()
                false
            }
            is Op.Read -> characteristic(g, op.service, op.ch)?.let { g.readCharacteristic(it) } ?: false
            is Op.Notify -> characteristic(g, op.service, op.ch)?.let { enableNotify(g, it) } ?: false
            is Op.Write -> characteristic(g, op.service, op.ch)?.let { write(g, it, op.value) } ?: false
        }
        if (started) {
            main.postDelayed(opTimeout, 5_000)
        } else {
            current = null
            main.post { nextOp() }
        }
    }

    private fun opDone() = main.post {
        main.removeCallbacks(opTimeout)
        current = null
        nextOp()
    }

    private fun characteristic(g: BluetoothGatt, service: UUID, ch: UUID): BluetoothGattCharacteristic? =
        g.getService(service)?.getCharacteristic(ch)

    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(ch, true)) return false
        val d = ch.getDescriptor(OmiProtocol.CCCD) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            writeDescriptorLegacy(g, d, value)
        }
    }

    private fun write(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            writeCharacteristicLegacy(g, ch, value)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorLegacy(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean {
        d.value = value
        return g.writeDescriptor(d)
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicLegacy(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = value
        return g.writeCharacteristic(ch)
    }

    // ------------------------------------------------------------------ данные с кулона
    private fun handleNotify(uuid: UUID, value: ByteArray) {
        when (uuid) {
            OmiProtocol.AUDIO_DATA -> listener.onAudioPacket(value)
            OmiProtocol.BUTTON_TRIGGER -> if (value.isNotEmpty()) listener.onButton(value[0].toInt() and 0xFF)
            OmiProtocol.BATTERY_LEVEL -> if (value.isNotEmpty()) listener.onBattery(value[0].toInt() and 0xFF)
        }
    }

    private fun handleRead(uuid: UUID, value: ByteArray, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS && value.isNotEmpty()) {
            when (uuid) {
                OmiProtocol.AUDIO_CODEC -> listener.onCodec(value[0].toInt() and 0xFF)
                OmiProtocol.BATTERY_LEVEL -> listener.onBattery(value[0].toInt() and 0xFF)
                OmiProtocol.FIRMWARE_REVISION ->
                    listener.onFirmware(String(value, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' })
            }
        }
        opDone()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) {
                    try { g.close() } catch (_: Exception) {}
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    attempts = 0
                    listener.onConnectionState(ConnState.SETTING_UP)
                    if (!g.requestMtu(517)) {
                        requestServices(g)
                    } else {
                        main.postDelayed(mtuFallback, 3_000)
                    }
                } else {
                    log("disconnected (status=$status, state=$newState)")
                    clearOps()
                    main.removeCallbacks(mtuFallback)
                    try { g.close() } catch (_: Exception) {}
                    gatt = null
                    listener.onConnectionState(ConnState.DISCONNECTED)
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (g != gatt) return@post
                listener.onMtu(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
                requestServices(g)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || g.getService(OmiProtocol.AUDIO_SERVICE) == null) {
                    log("Omi audio service not found (status=$status)")
                    g.disconnect()
                    return@post
                }
                setup(g)
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            handleRead(ch.uuid, value, status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) handleRead(ch.uuid, legacyValue(ch), status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(ch.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) handleNotify(ch.uuid, legacyValue(ch))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("descriptor write ${d.characteristic.uuid} failed: $status")
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            opDone()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyValue(ch: BluetoothGattCharacteristic): ByteArray = ch.value?.copyOf() ?: ByteArray(0)

    private fun log(msg: String) = Log.i("NeuroSoft.BLE", msg)
}

/** Поиск кулонов Omi поблизости. */
@SuppressLint("MissingPermission")
class OmiScanner(context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var onFound: ((FoundDevice) -> Unit)? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val name = record?.deviceName ?: try { result.device.name } catch (_: SecurityException) { null }
            val hasOmiService = record?.serviceUuids?.any { it.uuid == OmiProtocol.AUDIO_SERVICE } == true
            if (hasOmiService || name?.contains("omi", ignoreCase = true) == true) {
                onFound?.invoke(FoundDevice(result.device.address, name ?: "Omi", result.rssi))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w("NeuroSoft.BLE", "scan failed: $errorCode")
        }
    }

    fun start(onFound: (FoundDevice) -> Unit): Boolean {
        val scanner = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        this.onFound = onFound
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            scanner.startScan(null, settings, callback)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun stop() {
        try {
            manager?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {
        }
        onFound = null
    }
}
