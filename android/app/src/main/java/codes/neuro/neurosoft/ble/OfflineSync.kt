package codes.neuro.neurosoft.ble

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Записи, которые кулон сделал без телефона (телефон далеко, выключен Bluetooth, приложение остановлено).
 *
 * Прошивка Omi 3.0.20+ пишет звук в кольцевой буфер, пока нет связи с телефоном. После подключения
 * забираем его по сервису хранения (30295780-…, команды и данные — по одной характеристике 30295781):
 *   INFO 0x10 → [02][read_seq u64][write_seq u64][capacity u32][dropped u64][pkt u16]  (всё big-endian)
 *   READ 0x11 [start u64][count u32] → [05 начало][03 данные…][04 status next_seq]
 *   ADVANCE 0x12 [seq u64] → [01 status] — кулон может освободить прочитанное
 *   STOP 0x03
 * Запись буфера — 444 байта: [время u32, UTC секунды][блок 440 байт: кадры Opus [длина u8][кадр]].
 * Время у каждой записи своё: звук ложится на сервер на своё место в дне, а не «сейчас».
 * Прочитанное сразу пишется на диск телефона (очередь выгрузки) — кулон сам сдвигает отметку прочитанного.
 * Прошивки старше 3.0.20 (другой протокол) пропускаем: в окне устройства видно, что нужно обновить кулон.
 */
class OfflineSync(
    private val send: (ByteArray) -> Boolean,
    private val sink: Sink,
) {
    interface Sink {
        /** Кадры Opus, записанные начиная с startMs (UTC, мс). */
        fun onOfflineAudio(startMs: Long, frames: List<ByteArray>)
        fun onOfflineState(state: State)
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        /** Сколько записей (≈0,1 с звука каждая) ещё лежит в кулоне. */
        val pending: Long = 0,
        val done: Long = 0,
        val lastSyncAt: Long = 0,
        /** Сколько секунд звука забрали за всё время. */
        val recoveredSec: Long = 0,
        val error: String? = null,
    )

    enum class Phase { IDLE, CHECKING, SYNCING, UNSUPPORTED }

    private val main = Handler(Looper.getMainLooper())
    private var state = State()
    private var active = false
    private var readStart = 0L
    private var readCount = 0L
    private var writeSeq = 0L
    private var records = 0L
    private val buf = ByteArray(RECORD * 4)
    private var bufLen = 0
    private var notReady = 0
    // кадры текущего непрерывного куска: пишем на диск по секунде
    private val frames = ArrayList<ByteArray>()
    private var batchStart = 0L
    private var clock = 0L
    private var recovered = 0L

    private val timeout = Runnable { synchronized(this) { if (active) fail("timeout") } }

    fun state() = state

    @Synchronized
    fun restore(lastSyncAt: Long, recoveredSec: Long) {
        recovered = recoveredSec * 1000
        state = state.copy(lastSyncAt = lastSyncAt, recoveredSec = recoveredSec)
    }

    /** Кулон подключён. firmware — строка версии (2A26). */
    @Synchronized
    fun start(firmware: String?, hasStorage: Boolean) {
        if (!hasStorage) return publish(state.copy(phase = Phase.IDLE))
        if (!ringFirmware(firmware)) {
            Log.i(TAG, "firmware $firmware: offline storage protocol not supported")
            return publish(state.copy(phase = Phase.UNSUPPORTED))
        }
        if (active) return
        active = true
        notReady = 0
        info()
    }

    @Synchronized
    fun stop() {
        main.removeCallbacks(timeout)
        if (active) flush()
        active = false
        bufLen = 0
        if (state.phase == Phase.CHECKING || state.phase == Phase.SYNCING) publish(state.copy(phase = Phase.IDLE))
    }

    private fun info() {
        // между порциями остаёмся в «забираю», чтобы полоска на главной не мигала
        publish(state.copy(phase = if (state.phase == Phase.SYNCING) Phase.SYNCING else Phase.CHECKING, error = null))
        if (!send(byteArrayOf(OP_INFO))) return fail("write")
        arm(8_000)
    }

    private fun arm(ms: Long) {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, ms)
    }

    /** Уведомление с характеристики хранения. */
    @Synchronized
    fun onData(v: ByteArray) {
        if (!active || v.isEmpty()) return
        val b = ByteBuffer.wrap(v).order(ByteOrder.BIG_ENDIAN)
        when (v[0]) {
            NOTIFY_INFO -> if (v.size >= 31) {
                val readSeq = b.getLong(1)
                writeSeq = b.getLong(9)
                val unread = (writeSeq - readSeq).coerceAtLeast(0)
                if (unread == 0L) {
                    done()
                } else {
                    Log.i(TAG, "pendant has $unread offline records")
                    read(readSeq, minOf(unread, BATCH_RECORDS))
                }
            }
            NOTIFY_ACK -> {
                val status = if (v.size > 1) v[1].toInt() and 0xFF else 0
                when {
                    status == 9 && notReady++ < 5 -> {  // карта памяти ещё монтируется
                        main.removeCallbacks(timeout)
                        main.postDelayed({ synchronized(this) { if (active) info() } }, 3_000)
                    }
                    status == 10 -> info()  // отметка уже сдвинулась — перечитаем, где мы
                    status != 0 -> fail("ack $status")
                    // ACK 0 — ответ на ADVANCE: дальше смотрим, что осталось
                    state.phase == Phase.SYNCING -> info()
                }
            }
            NOTIFY_READ_BEGIN -> if (v.size >= 13) {
                readStart = b.getLong(1)
                readCount = b.getInt(9).toLong() and 0xFFFFFFFFL
                records = 0
                bufLen = 0
                arm(15_000)
            }
            NOTIFY_DATA -> {
                arm(15_000)
                var off = 1
                while (off < v.size) {
                    val n = minOf(v.size - off, RECORD - bufLen)
                    System.arraycopy(v, off, buf, bufLen, n)
                    bufLen += n
                    off += n
                    if (bufLen == RECORD) {
                        record()
                        bufLen = 0
                    }
                }
            }
            NOTIFY_DONE -> if (v.size >= 10) {
                main.removeCallbacks(timeout)
                val status = v[1].toInt() and 0xFF
                val next = b.getLong(2)
                flush()
                if (status != 0 || bufLen != 0 || next != readStart + records) {
                    return fail("done $status next=$next start=$readStart records=$records rest=$bufLen")
                }
                publish(state.copy(done = state.done + records, pending = (writeSeq - next).coerceAtLeast(0)))
                val cmd = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN).put(OP_ADVANCE).putLong(next).array()
                if (!send(cmd)) return fail("write")
                arm(8_000)
            }
        }
    }

    private fun read(start: Long, count: Long) {
        publish(state.copy(phase = Phase.SYNCING, pending = (writeSeq - start).coerceAtLeast(0)))
        val cmd = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).put(OP_READ).putLong(start)
            .putInt(count.toInt()).array()
        if (!send(cmd)) return fail("write")
        arm(10_000)
    }

    /** Одна запись: время + блок кадров. */
    private fun record() {
        records++
        val ts = ((buf[0].toLong() and 0xFF) shl 24) or ((buf[1].toLong() and 0xFF) shl 16) or
            ((buf[2].toLong() and 0xFF) shl 8) or (buf[3].toLong() and 0xFF)
        val block = ArrayList<ByteArray>()
        var pos = 0
        while (pos < BLOCK - 1) {
            val len = buf[HEADER + pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                continue
            }
            if (pos + 1 + len >= BLOCK) break  // хвост блока: кадр продолжается в следующем
            block.add(buf.copyOfRange(HEADER + pos + 1, HEADER + pos + 1 + len))
            pos += 1 + len
        }
        if (block.isEmpty()) return
        // время записи — примерно конец блока, с точностью до секунды; соседние записи склеиваем без швов
        val blockMs = block.size * FRAME_MS
        val valid = ts in MIN_EPOCH..(System.currentTimeMillis() / 1000 + 86_400)
        val startMs = when {
            valid -> ts * 1000 - blockMs
            clock > 0 -> clock
            else -> System.currentTimeMillis()
        }
        val anchor = if (clock > 0 && kotlin.math.abs(startMs - clock) <= JUMP_MS) clock else startMs
        if (frames.isEmpty()) {
            batchStart = anchor
        } else if (anchor != clock) {
            flush()  // разрыв во времени: кулон выключался или долго молчал — новый кусок
            batchStart = anchor
        }
        frames.addAll(block)
        clock = anchor + blockMs
        if (frames.size >= FRAMES_PER_FILE) flush()
    }

    private fun flush() {
        if (frames.isEmpty()) return
        val out = ArrayList(frames)
        frames.clear()
        recovered += out.size * FRAME_MS
        try {
            sink.onOfflineAudio(batchStart, out)
        } catch (e: Exception) {
            Log.e(TAG, "cannot store offline audio", e)
        }
        batchStart = clock
    }

    private fun done() {
        main.removeCallbacks(timeout)
        flush()
        active = false
        clock = 0
        publish(state.copy(phase = Phase.IDLE, pending = 0, lastSyncAt = System.currentTimeMillis(),
            recoveredSec = recovered / 1000, error = null))
    }

    private fun fail(why: String) {
        Log.w(TAG, "offline sync failed: $why")
        main.removeCallbacks(timeout)
        flush()
        if (active) send(byteArrayOf(OP_STOP))
        active = false
        bufLen = 0
        clock = 0
        publish(state.copy(phase = Phase.IDLE, error = why, recoveredSec = recovered / 1000))
    }

    private fun publish(s: State) {
        state = s
        sink.onOfflineState(s)
    }

    companion object {
        private const val TAG = "Mnemo.Offline"
        const val OP_STOP: Byte = 0x03
        const val OP_INFO: Byte = 0x10
        const val OP_READ: Byte = 0x11
        const val OP_ADVANCE: Byte = 0x12
        const val NOTIFY_ACK: Byte = 0x01
        const val NOTIFY_INFO: Byte = 0x02
        const val NOTIFY_DATA: Byte = 0x03
        const val NOTIFY_DONE: Byte = 0x04
        const val NOTIFY_READ_BEGIN: Byte = 0x05
        const val HEADER = 4
        const val BLOCK = 440
        const val RECORD = HEADER + BLOCK
        const val FRAME_MS = 20L
        const val FRAMES_PER_FILE = 250          // 5 с звука на файл очереди
        const val BATCH_RECORDS = 3_000L         // ≈5 минут звука за одно чтение
        const val JUMP_MS = 3_000L               // разрыв во времени больше — новый кусок
        const val MIN_EPOCH = 1_704_067_200L     // 2024-01-01: раньше — часы кулона не были выставлены

        /** Кольцевой буфер — прошивка 3.0.20 и новее. */
        fun ringFirmware(v: String?): Boolean {
            val nums = Regex("""(\d+)\.(\d+)\.(\d+)""").find(v ?: return false)?.groupValues?.drop(1)
                ?.map { it.toInt() } ?: return false
            val (a, b, c) = nums
            return a > 3 || a == 3 && (b > 0 || c >= 20)
        }
    }
}
