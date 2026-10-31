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

/** Ошибка API. kind — для понятного текста на языке приложения, detail — подробности. */
class ApiException(val kind: Kind, val detail: String? = null) : Exception(detail ?: kind.name) {
    enum class Kind { NOT_CONFIGURED, NETWORK, AUTH, NOT_FOUND, SERVER, DIARY_OFF, PAIR_BAD, PAIR_EXPIRED, PAIR_LOCKED }
}

data class ConversationItem(
    val id: Long,
    val startedAt: String,
    val endedAt: String,
    val status: String,
    val title: String?,
    val summary: String?,
    val tasksCount: Int,
    val preview: String?,
    val emoji: String? = null,
    val durationSec: Int = 0,
    val people: List<PersonRef> = emptyList(),
    val topics: List<String> = emptyList(),
)

/** Человек, узнанный по голосу: имя или «Голос N»; владелец кулона — «Я». */
data class PersonRef(val id: Long, val name: String?, val number: Int, val isOwner: Boolean = false,
                     val ignored: Boolean = false)

data class Person(
    val ref: PersonRef,
    val conversations: Int,
    val speechSec: Int,
    val samples: Int,
    val firstSeen: String?,
    val lastSeen: String?,
    val hasSample: Boolean,
    val note: String?,
) {
    val id get() = ref.id
}

data class PersonConversation(val id: Long, val title: String?, val emoji: String?, val startedAt: String,
                              val durationSec: Int, val speechSec: Int)
data class PersonQuote(val segmentId: Long, val text: String, val at: String, val conversationId: Long?)
data class PersonTogether(val ref: PersonRef, val conversations: Int)
data class NameSuggestion(val id: Long, val personId: Long, val name: String, val evidence: String?,
                          val conversationId: Long? = null, val conversationTitle: String? = null,
                          val number: Int = 0, val isOwner: Boolean = false)

data class PersonDetail(
    val person: Person,
    val recent: List<PersonConversation>,
    val topics: List<String>,
    val tasks: List<TaskItem>,
    val quotes: List<PersonQuote>,
    val together: List<PersonTogether>,
    val suggestions: List<NameSuggestion>,
    val sameName: Long? = null,
)

data class PeopleInfo(val people: List<Person>, val suggestions: List<NameSuggestion>, val ownerCandidate: Person?,
                      val enabled: Boolean, val others: Boolean)

data class GraphTopic(val name: String, val count: Int, val firstAt: String)
data class GraphConversation(val id: Long, val title: String?, val emoji: String?, val startedAt: String,
                             val durationSec: Int, val people: List<Long>, val topics: List<String>, val photoId: Long?)
data class Graph(val people: List<Person>, val topics: List<GraphTopic>, val conversations: List<GraphConversation>)

/** День с записями: для ленты дней и календаря. */
data class DayInfo(val day: String, val conversations: Int, val seconds: Int, val speechSeconds: Int = 0)

data class SearchHit(val kind: String, val id: Long?, val conversationId: Long?, val title: String, val text: String?,
                     val at: String?, val emoji: String?, val done: Boolean = false)
data class SearchResults(val conversations: List<SearchHit>, val segments: List<SearchHit>, val tasks: List<SearchHit>,
                         val memories: List<SearchHit>) {
    val isEmpty get() = conversations.isEmpty() && segments.isEmpty() && tasks.isEmpty() && memories.isEmpty()
}

data class Segment(val startedAt: String, val speaker: Int?, val text: String, val id: Long = 0,
                   val personId: Long? = null, val sure: Boolean = true, val hasAudio: Boolean = false)

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
    val personId: Long? = null,
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
    val people: Map<Long, PersonRef> = emptyMap(),
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

/** Режим чата «Claude»: токен на сервере (сюда приходит только хвост), сервис claude и его репозиторий. */
data class ClaudeInfo(
    val configured: Boolean, val tokenHint: String?, val session: Boolean, val model: String, val models: List<String>,
    val available: Boolean, val version: String?, val repo: String?, val head: String?, val dirty: Boolean,
    val deployKey: String?, val busy: Boolean,
)

data class ExtSetting(val key: String, val title: String, val secret: Boolean, val required: Boolean,
                      val hint: String?, val filled: Boolean, val value: String?)
data class ExtTool(val name: String, val title: String, val description: String)
data class Extension(
    val id: String, val name: String, val icon: String?, val description: String, val author: String?,
    val version: String?, val hosts: List<String>, val tools: List<ExtTool>, val settings: List<ExtSetting>,
    val taskSync: String?, val enabled: Boolean, val ready: Boolean, val installed: Boolean, val verified: Boolean,
)

data class Features(val diary: Boolean, val photoStyle: Boolean, val photoAi: Boolean = true, val style: String, val photosPerDay: Int,
                    val styles: List<Pair<String, String>>, val language: String = "ru",
                    val sttLanguage: String = "app", val audioKeepDays: Int = 3,
                    val audioKeepChoices: List<Int> = listOf(0, 1, 3, 7, 30))

data class Highlight(val time: String, val emoji: String, val title: String, val text: String, val kind: String,
                     val photoId: Long?)
data class Place(val name: String?, val from: String, val to: String, val lat: Double? = null, val lon: Double? = null)
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

/** REST API своего сервера (gateway). */
object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private suspend fun call(method: String, path: String, body: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            val prefs = Core.prefs
            if (!prefs.serverConfigured) throw ApiException(ApiException.Kind.NOT_CONFIGURED)
            val request = try {
                Request.Builder()
                    .url(prefs.serverUrl + path)
                    .header("Authorization", "Bearer ${prefs.token}")
                    .method(method, body?.toString()?.toRequestBody(json))
                    .build()
            } catch (e: IllegalArgumentException) {
                throw ApiException(ApiException.Kind.NOT_CONFIGURED, e.message)
            }
            try {
                client.newCall(request).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    when {
                        r.code == 401 || r.code == 403 -> throw ApiException(ApiException.Kind.AUTH)
                        r.code == 404 -> throw ApiException(ApiException.Kind.NOT_FOUND)
                        r.code == 409 -> throw ApiException(ApiException.Kind.DIARY_OFF)
                        !r.isSuccessful -> throw ApiException(ApiException.Kind.SERVER, "${r.code}")
                    }
                    text
                }
            } catch (e: IOException) {
                throw ApiException(ApiException.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
            }
        }

    /**
     * Одноразовый код подключения -> ключ доступа. Вызывается без ключа, по введённому адресу.
     * Ошибки: NOT_FOUND — сервер старый или адрес не тот; PAIR_BAD / PAIR_EXPIRED / PAIR_LOCKED.
     */
    suspend fun pair(serverUrl: String, code: String): String = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(serverUrl.trim().trimEnd('/') + "/api/pair")
                .post(JSONObject().put("code", code).toString().toRequestBody(json)).build()
        } catch (e: IllegalArgumentException) {
            throw ApiException(ApiException.Kind.NOT_CONFIGURED, e.message)
        }
        try {
            client.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                when (r.code) {
                    200 -> JSONObject(text).optString("token").ifBlank { throw ApiException(ApiException.Kind.SERVER, "empty") }
                    403 -> throw ApiException(ApiException.Kind.PAIR_BAD)
                    410 -> throw ApiException(ApiException.Kind.PAIR_EXPIRED)
                    423, 429 -> throw ApiException(ApiException.Kind.PAIR_LOCKED)
                    404, 405 -> throw ApiException(ApiException.Kind.NOT_FOUND)
                    else -> throw ApiException(ApiException.Kind.SERVER, "${r.code}")
                }
            }
        } catch (e: IOException) {
            throw ApiException(ApiException.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
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

    suspend fun conversations(limit: Int = 100, day: String? = null): List<ConversationItem> =
        JSONObject(call("GET", "/api/conversations?limit=$limit" + (day?.let { "&day=$it" } ?: "")))
            .getJSONArray("items").map { conversation(it) }

    suspend fun days(): List<DayInfo> =
        JSONObject(call("GET", "/api/days")).getJSONArray("items").map {
            DayInfo(it.optString("day").take(10), it.optInt("conversations"), it.optInt("seconds"), it.optInt("speech_seconds"))
        }

    suspend fun search(q: String): SearchResults {
        val o = JSONObject(call("GET", "/api/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")))
        return SearchResults(
            conversations = o.optJSONArray("conversations").map {
                SearchHit("conversation", it.getLong("id"), it.getLong("id"), it.str("title") ?: "", it.str("summary"),
                    it.str("started_at"), it.str("emoji"))
            },
            segments = o.optJSONArray("segments").map {
                val cid = if (it.isNull("conversation_id")) null else it.optLong("conversation_id")
                SearchHit("segment", null, cid, it.str("title") ?: "", it.str("text"), it.str("started_at"), it.str("emoji"))
            },
            tasks = o.optJSONArray("tasks").map {
                SearchHit("task", it.getLong("id"), null, it.optString("text"), it.str("due"), null, null,
                    done = it.optString("status") == "done")
            },
            memories = o.optJSONArray("memories").map {
                SearchHit("memory", it.getLong("id"), null, it.optString("text"), null, it.str("created_at"), null)
            },
        )
    }

    suspend fun conversation(id: Long): ConversationDetail {
        val o = JSONObject(call("GET", "/api/conversations/$id"))
        return ConversationDetail(
            item = conversation(o),
            segments = o.optJSONArray("segments").map {
                Segment(it.optString("started_at"), it.intOrNull("speaker"), it.optString("text"), it.optLong("id"),
                    it.longOrNull("person_id"), it.optBoolean("sure", true), it.optBoolean("has_audio"))
            },
            tasks = o.optJSONArray("tasks").map { task(it) },
            memories = o.optJSONArray("memories").map { memory(it) },
            people = o.optJSONArray("people").map { personRef(it) }.associateBy { it.id },
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

    // ---------------------------------------------------------------- Claude Code
    suspend fun claude(): ClaudeInfo = claudeInfo(JSONObject(call("GET", "/api/claude")))

    suspend fun setClaude(token: String? = null, model: String? = null, newSession: Boolean = false,
                          cancel: Boolean = false): ClaudeInfo {
        val body = JSONObject()
        if (!token.isNullOrBlank()) body.put("token", token.trim())
        if (model != null) body.put("model", model)
        if (newSession) body.put("new_session", true)
        if (cancel) body.put("cancel", true)
        return claudeInfo(JSONObject(call("POST", "/api/claude", body)))
    }

    suspend fun disconnectClaude(): ClaudeInfo = claudeInfo(JSONObject(call("DELETE", "/api/claude")))

    private fun claudeInfo(o: JSONObject): ClaudeInfo {
        val s = o.optJSONObject("service") ?: JSONObject()
        return ClaudeInfo(
            configured = o.optBoolean("configured"), tokenHint = o.str("token_hint"), session = o.optBoolean("session"),
            model = o.optString("model"), models = o.optJSONArray("models").strings().ifEmpty { listOf("") },
            available = s.optBoolean("available"), version = s.str("version"), repo = s.str("repo"),
            head = s.str("head"), dirty = s.optBoolean("dirty"), deployKey = s.str("deploy_key"),
            busy = s.optBoolean("busy"),
        )
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
        photoAi = o.optBoolean("photo_ai", true),
        style = o.optString("style"), photosPerDay = o.optInt("photos_per_day", 3),
        styles = o.optJSONArray("styles").map { it.optString("key") to it.optString("title") },
        language = o.optString("language", "ru"), sttLanguage = o.optString("stt_language", "app"),
        audioKeepDays = o.optInt("audio_keep_days", 3),
        audioKeepChoices = o.optJSONArray("audio_keep_choices")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
            ?: listOf(0, 1, 3, 7, 30),
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
                    places = d.optJSONArray("places").map {
                        Place(it.str("name"), it.optString("from"), it.optString("to"), it.dbl("lat"), it.dbl("lon"))
                    },
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
            try {
                client.newCall(request).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (r.code == 409) throw ApiException(ApiException.Kind.DIARY_OFF)
                    if (!r.isSuccessful) throw ApiException(ApiException.Kind.SERVER, "${r.code}")
                    JSONObject(text).optLong("id")
                }
            } catch (e: IOException) {
                throw ApiException(ApiException.Kind.NETWORK, e.message)
            }
        }

    // ---------------------------------------------------------------- люди (голоса)
    suspend fun people(): PeopleInfo {
        val o = JSONObject(call("GET", "/api/people"))
        val st = o.optJSONObject("settings") ?: JSONObject()
        return PeopleInfo(
            people = o.optJSONArray("people").map { person(it) },
            suggestions = o.optJSONArray("suggestions").map { suggestion(it) },
            ownerCandidate = o.optJSONObject("owner_candidate")?.let { person(it) },
            enabled = st.optBoolean("enabled", true), others = st.optBoolean("others", true),
        )
    }

    suspend fun person(id: Long): PersonDetail = personDetail(JSONObject(call("GET", "/api/people/$id")))

    /** Имя («» — убрать), «это я», «не учитывать», заметка. */
    suspend fun updatePerson(id: Long, name: String? = null, isOwner: Boolean? = null, ignored: Boolean? = null,
                             clearName: Boolean = false): PersonDetail {
        val body = JSONObject()
        if (name != null) body.put("name", name)
        if (clearName) body.put("name", "")
        if (isOwner != null) body.put("is_owner", isOwner)
        if (ignored != null) body.put("ignored", ignored)
        return personDetail(JSONObject(call("POST", "/api/people/$id", body)))
    }

    suspend fun mergePerson(id: Long, into: Long): PersonDetail =
        personDetail(JSONObject(call("POST", "/api/people/$id/merge", JSONObject().put("into", into))))

    suspend fun forgetPerson(id: Long) {
        call("DELETE", "/api/people/$id")
    }

    suspend fun forgetAllVoices(): Int = JSONObject(call("POST", "/api/people/forget", JSONObject())).optInt("forgotten")

    suspend fun setVoiceSettings(enabled: Boolean? = null, others: Boolean? = null): Pair<Boolean, Boolean> {
        val body = JSONObject()
        if (enabled != null) body.put("enabled", enabled)
        if (others != null) body.put("others", others)
        val o = JSONObject(call("POST", "/api/people/settings", body))
        return o.optBoolean("enabled", true) to o.optBoolean("others", true)
    }

    suspend fun answerSuggestion(id: Long, accept: Boolean): Long? =
        JSONObject(call("POST", "/api/people/suggestions/$id", JSONObject().put("accept", accept))).longOrNull("person_id")

    /** «Кто это?» для одной фразы: человек из списка, новый по имени или null — снять подпись. */
    suspend fun assignSegment(segmentId: Long, personId: Long?, name: String? = null): Long? {
        val body = JSONObject().put("person_id", personId ?: JSONObject.NULL)
        if (name != null) body.put("name", name)
        return JSONObject(call("POST", "/api/segments/$segmentId/person", body)).longOrNull("person_id")
    }

    fun sampleUrl(personId: Long): String = Core.prefs.serverUrl + "/api/people/$personId/sample"

    fun segmentAudioUrl(segmentId: Long): String = Core.prefs.serverUrl + "/api/segments/$segmentId/audio"

    suspend fun graph(days: Int = 90): Graph {
        val o = JSONObject(call("GET", "/api/graph?days=$days"))
        return Graph(
            people = o.optJSONArray("people").map { person(it) },
            topics = o.optJSONArray("topics").map {
                GraphTopic(it.optString("name"), it.optInt("count"), it.optString("first_at"))
            },
            conversations = o.optJSONArray("conversations").map { c ->
                val ids = c.optJSONArray("people")
                GraphConversation(
                    id = c.getLong("id"), title = c.str("title"), emoji = c.str("emoji"),
                    startedAt = c.optString("started_at"), durationSec = c.optInt("duration_sec"),
                    people = if (ids == null) emptyList() else (0 until ids.length()).map { ids.optLong(it) },
                    topics = c.optJSONArray("topics").strings(), photoId = c.longOrNull("photo_id"),
                )
            },
        )
    }

    private fun personRef(o: JSONObject) = PersonRef(
        id = o.getLong("id"), name = o.str("name"), number = o.optInt("number"),
        isOwner = o.optBoolean("is_owner"), ignored = o.optBoolean("ignored"),
    )

    private fun person(o: JSONObject) = Person(
        ref = personRef(o), conversations = o.optInt("conversations"), speechSec = o.optDouble("speech_sec", 0.0).toInt(),
        samples = o.optInt("samples"), firstSeen = o.str("first_seen"), lastSeen = o.str("last_seen"),
        hasSample = o.optBoolean("has_sample"), note = o.str("note"),
    )

    private fun suggestion(o: JSONObject) = NameSuggestion(
        id = o.getLong("id"), personId = o.optLong("person_id"), name = o.optString("name"), evidence = o.str("evidence"),
        conversationId = o.longOrNull("conversation_id"), conversationTitle = o.str("conversation_title"),
        number = o.optInt("number"), isOwner = o.optBoolean("is_owner"),
    )

    private fun personDetail(o: JSONObject) = PersonDetail(
        person = person(o),
        recent = o.optJSONArray("recent").map {
            PersonConversation(it.getLong("id"), it.str("title"), it.str("emoji"), it.optString("started_at"),
                it.optInt("duration_sec"), it.optInt("speech_sec"))
        },
        topics = o.optJSONArray("topics").strings(),
        tasks = o.optJSONArray("tasks").map { task(it) },
        quotes = o.optJSONArray("quotes").map {
            PersonQuote(it.getLong("id"), it.optString("text"), it.optString("started_at"), it.longOrNull("conversation_id"))
        },
        together = o.optJSONArray("together").map { PersonTogether(personRef(it), it.optInt("conversations")) },
        suggestions = o.optJSONArray("suggestions").map {
            NameSuggestion(it.getLong("id"), o.getLong("id"), it.optString("name"), it.str("evidence"))
        },
        sameName = o.longOrNull("same_name"),
    )

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
        emoji = o.str("emoji"),
        durationSec = o.optInt("duration_sec"),
        people = o.optJSONArray("people").map { personRef(it) },
        topics = o.optJSONArray("topics").strings(),
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
        personId = o.longOrNull("person_id"),
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

private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)

private fun JSONObject.dbl(key: String): Double? = if (isNull(key)) null else optDouble(key)

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }
}

private fun <T> JSONArray?.map(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).map { transform(getJSONObject(it)) }
}
