package codes.neuro.neurosoft.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.ChatState
import codes.neuro.neurosoft.net.ConversationItem
import codes.neuro.neurosoft.net.DayInfo
import codes.neuro.neurosoft.net.DiaryInfo
import codes.neuro.neurosoft.net.Features
import codes.neuro.neurosoft.net.ModelTask
import codes.neuro.neurosoft.net.TaskItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** Данные + загрузка + последняя ошибка. Старые данные не пропадают, если сервер недоступен. */
data class Res<T>(val data: T? = null, val loading: Boolean = false, val error: Throwable? = null)

/** Кэш одного запроса: экраны показывают его мгновенно, а свежие данные подтягиваются в фоне. */
class Cached<T>(private val loader: suspend () -> T) {
    private val _state = MutableStateFlow(Res<T>())
    val state: StateFlow<Res<T>> = _state
    private val mutex = Mutex()

    suspend fun refresh() {
        if (!mutex.tryLock()) return
        try {
            _state.update { it.copy(loading = true) }
            val data = loader()
            _state.value = Res(data)
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e) }
        } finally {
            mutex.unlock()
        }
    }

    /** Оптимистичное изменение (галочка задачи и т. п.). */
    fun edit(f: (T) -> T) {
        _state.update { s -> s.data?.let { s.copy(data = f(it)) } ?: s }
    }
}

object Repo {
    val days = Cached { Api.days() }
    val tasks = Cached { Api.tasks("all") }
    val diary = Cached { Api.diary() }
    val features = Cached { Api.features() }
    val chat = Cached<ChatState> { Api.chat() }
    private val byDay = HashMap<String, Cached<List<ConversationItem>>>()

    @Synchronized
    fun conversations(day: String): Cached<List<ConversationItem>> =
        byDay.getOrPut(day) { Cached { Api.conversations(200, day) } }

    suspend fun refreshAll(day: String) {
        days.refresh()
        conversations(day).refresh()
    }
}

/** Экраны приложения. Стек живёт дольше активити: смена языка или поворот не сбрасывают место. */
sealed interface Route {
    data object Onboarding : Route
    data object Home : Route
    data class Conversation(val id: Long) : Route
    data class Chat(val prefill: String? = null) : Route
    data object Search : Route
    data class Day(val day: String) : Route
    data object Settings : Route
    data object Device : Route
    data object Voice : Route
    data object DiarySettings : Route
    data object Language : Route
    data object SttLanguage : Route
    data object Models : Route
    data class ModelPicker(val task: ModelTask) : Route
    data object Extensions : Route
    data class Extension(val id: String) : Route
    data object Memories : Route
}

object Nav {
    val stack = mutableStateListOf<Route>(Route.Home)

    /** Последний шаг: true — вперёд, false — назад (для направления анимации). */
    var forward = true
        private set

    fun push(r: Route) {
        forward = true
        stack.add(r)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        forward = false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun reset(r: Route) {
        forward = true
        stack.clear()
        stack.add(r)
    }
}

/** Состояние главного экрана, которое не должно сбрасываться при переходах. */
object HomeState {
    val page = mutableStateOf(0)
    val day = mutableStateOf(todayIso())
}
