package codes.neuro.neurosoft.ble

import android.os.SystemClock
import java.io.ByteArrayOutputStream

/**
 * Собирает кадры Opus из BLE-уведомлений кулона.
 *
 * Формат уведомления (прошивка, push_to_gatt): [u16 номер пакета LE][u8 номер части кадра][данные].
 * Кадр, не влезший в один пакет, приходит несколькими частями 0, 1, 2…
 * Кадр считается готовым, когда пришла часть 0 следующего кадра или поток замолчал (flushStale).
 * Потеря пакета (разрыв в нумерации) отбрасывает недособранный кадр.
 */
class FrameAssembler(private val onFrame: (ByteArray) -> Unit) {
    private var lastIndex = -1
    private var lastPart = -1
    private var pending: ByteArrayOutputStream? = null
    private var pendingSince = 0L

    @Volatile
    var lostPackets = 0L
        private set

    @Volatile
    var frames = 0L
        private set

    @Synchronized
    fun reset() {
        lastIndex = -1
        lastPart = -1
        pending = null
    }

    @Synchronized
    fun onPacket(packet: ByteArray) {
        if (packet.size < 3) return
        val index = (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)
        val part = packet[2].toInt() and 0xFF
        val content = packet.copyOfRange(3, packet.size)

        if (lastIndex < 0) {
            if (part == 0) startFrame(index, content)
            return
        }
        val expected = (lastIndex + 1) and 0xFFFF
        if (index != expected || (part != 0 && part != lastPart + 1)) {
            lostPackets++
            pending = null
            lastIndex = -1
            if (part == 0) startFrame(index, content)
            return
        }
        if (part == 0) {
            emitPending()
            startFrame(index, content)
        } else {
            pending?.write(content, 0, content.size)
            lastIndex = index
            lastPart = part
            pendingSince = SystemClock.elapsedRealtime()
        }
    }

    /** Выпустить последний кадр, если новых пакетов давно не было (кадры идут каждые 20 мс). */
    @Synchronized
    fun flushStale(maxAgeMs: Long = 80) {
        if (pending != null && SystemClock.elapsedRealtime() - pendingSince > maxAgeMs) emitPending()
    }

    private fun startFrame(index: Int, content: ByteArray) {
        pending = ByteArrayOutputStream(256).also { it.write(content, 0, content.size) }
        lastIndex = index
        lastPart = 0
        pendingSince = SystemClock.elapsedRealtime()
    }

    private fun emitPending() {
        val p = pending ?: return
        pending = null
        if (p.size() > 0) {
            frames++
            onFrame(p.toByteArray())
        }
    }
}
