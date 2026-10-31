package codes.neuro.neurosoft.net

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Очередь на диске: каждую секунду звука и каждое нажатие кнопки сохраняем в файл,
 * а удаляем только после подтверждения сервера. Если сети нет — копится здесь и догружается потом.
 *
 * Файл звука (<id>_a.bin) — это ровно то бинарное сообщение, что уходит на сервер:
 *   u8 версия=1, u8 кодек, u64 id (= время захвата, мс), далее кадры [u16 длина LE][байты].
 */
class AudioSpool(private val dir: File) {
    companion object {
        const val MAX_BYTES = 500L * 1024 * 1024 // ~35 часов звука Opus 32 кбит/с

        fun ackId(file: File): String = file.name.substringBefore('.')
        private fun order(file: File): Long = file.name.substringBefore('_').toLongOrNull() ?: 0L

        /** Время записи файла очереди (мс), по имени. */
        fun timeOf(file: File): Long = order(file)
    }

    private var lastId = 0L

    @Volatile
    var droppedFiles = 0
        private set

    init {
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        lastId = pending().lastOrNull()?.let { order(it) } ?: 0L
    }

    @Synchronized
    private fun nextId(ts: Long): Long {
        val id = if (ts <= lastId) lastId + 1 else ts
        lastId = id
        return id
    }

    fun writeAudio(codec: Int, ts: Long, frames: List<ByteArray>): String {
        val id = nextId(ts)
        val size = 10 + frames.sumOf { 2 + it.size }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(1.toByte())
        buf.put(codec.toByte())
        buf.putLong(id)
        for (f in frames) {
            buf.putShort(f.size.toShort())
            buf.put(f)
        }
        val name = "${id}_a.bin"
        atomicWrite(name, buf.array())
        enforceLimit()
        return name
    }

    private var lastOfflineId = 0L

    /**
     * Звук, который кулон записал без телефона (см. OfflineSync): тот же формат, но версия 2 — сервер кладёт
     * его на своё время отдельно от живого потока. Файл <время>_o.bin.
     */
    @Synchronized
    fun writeOffline(codec: Int, ts: Long, frames: List<ByteArray>): String {
        val id = if (ts <= lastOfflineId) lastOfflineId + 1 else ts
        lastOfflineId = id
        val size = 10 + frames.sumOf { 2 + it.size }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(2.toByte())
        buf.put(codec.toByte())
        buf.putLong(id)
        for (f in frames) {
            buf.putShort(f.size.toShort())
            buf.put(f)
        }
        val name = "${id}_o.bin"
        atomicWrite(name, buf.array())
        enforceLimit()
        return name
    }

    fun writeEvent(event: String, value: Int?, ts: Long, extra: Map<String, Any?> = emptyMap()): String {
        val id = nextId(ts)
        val json = JSONObject()
            .put("type", "event")
            .put("id", "${id}_e")
            .put("event", event)
            .put("ts", ts)
        if (value != null) json.put("value", value)
        for ((k, v) in extra) json.put(k, v ?: JSONObject.NULL)
        val name = "${id}_e.json"
        atomicWrite(name, json.toString().toByteArray(Charsets.UTF_8))
        return name
    }

    fun pending(): List<File> =
        (dir.listFiles { f -> f.name.endsWith(".bin") || f.name.endsWith(".json") } ?: emptyArray())
            .sortedBy { order(it) }

    /**
     * Порядок выгрузки: сначала живое (звук, кнопка, геолокация — по времени), потом запись из памяти кулона.
     * Иначе большая старая запись задерживала бы то, что вы говорите сейчас.
     */
    fun uploadOrder(): List<File> = pending().sortedBy { if (it.name.contains("_o.")) 1 else 0 }

    fun stats(): Pair<Int, Long> {
        val files = dir.listFiles { f -> !f.name.endsWith(".tmp") } ?: return 0 to 0L
        return files.size to files.sumOf { it.length() }
    }

    private fun atomicWrite(name: String, bytes: ByteArray) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(File(dir, name))) {
            tmp.delete()
            throw IllegalStateException("cannot store $name")
        }
    }

    /** Если очередь разрослась (долго нет сети), удаляем самый старый звук, чтобы не забить телефон. */
    private fun enforceLimit() {
        var (_, total) = stats()
        if (total <= MAX_BYTES) return
        for (f in pending()) {
            if (total <= MAX_BYTES * 9 / 10) break
            if (!f.name.endsWith(".bin")) continue
            total -= f.length()
            if (f.delete()) droppedFiles++
        }
    }
}
