package codes.neuro.neurosoft

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Уведомления внутри приложения (колокольчик на главной): всё, что Mnemo показывал в шторке, — «Это задача?»,
 * напоминания, утренняя сводка. В шторке уведомление можно смахнуть, а здесь оно останется: прочитать,
 * ответить на вопрос о задаче, открыть день. Хранится только на телефоне, последние 80.
 */
object Inbox {
    data class Item(
        val key: String,
        /** task | reminder | morning */
        val kind: String,
        val title: String,
        val text: String,
        val at: Long,
        /** Куда ведёт нажатие: tasks | day:YYYY-MM-DD | chat */
        val open: String?,
        val taskId: Long? = null,
    )

    private const val MAX = 80
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items
    private val _seenAt = MutableStateFlow(0L)
    /** Когда колокольчик открывали в последний раз: всё новее — непрочитанное. */
    val seenAt: StateFlow<Long> = _seenAt
    private var loaded = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val prefs = Core.prefs
        _seenAt.value = prefs.inboxSeenAt
        _items.value = try {
            val a = JSONArray(prefs.inbox)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Item(o.getString("key"), o.optString("kind"), o.optString("title"), o.optString("text"), o.optLong("at"),
                    o.optString("open").ifBlank { null }, if (o.has("task")) o.optLong("task") else null)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Item>) {
        _items.value = list
        val a = JSONArray()
        list.forEach { i ->
            val o = JSONObject().put("key", i.key).put("kind", i.kind).put("title", i.title).put("text", i.text)
                .put("at", i.at).put("open", i.open ?: "")
            i.taskId?.let { o.put("task", it) }
            a.put(o)
        }
        Core.prefs.inbox = a.toString()
    }

    fun ensureLoaded() = load()

    /** Записать уведомление (повтор с тем же key заменяет прежнее и поднимается наверх). */
    @Synchronized
    fun add(kind: String, key: String, title: String, text: String, open: String?, taskId: Long? = null) {
        load()
        val item = Item(key, kind, title, text, System.currentTimeMillis(), open, taskId)
        save((listOf(item) + _items.value.filter { it.key != key }).take(MAX))
    }

    @Synchronized
    fun remove(key: String) {
        load()
        save(_items.value.filter { it.key != key })
    }

    @Synchronized
    fun clear() {
        load()
        save(emptyList())
    }

    fun markSeen() {
        load()
        val now = System.currentTimeMillis()
        _seenAt.value = now
        Core.prefs.inboxSeenAt = now
    }

    fun unread(items: List<Item>, seenAt: Long): Int = items.count { it.at > seenAt }
}
