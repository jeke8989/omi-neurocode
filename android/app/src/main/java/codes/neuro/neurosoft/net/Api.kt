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
    /** Важный разговор: двойное нажатие кнопки во время разговора или звёздочка. */
    val important: Boolean = false,
)

/** Человек, узнанный по голосу: имя или «Голос N»; владелец кулона — «Я». */
data class PersonRef(val id: Long, val name: String?, val number: Int, val isOwner: Boolean = false,
                     val ignored: Boolean = false,
                     /** Голос прозвучал в одном разговоре — «Собеседник», в «Людях» не показывается. */
                     val transient: Boolean = false,
                     /** media — реклама, ТВ, объявления; crowd — люди рядом, не в разговоре; null/person — человек. */
                     val kind: String? = null) {
    val background: Boolean get() = ignored || kind == "media" || kind == "crowd"
}

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
                          val number: Int = 0, val isOwner: Boolean = false,
                          /** «Голос 7 похож на …» — объединить с этим человеком. */
                          val targetId: Long? = null, val targetOwner: Boolean = false)

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
/** Событие в «Созвездии»: звезда-событие, связанная с другими через общих людей и темы. */
data class GraphEvent(val id: Long, val title: String?, val emoji: String?, val kind: String, val startedAt: String,
                      val durationSec: Int, val people: List<Long>, val topics: List<String>, val photoId: Long?,
                      val firstConversation: Long?, val fragments: Int = 1, val day: String? = null,
                      /** Яркость воспоминания 0..1 (крупнее звезда на карте). */
                      val vivid: Float = 0.3f, val summary: String? = null)
data class Graph(val people: List<Person>, val topics: List<GraphTopic>, val conversations: List<GraphConversation>,
                 val events: List<GraphEvent> = emptyList())

/** Событие дня: несколько фрагментов записи, собранных ассистентом в одну ситуацию («Обед с Анной»). */
data class EventItem(
    val id: Long, val day: String, val startedAt: String, val endedAt: String, val title: String?, val emoji: String?,
    val kind: String, val summary: String?, val topics: List<String>, val photoId: Long?, val people: List<PersonRef>,
    val fragments: List<ConversationItem>, val final: Boolean = false,
    val lat: Double? = null, val lon: Double? = null,
) {
    val durationSec: Int get() = fragments.sumOf { it.durationSec }
    val tasksCount: Int get() = fragments.sumOf { it.tasksCount }
    val important: Boolean get() = fragments.any { it.important }
}

/** Лента дня: события и ещё не разобранные фрагменты (последний час). grouped=false — сервер старый, без событий. */
data class DayEvents(val day: String, val events: List<EventItem>, val loose: List<ConversationItem>,
                     val grouped: Boolean = true) {
    val totalSec: Long get() = events.sumOf { it.durationSec.toLong() } + loose.sumOf { it.durationSec.toLong() }
    val isEmpty: Boolean get() = events.isEmpty() && loose.isEmpty()
}

/** Фрагмент в корзине: ассистент счёл его мусором (шум, телевизор); лежит 7 дней, можно вернуть. */
data class TrashItem(val id: Long, val startedAt: String, val endedAt: String, val title: String?, val emoji: String?,
                     val reason: String?, val preview: String?)

/** День с записями: для ленты дней и календаря. */
data class DayInfo(val day: String, val conversations: Int, val seconds: Int, val speechSeconds: Int = 0)

data class SearchHit(val kind: String, val id: Long?, val conversationId: Long?, val title: String, val text: String?,
                     val at: String?, val emoji: String?, val done: Boolean = false)
data class SearchResults(val conversations: List<SearchHit>, val segments: List<SearchHit>, val tasks: List<SearchHit>,
                         val memories: List<SearchHit>) {
    val isEmpty get() = conversations.isEmpty() && segments.isEmpty() && tasks.isEmpty() && memories.isEmpty()
}

data class Segment(val startedAt: String, val speaker: Int?, val text: String, val id: Long = 0,
                   val personId: Long? = null, val sure: Boolean = true, val hasAudio: Boolean = false,
                   /** Исходный текст распознавания, если ассистент исправил фразу. */
                   val raw: String? = null)

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
    /** mine — делаю я, waiting — жду от другого, reminder — напоминание. */
    val kind: String = "mine",
    /** Срок как время (ISO) — по нему приходит напоминание. */
    val dueAt: String? = null,
    val evidence: String? = null,
    /** С кем договорённость: кому я обещал или кто обещал мне. */
    val counterpart: String? = null,
    /** Встреча в календаре телефона: id, длительность; inCalendar — она ещё есть в календаре. */
    val calendarEventId: Long? = null,
    val calendarMinutes: Int? = null,
    val inCalendar: Boolean = false,
    /** Человек из «Людей», с кем договорённость (имя берётся оттуда). */
    val counterpartId: Long? = null,
    val counterpartNumber: Int? = null,
    /** Подтверждено «Да» в карточке. */
    val confirmedAt: String? = null,
    /** Когда выполнено — для счётчика «выполнено N из M». */
    val doneAt: String? = null,
) {
    /** «Мне обещали» — ждём от другого; иначе обещал я (или напоминание себе). */
    val theirs: Boolean get() = kind == "waiting"

    /** С кем: имя собеседника (служебные подписи модели не показываем). */
    val mate: String? get() = ((counterpart ?: if (theirs) assignee else null)
        ?.takeUnless { Regex("^(владелец|пользователь|спикер|голос|неизвестн|owner|user|speaker|voice|unknown)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        ?.split(" ")?.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } })
        ?: counterpartNumber?.let { codes.neuro.neurosoft.Core.res().getString(codes.neuro.neurosoft.R.string.voice_n, it) }

    fun dueMillis(): Long? = try {
        dueAt?.let { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }
    } catch (_: Exception) {
        null
    }
}

data class MemoryItem(
    val id: Long,
    val text: String,
    val source: String,
    val createdAt: String,
    val conversationTitle: String?,
    /** Запись уточнялась: новый факт объединён со старым или исправлена вручную. */
    val updatedAt: String? = null,
) {
    /** Пользователь сам попросил запомнить (кнопкой раньше или «запомни, что…» ассистенту). */
    val asked: Boolean get() = source == "button" || source == "agent"
}

/** Память: записи, сколько активных и в архиве. */
data class MemoryList(val items: List<MemoryItem>, val active: Int, val archived: Int)

data class ConversationDetail(
    val item: ConversationItem,
    val segments: List<Segment>,
    val tasks: List<TaskItem>,
    val memories: List<MemoryItem>,
    val people: Map<Long, PersonRef> = emptyMap(),
    /** Где был разговор (если в это время писалась геолокация). */
    val location: ConvLocation? = null,
)

data class LatLon(val lat: Double, val lon: Double)
data class ConvLocation(val lat: Double, val lon: Double, val name: String?, val track: List<LatLon>)
data class MapPlace(val name: String?, val lat: Double, val lon: Double, val from: String, val to: String)
/** Событие или отдельный разговор на карте дня. */
data class MapSpot(val id: Long, val event: Boolean, val title: String?, val emoji: String?, val startedAt: String,
                   val lat: Double, val lon: Double, val fragments: Int, val firstConversation: Long?)
data class DayMapData(val day: String, val track: List<LatLon>, val places: List<MapPlace>, val spots: List<MapSpot>) {
    val isEmpty get() = track.isEmpty() && places.isEmpty() && spots.isEmpty()
}

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
data class ChatRun(val id: Long, val level: String, val status: String, val model: String?, val steps: List<ChatStep>,
                   val agent: String = "mnemo", val context: String? = null)
data class ChatMessage(val id: Long, val role: String, val text: String, val channel: String, val runId: Long?,
                       val createdAt: String)
data class ChatState(val messages: List<ChatMessage>, val runs: Map<Long, ChatRun>, val running: Boolean)

data class ExtSetting(val key: String, val title: String, val secret: Boolean, val required: Boolean,
                      val hint: String?, val filled: Boolean, val value: String?, val type: String = "text")
data class ExtTool(val name: String, val title: String, val description: String)
data class Extension(
    val id: String, val name: String, val icon: String?, val description: String, val author: String?,
    val version: String?, val hosts: List<String>, val tools: List<ExtTool>, val settings: List<ExtSetting>,
    val taskSync: String?, val enabled: Boolean, val ready: Boolean, val installed: Boolean, val verified: Boolean,
    /** На какие события подписана (вебхук, n8n…) и сколько уже отправлено. */
    val events: List<ExtEvent> = emptyList(), val delivery: ExtDelivery? = null, val sourceUrl: String? = null,
)

data class ExtEvent(val event: String, val title: String?, val toUserUrl: Boolean)
data class ExtDelivery(val sent: Int, val waiting: Int, val failed: Int, val lastSent: String?, val error: String?)

/** Черновик интеграции от ассистента: manifest (как есть, для установки), что она умеет, что сделать владельцу. */
data class ExtDraft(val manifest: JSONObject?, val preview: Extension?, val notes: String?)

data class McpInfo(val enabled: Boolean, val since: String?, val token: String? = null, val url: String)

/** Календарь телефона на сервере: подключён ли, сколько встреч, что можно ассистенту, куда добавлять. */
data class CalendarInfo(val id: Long, val name: String, val account: String, val primary: Boolean)
data class CalendarStatus(val connected: Boolean, val syncedAt: String?, val events: Int, val read: Boolean,
                          val write: Boolean, val target: Long?, val calendars: List<CalendarInfo>)

/** Постоянное правило агента («итоги встреч — в Notion»). */
data class AgentRule(val id: Long, val text: String, val createdAt: String)

/** Задание агента по расписанию («каждый день в 23:00 — сводка в Notion»). */
data class AgentSchedule(val id: Long, val title: String, val prompt: String, val whenText: String,
                         val nextRunAt: String?, val enabled: Boolean, val lastRunAt: String?, val lastStatus: String?)

data class Routines(val rules: List<AgentRule>, val schedules: List<AgentSchedule>)

/** Готовый ответ задания по расписанию (для уведомления). */
data class RoutineResult(val runId: Long, val question: String, val answer: String)

/** Знакомство: имя, чем занимается, цели; voice — none | waiting | done | failed. */
data class Profile(val name: String?, val about: String?, val goals: List<String>, val voice: String)

/** Сервис, который подключается одной кнопкой (Notion, Linear): вход через сайт сервиса, без ключей. */
data class Connector(val id: String, val name: String, val icon: String, val description: String, val status: String,
                     val account: String?, val error: String?, val tools: List<String>,
                     /** Что ассистенту можно: просматривать (поиск, чтение); создавать и изменять. */
                     val read: Boolean = true, val write: Boolean = true) {
    val connected get() = status == "connected"
}

data class Features(val diary: Boolean, val photoStyle: Boolean, val photoAi: Boolean = true, val style: String, val photosPerDay: Int,
                    val styles: List<Pair<String, String>>, val language: String = "ru",
                    val sttLanguage: String = "app", val audioKeepDays: Int = 3,
                    val audioKeepChoices: List<Int> = listOf(0, 1, 3, 7, 30),
                    val transcriptCleanup: Boolean = true)

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
    /** Очередь расшифровки на сервере, секунды звука: живой — первым, из памяти кулона — за ним. */
    val queueLiveSec: Int = 0,
    val queueOfflineSec: Int = 0,
    /** Сколько стоили модели ИИ за 30 дней, $. */
    val aiCostMonth: Double? = null,
)

/** REST API своего сервера (gateway). */
object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    /** explain — при ошибке показать текст сервера («вход устарел…»), а не код ответа. */
    /** Для ответов, которые готовит модель (письмо по итогам): до двух минут. */
    private val slowClient by lazy { client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build() }

    private suspend fun call(method: String, path: String, body: JSONObject? = null, explain: Boolean = false,
                             slow: Boolean = false): String =
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
                (if (slow) slowClient else client).newCall(request).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    when {
                        r.code == 401 || r.code == 403 -> throw ApiException(ApiException.Kind.AUTH)
                        r.code == 404 -> throw ApiException(ApiException.Kind.NOT_FOUND)
                        r.code == 409 -> throw ApiException(ApiException.Kind.DIARY_OFF)
                        !r.isSuccessful -> throw ApiException(ApiException.Kind.SERVER,
                            (if (explain) runCatching { JSONObject(text).optString("error") }.getOrNull() else null)
                                ?.takeIf { it.isNotBlank() } ?: "${r.code}")
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

    /** Запрос к серверу без ключа (подключение): код ответа и тело. */
    private suspend fun open(method: String, url: String, body: JSONObject? = null): Pair<Int, JSONObject> =
        withContext(Dispatchers.IO) {
            val request = try {
                Request.Builder().url(url).method(method, body?.toString()?.toRequestBody(json)).build()
            } catch (e: IllegalArgumentException) {
                throw ApiException(ApiException.Kind.NOT_CONFIGURED, e.message)
            }
            try {
                client.newCall(request).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    r.code to (try { JSONObject(text) } catch (e: Exception) { JSONObject() })
                }
            } catch (e: IOException) {
                throw ApiException(ApiException.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
            }
        }

    /** Первый телефон подключается без кода. null — сервер уже подключён к другому телефону. */
    suspend fun pairAuto(serverUrl: String, name: String): String? {
        val (code, o) = open("POST", serverUrl.trimEnd('/') + "/api/pair/auto", JSONObject().put("name", name))
        return when (code) {
            200 -> o.optString("token").ifBlank { null }
            409 -> null
            404, 405 -> throw ApiException(ApiException.Kind.NOT_FOUND)
            429 -> throw ApiException(ApiException.Kind.PAIR_LOCKED)
            else -> throw ApiException(ApiException.Kind.SERVER, "$code")
        }
    }

    /** Новый телефон просит доступ у уже подключённого. Возвращает id запроса. */
    suspend fun pairRequest(serverUrl: String, name: String): String {
        val (code, o) = open("POST", serverUrl.trimEnd('/') + "/api/pair/request", JSONObject().put("name", name))
        if (code != 200) throw ApiException(if (code == 404) ApiException.Kind.NOT_FOUND else ApiException.Kind.SERVER, "$code")
        return o.optString("id")
    }

    /** Ответ на запрос: pending | approved (+ключ) | rejected | expired | unknown. */
    suspend fun pairRequestStatus(serverUrl: String, id: String): Pair<String, String?> {
        val (code, o) = open("GET", serverUrl.trimEnd('/') + "/api/pair/request/" + id)
        if (code != 200) throw ApiException(ApiException.Kind.SERVER, "$code")
        return o.optString("status") to o.optString("token").ifBlank { null }
    }

    /** Кто просит подключиться к серверу (видят уже подключённые телефоны). */
    suspend fun pairRequests(): List<Pair<String, String>> =
        JSONObject(call("GET", "/api/pair/requests")).optJSONArray("items").map { it.optString("id") to it.optString("name") }

    suspend fun answerPairRequest(id: String, approve: Boolean) {
        call("POST", "/api/pair/requests/$id", JSONObject().put("approve", approve))
    }

    /** Нейроголос: WAV фразы. null — сервер ещё не готов (503) или старый: говорим системным голосом. */
    suspend fun tts(text: String, voice: String, lang: String, speed: Float): ByteArray? = withContext(Dispatchers.IO) {
        val prefs = Core.prefs
        if (!prefs.serverConfigured) return@withContext null
        val body = JSONObject().put("text", text).put("voice", voice).put("lang", lang).put("speed", speed.toDouble())
        val request = try {
            Request.Builder().url(prefs.serverUrl + "/api/tts").header("Authorization", "Bearer ${prefs.token}")
                .post(body.toString().toRequestBody(json)).build()
        } catch (e: IllegalArgumentException) {
            return@withContext null
        }
        try {
            ttsClient.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        } catch (e: IOException) {
            null
        }
    }

    private val ttsClient by lazy {
        client.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
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
            queueLiveSec = o.optJSONObject("queue")?.optInt("live_sec") ?: 0,
            queueOfflineSec = o.optJSONObject("queue")?.optInt("offline_sec") ?: 0,
            aiCostMonth = if (o.has("ai_cost_30d") && !o.isNull("ai_cost_30d")) o.optDouble("ai_cost_30d") else null,
        )
    }

    suspend fun conversations(limit: Int = 100, day: String? = null): List<ConversationItem> =
        JSONObject(call("GET", "/api/conversations?limit=$limit" + (day?.let { "&day=$it" } ?: "")))
            .getJSONArray("items").map { conversation(it) }

    /** События дня. Старый сервер без событий — разговоры дня как отдельные фрагменты. */
    suspend fun events(day: String): DayEvents = try {
        val o = JSONObject(call("GET", "/api/events?day=$day"))
        DayEvents(
            day = day,
            events = o.optJSONArray("events").map { e ->
                EventItem(
                    id = e.getLong("id"), day = e.optString("day").take(10).ifBlank { day },
                    startedAt = e.optString("started_at"), endedAt = e.optString("ended_at"),
                    title = e.str("title"), emoji = e.str("emoji"), kind = e.optString("kind", "другое"),
                    summary = e.str("summary"), topics = e.optJSONArray("topics").strings(),
                    photoId = e.longOrNull("photo_id"), people = e.optJSONArray("people").map { personRef(it) },
                    fragments = e.optJSONArray("fragments").map { conversation(it) },
                    final = e.optString("status") == "final",
                    lat = e.dbl("lat"), lon = e.dbl("lon"),
                )
            }.filter { it.fragments.isNotEmpty() },
            loose = o.optJSONArray("loose").map { conversation(it) },
        )
    } catch (e: ApiException) {
        if (e.kind != ApiException.Kind.NOT_FOUND) throw e
        DayEvents(day, emptyList(), conversations(200, day), grouped = false)
    }

    suspend fun trash(): List<TrashItem> =
        JSONObject(call("GET", "/api/trash")).optJSONArray("items").map {
            TrashItem(it.getLong("id"), it.optString("started_at"), it.optString("ended_at"), it.str("title"),
                it.str("emoji"), it.str("junk_reason"), it.str("preview"))
        }

    suspend fun restore(conversationId: Long) {
        call("POST", "/api/conversations/$conversationId/restore", JSONObject())
    }

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
                    it.longOrNull("person_id"), it.optBoolean("sure", true), it.optBoolean("has_audio"),
                    it.str("raw_text"))
            },
            tasks = o.optJSONArray("tasks").map { task(it) },
            memories = o.optJSONArray("memories").map { memory(it) },
            people = o.optJSONArray("people").map { personRef(it) }.associateBy { it.id },
            location = o.optJSONObject("location")?.let { l ->
                ConvLocation(l.optDouble("lat"), l.optDouble("lon"), l.str("name"), latLons(l.optJSONArray("track")))
            },
        )
    }

    /** Карта дня. Старый сервер без карты — null. */
    suspend fun dayMap(day: String): DayMapData? = try {
        val o = JSONObject(call("GET", "/api/map?day=$day"))
        fun spot(it: JSONObject, event: Boolean) = MapSpot(
            it.getLong("id"), event, it.str("title"), it.str("emoji"), it.optString("started_at"),
            it.optDouble("lat"), it.optDouble("lon"), if (event) it.optInt("fragments", 1) else 1,
            if (event) it.longOrNull("first_conversation") else it.getLong("id"))
        DayMapData(
            day = day,
            track = latLons(o.optJSONArray("track")),
            places = o.optJSONArray("places").map {
                MapPlace(it.str("name"), it.optDouble("lat"), it.optDouble("lon"), it.optString("from"), it.optString("to"))
            },
            spots = (o.optJSONArray("events").map { spot(it, true) } + o.optJSONArray("conversations").map { spot(it, false) })
                .sortedBy { it.startedAt },
        )
    } catch (e: ApiException) {
        if (e.kind == ApiException.Kind.NOT_FOUND) null else throw e
    }

    /** Плитка подложки карты (через свой сервер). style: light | dark. */
    /** Подпись поставщиков карты (обязательна): сервер говорит, откуда плитки. */
    @Volatile var mapAttribution: String = "© OpenStreetMap"

    fun tileUrl(style: String, z: Int, x: Int, y: Int): String = Core.prefs.serverUrl + "/api/tiles/$style/$z/$x/$y.png"

    private fun latLons(a: JSONArray?): List<LatLon> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { LatLon(it.optDouble(0), it.optDouble(1)) } }
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

    /** Другой срок обещания: время (мс) или null — без срока. */
    suspend fun setTaskDue(id: Long, dueAtMs: Long?) {
        val iso = dueAtMs?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString() }
        call("POST", "/api/tasks/$id", JSONObject().put("due_at", iso ?: JSONObject.NULL))
    }

    /** Исправить обещание: что, кто кому, с кем (человек из «Людей» или имя), срок — одним запросом. */
    suspend fun updatePromise(id: Long, edit: codes.neuro.neurosoft.ui.PromiseEdit) {
        val body = JSONObject()
        edit.text?.let { body.put("text", it) }
        edit.kind?.let { body.put("kind", it) }
        if (edit.dueAt != null || edit.clearDue) {
            body.put("due_at", edit.dueAt?.let {
                java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString()
            } ?: JSONObject.NULL)
        }
        when {
            edit.personId != null -> body.put("counterpart_id", edit.personId)
            edit.personName != null -> body.put("counterpart", edit.personName).put("counterpart_id", JSONObject.NULL)
            edit.clearPerson -> body.put("counterpart", JSONObject.NULL).put("counterpart_id", JSONObject.NULL)
        }
        edit.nameVoice?.let { (pid, name) -> updatePerson(pid, name = name) }
        if (body.length() > 0) call("POST", "/api/tasks/$id", body)
    }

    /** Встреча в календаре для обещания (null — убрали из календаря). */
    suspend fun setTaskCalendar(id: Long, eventId: Long?, minutes: Int? = null) {
        val body = JSONObject().put("calendar_event_id", eventId ?: JSONObject.NULL)
        minutes?.let { body.put("calendar_minutes", it) }
        call("POST", "/api/tasks/$id", body)
    }

    /** Новое обещание вручную. Возвращает его id. */
    suspend fun createPromise(edit: codes.neuro.neurosoft.ui.PromiseEdit): Long {
        edit.nameVoice?.let { (pid, name) -> updatePerson(pid, name = name) }
        val body = JSONObject().put("text", edit.text ?: "").put("kind", edit.kind ?: "mine")
        edit.dueAt?.let {
            body.put("due_at", java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString())
        }
        edit.personId?.let { body.put("counterpart_id", it) }
        edit.personName?.let { body.put("counterpart", it) }
        return JSONObject(call("POST", "/api/tasks", body, explain = true)).optLong("id")
    }

    /** Черновик напоминания тому, кто обещал. */
    suspend fun nudge(taskId: Long): String =
        JSONObject(call("POST", "/api/tasks/$taskId/nudge", JSONObject(), slow = true)).optString("text")

    /** Письмо по итогам встречи: тема и текст. */
    suspend fun followup(conversationId: Long): Pair<String, String> {
        val o = JSONObject(call("POST", "/api/conversations/$conversationId/followup", JSONObject(), slow = true))
        return o.optString("subject") to o.optString("body")
    }

    /** query — поиск по смыслу («что любит мама»), самое близкое первым. */
    suspend fun memories(archived: Boolean = false, query: String? = null): MemoryList {
        val q = when {
            !query.isNullOrBlank() -> "?q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")
            archived -> "?archived=1"
            else -> ""
        }
        val o = JSONObject(call("GET", "/api/memories$q"))
        return MemoryList(o.getJSONArray("items").map { memory(it) }, o.optInt("active"), o.optInt("archived"))
    }

    /** Исправить запись памяти. */
    suspend fun editMemory(id: Long, text: String) {
        call("POST", "/api/memories/$id", JSONObject().put("text", text))
    }

    /** В архив / вернуть из архива. */
    suspend fun archiveMemory(id: Long, archived: Boolean) {
        call("POST", "/api/memories/$id", JSONObject().put("archived", archived))
    }

    suspend fun deleteMemory(id: Long) {
        call("DELETE", "/api/memories/$id")
    }

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
                agent = r.optString("agent").ifBlank { if (r.optString("level").startsWith("claude")) "claude" else "mnemo" },
                context = r.str("context"),
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

    suspend fun sendChat(text: String, level: String, agent: String = "mnemo", context: String? = null) {
        val body = JSONObject().put("text", text).put("level", level).put("agent", agent)
        if (context != null) body.put("context", context)
        call("POST", "/api/chat", body)
    }

    /** Всё о событии, разговоре или дне простым текстом — для «Скопировать». ref: event:ID | conversation:ID | day:… */
    suspend fun share(ref: String): Pair<String, String> {
        val o = JSONObject(call("GET", "/api/share?ref=" + java.net.URLEncoder.encode(ref, "UTF-8")))
        return o.optString("title") to o.optString("text")
    }

    /** Остановить запуск агента («Стоп» в чате). */
    suspend fun cancelRun(id: Long) {
        call("POST", "/api/runs/$id/cancel", JSONObject())
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
                it.str("hint"), it.optBoolean("filled"), it.str("value"), it.optString("type", "text"))
        },
        taskSync = o.str("task_sync"), enabled = o.optBoolean("enabled", false),
        ready = o.optBoolean("ready", false), installed = installed, verified = o.optBoolean("verified", false),
        events = o.optJSONArray("events").map { ExtEvent(it.optString("event"), it.str("title"), it.optBoolean("to_user_url")) },
        delivery = o.optJSONObject("delivery")?.let {
            ExtDelivery(it.optInt("sent"), it.optInt("waiting"), it.optInt("failed"), it.str("last_sent"), it.str("error"))
        },
        sourceUrl = o.str("source_url"),
    )

    /** «Создать с ассистентом»: описание словами (и правка прошлого черновика) -> черновик манифеста. */
    suspend fun draftExtension(description: String, previous: JSONObject? = null, feedback: String? = null): ExtDraft {
        val body = JSONObject().put("description", description)
        if (previous != null) body.put("previous", previous)
        if (!feedback.isNullOrBlank()) body.put("feedback", feedback)
        val o = JSONObject(call("POST", "/api/extensions/draft", body))
        return ExtDraft(o.optJSONObject("manifest"), o.optJSONObject("preview")?.let { extension(it, false) }, o.str("notes"))
    }

    suspend fun installManifest(manifest: JSONObject) {
        call("POST", "/api/extensions/install", JSONObject().put("manifest", manifest))
    }

    // ---------------------------------------------------------------- календарь телефона
    suspend fun calendarStatus(): CalendarStatus = calendarStatus(JSONObject(call("GET", "/api/calendar")))

    /** read / write — что можно ассистенту; target — календарь для новых встреч. */
    suspend fun calendarSettings(read: Boolean? = null, write: Boolean? = null, target: Long? = null): CalendarStatus {
        val body = JSONObject()
        read?.let { body.put("read", it) }
        write?.let { body.put("write", it) }
        target?.let { body.put("target", it) }
        return calendarStatus(JSONObject(call("POST", "/api/calendar", body)))
    }

    suspend fun calendarDisconnect() {
        call("DELETE", "/api/calendar")
    }

    suspend fun calendarSync(body: JSONObject) {
        call("POST", "/api/calendar/sync", body)
    }

    suspend fun calendarCommands(): List<JSONObject> =
        JSONObject(call("GET", "/api/calendar/commands")).optJSONArray("items").map { it }

    suspend fun calendarCommandDone(id: Long, ok: Boolean, result: String) {
        call("POST", "/api/calendar/commands/$id", JSONObject().put("ok", ok).put("result", result))
    }

    private fun calendarStatus(o: JSONObject) = CalendarStatus(
        o.optBoolean("connected"), o.str("synced_at"), o.optInt("events"), o.optBoolean("read", true),
        o.optBoolean("write", true), o.longOrNull("target"),
        o.optJSONArray("calendars").map { CalendarInfo(it.getLong("id"), it.optString("name"), it.optString("account"), it.optBoolean("primary")) },
    )

    // ---------------------------------------------------------------- правила и расписание агента
    suspend fun routines(): Routines {
        val o = JSONObject(call("GET", "/api/agent/routines"))
        return Routines(
            o.optJSONArray("rules").map { AgentRule(it.getLong("id"), it.optString("text"), it.optString("created_at")) },
            o.optJSONArray("schedules").map {
                AgentSchedule(it.getLong("id"), it.optString("title"), it.optString("prompt"), it.optString("when"),
                    it.str("next_run_at"), it.optBoolean("enabled"), it.str("last_run_at"), it.str("last_status"))
            },
        )
    }

    suspend fun deleteRule(id: Long) {
        call("DELETE", "/api/agent/rules/$id")
    }

    suspend fun setScheduleEnabled(id: Long, on: Boolean) {
        call("POST", "/api/agent/schedules/$id", JSONObject().put("enabled", on))
    }

    suspend fun deleteSchedule(id: Long) {
        call("DELETE", "/api/agent/schedules/$id")
    }

    suspend fun routineResults(after: Long): List<RoutineResult> =
        JSONObject(call("GET", "/api/agent/results?after=$after")).optJSONArray("items").map {
            RoutineResult(it.getLong("run_id"), it.optString("question"), it.optString("answer"))
        }

    // ---------------------------------------------------------------- важные разговоры
    suspend fun important(): List<ConversationItem> =
        JSONObject(call("GET", "/api/important")).optJSONArray("items").map { conversation(it) }

    suspend fun setImportant(conversationId: Long, on: Boolean) {
        call("POST", "/api/conversations/$conversationId/important", JSONObject().put("on", on))
    }

    // ---------------------------------------------------------------- знакомство
    suspend fun profile(): Profile = profile(JSONObject(call("GET", "/api/profile")))

    suspend fun saveProfile(name: String, about: String, goals: List<String>): Profile =
        profile(JSONObject(call("POST", "/api/profile", JSONObject().put("name", name).put("about", about)
            .put("goals", JSONArray(goals)))))

    /** Прочитал фразы вслух с from до to (мс): голос в этом окне — голос пользователя. */
    suspend fun profileVoice(from: Long, to: Long) {
        call("POST", "/api/profile/voice", JSONObject().put("from", from).put("to", to), explain = true)
    }

    private fun profile(o: JSONObject) = Profile(o.str("name"), o.str("about"), o.optJSONArray("goals").strings(),
        o.optString("voice", "none"))

    // ---------------------------------------------------------------- сервисы одной кнопкой
    private fun connector(o: JSONObject) = Connector(
        o.optString("id"), o.optString("name"), o.optString("icon"), o.optString("description"),
        o.optString("status", "off"), o.str("account"), o.str("error"), o.optJSONArray("tools").strings(),
        o.optBoolean("read", true), o.optBoolean("write", true),
    )

    suspend fun connectors(): List<Connector> =
        JSONObject(call("GET", "/api/connectors")).optJSONArray("items").map { connector(it) }

    /** Ссылка на вход в сервис. Адрес сервера — запасной адрес возврата, если сервис не умеет в приложение. */
    suspend fun connectorStart(id: String): String = JSONObject(call("POST", "/api/connectors/$id/start",
        JSONObject().put("server_url", Core.prefs.serverUrl), explain = true)).getString("url")

    /** Код из сервиса (вернулся в приложение) -> сервер меняет его на доступ. */
    suspend fun connectorFinish(code: String, state: String): Connector = connector(JSONObject(call("POST",
        "/api/connectors/finish", JSONObject().put("code", code).put("state", state), explain = true)))

    /** key: read | write — что ассистенту можно в сервисе. */
    suspend fun setConnector(id: String, key: String, on: Boolean) {
        call("POST", "/api/connectors/$id", JSONObject().put(key, on))
    }

    suspend fun disconnectConnector(id: String) {
        call("DELETE", "/api/connectors/$id")
    }

    // ---------------------------------------------------------------- Mnemo в других программах (MCP)
    private fun mcp(o: JSONObject) = McpInfo(o.optBoolean("enabled"), o.str("since"), o.str("token"),
        Core.prefs.serverUrl.trimEnd('/') + o.optString("path", "/mcp"))

    suspend fun mcpInfo(): McpInfo = mcp(JSONObject(call("GET", "/api/mcp")))
    suspend fun mcpNewKey(): McpInfo = mcp(JSONObject(call("POST", "/api/mcp", JSONObject())))
    suspend fun mcpOff(): McpInfo = mcp(JSONObject(call("DELETE", "/api/mcp")))

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
        transcriptCleanup = o.optBoolean("transcript_cleanup", true),
    ).also { mapAttribution = o.optString("map_attribution", mapAttribution).ifBlank { mapAttribution } }

    suspend fun diary(): DiaryInfo {
        val o = JSONObject(call("GET", "/api/diary?limit=21"))
        val photos = o.optJSONObject("photos") ?: JSONObject()
        return DiaryInfo(
            // пустой день (ещё ничего не было) не показываем
            days = o.optJSONArray("days").filterObjects { !it.isNull("title") || it.optString("status") == "failed" }.map { d ->
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
            events = o.optJSONArray("events").map { e ->
                val ids = e.optJSONArray("people")
                GraphEvent(
                    id = e.getLong("id"), title = e.str("title"), emoji = e.str("emoji"),
                    kind = e.optString("kind", "другое"), startedAt = e.optString("started_at"),
                    durationSec = e.optInt("duration_sec"),
                    people = if (ids == null) emptyList() else (0 until ids.length()).map { ids.optLong(it) },
                    topics = e.optJSONArray("topics").strings(), photoId = e.longOrNull("photo_id"),
                    firstConversation = e.longOrNull("first_conversation"), fragments = e.optInt("fragments", 1),
                    day = e.str("day")?.take(10),
                    vivid = e.optDouble("vivid", 0.3).toFloat(), summary = e.str("summary"),
                )
            },
        )
    }

    private fun personRef(o: JSONObject) = PersonRef(
        id = o.getLong("id"), name = o.str("name"), number = o.optInt("number"),
        isOwner = o.optBoolean("is_owner"), ignored = o.optBoolean("ignored"), transient = o.optBoolean("transient"),
        kind = o.str("kind"),
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
        targetId = o.longOrNull("target_id"), targetOwner = o.optBoolean("target_owner"),
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
        important = o.optBoolean("important"),
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
        kind = o.optString("kind", "mine").ifBlank { "mine" },
        dueAt = o.str("due_at"),
        evidence = o.str("evidence"),
        counterpart = o.str("counterpart"),
        counterpartId = o.longOrNull("counterpart_id"),
        calendarEventId = o.longOrNull("calendar_event_id"),
        calendarMinutes = if (o.isNull("calendar_minutes")) null else o.optInt("calendar_minutes"),
        inCalendar = o.optBoolean("in_calendar"),
        counterpartNumber = if (o.isNull("counterpart_number")) null else o.optInt("counterpart_number"),
        confirmedAt = o.str("confirmed_at"),
        doneAt = o.str("done_at"),
    )

    private fun memory(o: JSONObject) = MemoryItem(
        id = o.getLong("id"),
        text = o.optString("text"),
        source = o.optString("source"),
        createdAt = o.optString("created_at"),
        conversationTitle = o.str("conversation_title"),
        updatedAt = o.str("updated_at"),
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

private fun JSONArray?.filterObjects(keep: (JSONObject) -> Boolean): JSONArray {
    val out = JSONArray()
    if (this == null) return out
    for (i in 0 until length()) optJSONObject(i)?.takeIf(keep)?.let { out.put(it) }
    return out
}
