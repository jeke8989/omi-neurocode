package codes.neuro.neurosoft.net

import codes.neuro.neurosoft.Core
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)

data class ConversationItem(
    val id: Long,
    val startedAt: String,
    val endedAt: String,
    val status: String,
    val title: String?,
    val summary: String?,
    val tasksCount: Int,
    val preview: String?,
)

data class Segment(val startedAt: String, val speaker: Int?, val text: String)

data class TaskItem(
    val id: Long,
    val text: String,
    val due: String?,
    val assignee: String?,
    val status: String,
    val source: String,
    val conversationId: Long?,
    val conversationTitle: String?,
    val exportRef: String?,
    val exports: List<TaskExport> = emptyList(),
    val createdAt: String = "",
)

data class MemoryItem(
    val id: Long,
    val text: String,
    val source: String,
    val createdAt: String,
    val conversationTitle: String?,
)

data class ConversationDetail(
    val item: ConversationItem,
    val segments: List<Segment>,
    val tasks: List<TaskItem>,
    val memories: List<MemoryItem>,
)

data class TaskExport(val ext: String, val name: String?, val icon: String?, val ok: Boolean, val ref: String?)

data class ModelTask(
    val key: String, val kind: String, val title: String, val description: String,
    val model: String, val default: String, val custom: Boolean,
)

data class ModelOption(
    val id: String, val kind: String, val name: String, val priceIn: Double?, val priceOut: Double?,
    val priceHour: Double?, val badge: String?, val note: String?,
)

data class ModelsInfo(
    val configured: Boolean, val provider: String, val sttProvider: String, val tasks: List<ModelTask>,
    val catalog: List<ModelOption>, val all: List<ModelOption>, val spendTotal: Double, val spend: Map<String, Double>,
    val livePrices: Boolean,
)

data class ChatStep(val kind: String, val title: String, val detail: String?)
data class ChatRun(val id: Long, val level: String, val status: String, val model: String?, val steps: List<ChatStep>)
data class ChatMessage(val id: Long, val role: String, val text: String, val channel: String, val runId: Long?,
                       val createdAt: String)
data class ChatState(val messages: List<ChatMessage>, val runs: Map<Long, ChatRun>, val running: Boolean)

data class ExtSetting(val key: String, val title: String, val secret: Boolean, val required: Boolean,
                      val hint: String?, val filled: Boolean, val value: String?)
data class ExtTool(val name: String, val title: String, val description: String)
data class Extension(
    val id: String, val name: String, val icon: String?, val description: String, val author: String?,
    val version: String?, val hosts: List<String>, val tools: List<ExtTool>, val settings: List<ExtSetting>,
    val taskSync: String?, val enabled: Boolean, val ready: Boolean, val installed: Boolean, val verified: Boolean,
)

data class Features(val diary: Boolean, val photoStyle: Boolean, val style: String, val photosPerDay: Int,
                    val styles: List<Pair<String, String>>)

data class Highlight(val time: String, val emoji: String, val title: String, val text: String, val kind: String,
                     val photoId: Long?)
data class Place(val name: String?, val from: String, val to: String)
data class DiaryDay(
    val day: String, val title: String?, val summary: String?, val mood: String?, val quote: String?,
    val highlights: List<Highlight>, val places: List<Place>, val coverPhotoId: Long?, val status: String,
    val error: String?, val stats: Map<String, Int>,
)
data class DiaryInfo(val days: List<DiaryDay>, val photoCaptions: Map<Long, String>)

data class ServerStatus(
    val conversations: Int,
    val openTasks: Int,
    val memories: Int,
    val sttProvider: String,
    val llmConfigured: Boolean,
    val chunksPending: Int,
)

/** REST API сервера NeuroSoft (gateway). */
object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private suspend fun call(method: String, path: String, body: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            val prefs = Core.prefs
            if (prefs.serverUrl.isBlank() || prefs.token.isBlank()) {
                throw ApiException("Укажите адрес сервера и токен на вкладке «Кулон»")
            }
            val request = try {
                Request.Builder()
                    .url(prefs.serverUrl + path)
                    .header("Authorization", "Bearer ${prefs.token}")
                    .method(method, body?.toString()?.toRequestBody(json))
                    .build()
            } catch (e: IllegalArgumentException) {
                throw ApiException("Неверный адрес сервера")
            }
            try {
                client.newCall(request).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    when {
                        r.code == 401 -> throw ApiException("Сервер не принял токен")
                        r.code == 404 -> throw ApiException("Не найдено")
                        !r.isSuccessful -> throw ApiException("Ошибка сервера ${r.code}")
                    }
                    text
                }
            } catch (e: IOException) {
                throw ApiException("Нет связи с сервером: ${e.message ?: e.javaClass.simpleName}")
            }
        }

    suspend fun status(): ServerStatus {
        val o = JSONObject(call("GET", "/api/status"))
        val chunks = o.optJSONObject("chunks") ?: JSONObject()
        return ServerStatus(
            conversations = o.optInt("conversations"),
            openTasks = o.optInt("open_tasks"),
            memories = o.optInt("memories"),
            sttProvider = o.optString("stt_provider"),
            llmConfigured = o.optBoolean("llm_configured"),
            chunksPending = chunks.optInt("pending") + chunks.optInt("processing"),
        )
    }

    suspend fun conversations(limit: Int = 100): List<ConversationItem> =
        JSONObject(call("GET", "/api/conversations?limit=$limit")).getJSONArray("items").map { conversation(it) }

    suspend fun conversation(id: Long): ConversationDetail {
        val o = JSONObject(call("GET", "/api/conversations/$id"))
        return ConversationDetail(
            item = conversation(o),
            segments = o.optJSONArray("segments").map {
                Segment(it.optString("started_at"), it.intOrNull("speaker"), it.optString("text"))
            },
            tasks = o.optJSONArray("tasks").map { task(it) },
            memories = o.optJSONArray("memories").map { memory(it) },
        )
    }

    suspend fun deleteConversation(id: Long) {
        call("DELETE", "/api/conversations/$id")
    }

    suspend fun reprocessConversation(id: Long) {
        call("POST", "/api/conversations/$id/reprocess", JSONObject())
    }

    suspend fun tasks(status: String): List<TaskItem> =
        JSONObject(call("GET", "/api/tasks?status=$status")).getJSONArray("items").map { task(it) }

    suspend fun setTaskStatus(id: Long, status: String) {
        call("POST", "/api/tasks/$id", JSONObject().put("status", status))
    }

    suspend fun memories(): List<MemoryItem> =
        JSONObject(call("GET", "/api/memories")).getJSONArray("items").map { memory(it) }

    // ---------------------------------------------------------------- модели
    suspend fun models(all: Boolean = false): ModelsInfo {
        val o = JSONObject(call("GET", "/api/models" + if (all) "?all=1" else ""))
        val spend = o.optJSONObject("spend_30d") ?: JSONObject()
        return ModelsInfo(
            configured = o.optBoolean("configured"),
            provider = o.optString("provider"),
            sttProvider = o.optString("stt_provider"),
            tasks = o.optJSONArray("tasks").map {
                ModelTask(it.optString("key"), it.optString("kind"), it.optString("title"),
                    it.optString("description"), it.optString("model"), it.optString("default"),
                    it.optBoolean("custom"))
            },
            catalog = o.optJSONArray("catalog").map { modelOption(it) },
            all = o.optJSONArray("all").map { modelOption(it) },
            spendTotal = o.optDouble("spend_30d_total", 0.0),
            spend = spend.keys().asSequence().associateWith { spend.optDouble(it, 0.0) },
            livePrices = o.optBoolean("live_prices"),
        )
    }

    suspend fun setModel(task: String, model: String?) {
        call("POST", "/api/models", JSONObject().put("task", task).put("model", model ?: ""))
    }

    private fun modelOption(o: JSONObject) = ModelOption(
        id = o.optString("id"), kind = o.optString("kind", "llm"), name = o.optString("name", o.optString("id")),
        priceIn = o.dbl("price_in"), priceOut = o.dbl("price_out"), priceHour = o.dbl("price_hour"),
        badge = o.str("badge"), note = o.str("note"),
    )

    // ---------------------------------------------------------------- чат с агентом
    suspend fun chat(): ChatState {
        val o = JSONObject(call("GET", "/api/chat?limit=80"))
        val runsJson = o.optJSONObject("runs") ?: JSONObject()
        val runs = runsJson.keys().asSequence().associate { key ->
            val r = runsJson.getJSONObject(key)
            key.toLong() to ChatRun(
                id = r.getLong("id"), level = r.optString("level"), status = r.optString("status"),
                model = r.str("model"),
                steps = r.optJSONArray("steps").map { ChatStep(it.optString("kind"), it.optString("title"), it.str("detail")) },
            )
        }
        return ChatState(
            messages = o.optJSONArray("messages").map {
                ChatMessage(it.getLong("id"), it.optString("role"), it.optString("text"), it.optString("channel"),
                    if (it.isNull("run_id")) null else it.optLong("run_id"), it.optString("created_at"))
            },
            runs = runs,
            running = o.optBoolean("running"),
        )
    }

    suspend fun sendChat(text: String, level: String) {
        call("POST", "/api/chat", JSONObject().put("text", text).put("level", level))
    }

    suspend fun clearChat() {
        call("POST", "/api/chat/clear", JSONObject())
    }

    // ---------------------------------------------------------------- расширения
    suspend fun extensions(): Pair<List<Extension>, List<Extension>> {
        val o = JSONObject(call("GET", "/api/extensions"))
        return o.optJSONArray("installed").map { extension(it, true) } to
            o.optJSONArray("available").map { extension(it, false) }
    }

    suspend fun installExtension(id: String? = null, manifestUrl: String? = null) {
        val body = JSONObject()
        if (id != null) body.put("id", id)
        if (manifestUrl != null) body.put("manifest_url", manifestUrl)
        call("POST", "/api/extensions/install", body)
    }

    suspend fun updateExtension(id: String, enabled: Boolean? = null, settings: Map<String, String>? = null) {
        val body = JSONObject()
        if (enabled != null) body.put("enabled", enabled)
        if (settings != null) body.put("settings", JSONObject(settings as Map<*, *>))
        call("POST", "/api/extensions/$id", body)
    }

    suspend fun deleteExtension(id: String) {
        call("DELETE", "/api/extensions/$id")
    }

    private fun extension(o: JSONObject, installed: Boolean) = Extension(
        id = o.optString("id"), name = o.optString("name"), icon = o.str("icon"),
        description = o.optString("description"), author = o.str("author"), version = o.str("version"),
        hosts = o.optJSONArray("hosts").strings(),
        tools = o.optJSONArray("tools").map { ExtTool(it.optString("name"), it.optString("title"), it.optString("description")) },
        settings = o.optJSONArray("settings").map {
            ExtSetting(it.optString("key"), it.optString("title"), it.optBoolean("secret"), it.optBoolean("required"),
                it.str("hint"), it.optBoolean("filled"), it.str("value"))
        },
        taskSync = o.str("task_sync"), enabled = o.optBoolean("enabled", false),
        ready = o.optBoolean("ready", false), installed = installed, verified = o.optBoolean("verified", false),
    )

    // ---------------------------------------------------------------- дневник
    suspend fun features(): Features = features(JSONObject(call("GET", "/api/features")))

    suspend fun setFeatures(changes: Map<String, Any>): Features =
        features(JSONObject(call("POST", "/api/features", JSONObject(changes as Map<*, *>))))

    private fun features(o: JSONObject) = Features(
        diary = o.optBoolean("diary", true), photoStyle = o.optBoolean("photo_style", true),
        style = o.optString("style"), photosPerDay = o.optInt("photos_per_day", 3),
        styles = o.optJSONArray("styles").map { it.optString("key") to it.optString("title") },
    )

    suspend fun diary(): DiaryInfo {
        val o = JSONObject(call("GET", "/api/diary?limit=21"))
        val photos = o.optJSONObject("photos") ?: JSONObject()
        return DiaryInfo(
            days = o.optJSONArray("days").map { d ->
                val stats = d.optJSONObject("stats") ?: JSONObject()
                DiaryDay(
                    day = d.optString("day"), title = d.str("title"), summary = d.str("summary"), mood = d.str("mood"),
                    quote = d.str("quote"),
                    highlights = d.optJSONArray("highlights").map {
                        Highlight(it.optString("time"), it.optString("emoji", "✨"), it.optString("title"),
                            it.optString("text"), it.optString("kind"),
                            if (it.isNull("photo_id")) null else it.optLong("photo_id"))
                    },
                    places = d.optJSONArray("places").map { Place(it.str("name"), it.optString("from"), it.optString("to")) },
                    coverPhotoId = if (d.isNull("cover_photo_id")) null else d.optLong("cover_photo_id"),
                    status = d.optString("status"), error = d.str("error"),
                    stats = stats.keys().asSequence().associateWith { stats.optInt(it) },
                )
            },
            photoCaptions = photos.keys().asSequence().associate { k ->
                k.toLong() to (photos.getJSONObject(k).str("caption") ?: "")
            },
        )
    }

    suspend fun generateDiary(day: String) {
        call("POST", "/api/diary/$day/generate", JSONObject())
    }

    /** Ссылка на фото и заголовок авторизации — для загрузки картинок в ленте. */
    fun photoUrl(id: Long, original: Boolean = false): String =
        Core.prefs.serverUrl + "/api/photos/$id" + if (original) "?variant=original" else ""

    fun authHeader(): String = "Bearer ${Core.prefs.token}"

    suspend fun uploadPhoto(jpeg: ByteArray, takenAt: Long, lat: Double?, lon: Double?): Long =
        withContext(Dispatchers.IO) {
            val prefs = Core.prefs
            val q = StringBuilder("/api/photos?taken_at=$takenAt")
            if (lat != null && lon != null) q.append("&lat=$lat&lon=$lon")
            val request = Request.Builder()
                .url(prefs.serverUrl + q)
                .header("Authorization", "Bearer ${prefs.token}")
                .post(jpeg.toRequestBody("image/jpeg".toMediaType()))
                .build()
            client.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 409) throw ApiException("Дневник выключен в настройках")
                if (!r.isSuccessful) throw ApiException("Ошибка загрузки фото ${r.code}")
                JSONObject(text).optLong("id")
            }
        }

    // ---------------------------------------------------------------- разбор JSON
    private fun conversation(o: JSONObject) = ConversationItem(
        id = o.getLong("id"),
        startedAt = o.optString("started_at"),
        endedAt = o.optString("ended_at"),
        status = o.optString("status"),
        title = o.str("title"),
        summary = o.str("summary"),
        tasksCount = o.optInt("tasks_count"),
        preview = o.str("preview"),
    )

    private fun task(o: JSONObject) = TaskItem(
        id = o.getLong("id"),
        text = o.optString("text"),
        due = o.str("due"),
        assignee = o.str("assignee"),
        status = o.optString("status"),
        source = o.optString("source"),
        conversationId = if (o.isNull("conversation_id")) null else o.optLong("conversation_id"),
        conversationTitle = o.str("conversation_title"),
        exportRef = o.str("export_ref"),
        exports = o.optJSONArray("exports").map {
            TaskExport(it.optString("ext"), it.str("name"), it.str("icon"), it.optBoolean("ok"), it.str("ref"))
        },
        createdAt = o.optString("created_at"),
    )

    private fun memory(o: JSONObject) = MemoryItem(
        id = o.getLong("id"),
        text = o.optString("text"),
        source = o.optString("source"),
        createdAt = o.optString("created_at"),
        conversationTitle = o.str("conversation_title"),
    )
}

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }

private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)

private fun JSONObject.dbl(key: String): Double? = if (isNull(key)) null else optDouble(key)

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }
}

private fun <T> JSONArray?.map(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).map { transform(getJSONObject(it)) }
}
