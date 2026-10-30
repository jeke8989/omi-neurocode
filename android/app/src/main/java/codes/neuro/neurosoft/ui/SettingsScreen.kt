package codes.neuro.neurosoft.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.BuildConfig
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.Permissions
import codes.neuro.neurosoft.ble.ConnState
import codes.neuro.neurosoft.ble.OmiProtocol
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.Extension
import codes.neuro.neurosoft.net.Features
import codes.neuro.neurosoft.net.ModelOption
import codes.neuro.neurosoft.net.ModelTask
import codes.neuro.neurosoft.net.ModelsInfo
import kotlinx.coroutines.launch
import java.util.Locale

private sealed interface Page {
    data object Root : Page
    data object Device : Page
    data object Models : Page
    data class Picker(val task: ModelTask) : Page
    data object Extensions : Page
    data class ExtensionDetail(val id: String) : Page
    data object Conversations : Page
    data object Memories : Page
}

@Composable
fun SettingsScreen(onOpenConversation: (Long) -> Unit) {
    var stack by remember { mutableStateOf(listOf<Page>(Page.Root)) }
    val page = stack.last()
    fun push(p: Page) {
        stack = stack + p
    }
    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    when (page) {
        Page.Root -> SettingsRoot(::push)
        Page.Device -> DevicePage(::pop)
        Page.Models -> ModelsPage(::pop, onPick = { push(Page.Picker(it)) })
        is Page.Picker -> ModelPickerPage(page.task, ::pop)
        Page.Extensions -> ExtensionsPage(::pop, onOpen = { push(Page.ExtensionDetail(it)) })
        is Page.ExtensionDetail -> ExtensionDetailPage(page.id, ::pop)
        Page.Conversations -> ConversationsList(onOpen = onOpenConversation, onBack = ::pop)
        Page.Memories -> MemoriesScreen(::pop)
    }
}

// ======================================================================= корень
@Composable
private fun SettingsRoot(push: (Page) -> Unit) {
    val context = LocalContext.current
    val device by Core.device.collectAsState()
    val upload by Core.upload.collectAsState()
    val voice by Core.voice.state.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(Core.prefs.serverUrl) }
    var token by remember { mutableStateOf(Core.prefs.token) }
    var check by remember { mutableStateOf<String?>(null) }
    var features by remember { mutableStateOf<Features?>(null) }
    var location by remember { mutableStateOf(Core.prefs.locationEnabled) }
    var photoAuto by remember { mutableStateOf(Core.prefs.photoAutoImport) }
    var earcons by remember { mutableStateOf(Core.prefs.earcons) }
    var confirmRemember by remember { mutableStateOf(Core.prefs.confirmRemember) }
    var autoStart by remember { mutableStateOf(Core.prefs.autoStart) }
    var ignoringBattery by remember { mutableStateOf(isIgnoringBattery(context)) }

    LaunchedEffect(Unit) {
        features = try {
            Api.features()
        } catch (e: Exception) {
            null
        }
    }
    fun setFeature(changes: Map<String, Any>) {
        scope.launch {
            features = try {
                Api.setFeatures(changes)
            } catch (e: Exception) {
                check = e.message
                features
            }
        }
    }
    val locationPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val ok = Core.locationPermissionGranted()
        location = ok
        Core.prefs.locationEnabled = ok
        Core.applyLocationSetting()
    }
    val photoPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val ok = Permissions.granted(context, Permissions.photos())
        photoAuto = ok
        Core.prefs.photoAutoImport = ok
        if (ok) Core.syncPhotosNow()
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        ignoringBattery = isIgnoringBattery(context)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        LargeTitle("Настройки")

        Section("Кулон") {
            row {
                ListRow(
                    device.name ?: "Кулон не выбран",
                    subtitle = if (device.address == null) "Найти и подключить Omi"
                    else "${Core.stateText(device.state)} · ${device.address}" +
                        (device.firmware?.let { " · прошивка $it" } ?: ""),
                    icon = NeuroIcons.Waveform,
                    iconColor = if (device.state == ConnState.CONNECTED) Ios.colors.green else Ios.colors.gray,
                    chevron = true, onClick = { push(Page.Device) },
                )
            }
        }

        Section("Сервер", footer = "Адрес и токен выдаёт deploy.sh на сервере. " +
            if (upload.pendingFiles > 1) "Не отправлено: ${bytesText(upload.pendingBytes)}." else "") {
            row { FieldRow("Адрес", url, { url = it }, "http://IP:8090", keyboard = KeyboardType.Uri) }
            row { FieldRow("Токен", token, { token = it }, "из deploy.sh", secret = true) }
            row {
                ListRow(
                    "Сохранить и проверить",
                    subtitle = check ?: when {
                        upload.connected -> "Подключено"
                        upload.error != null && upload.configured -> upload.error
                        else -> null
                    },
                    titleColor = Ios.colors.tint,
                    onClick = {
                        Core.prefs.serverUrl = url
                        Core.prefs.token = token
                        url = Core.prefs.serverUrl
                        Core.uploader.reconnectNow()
                        check = "Проверяю…"
                        scope.launch {
                            check = try {
                                val s = Api.status()
                                features = Api.features()
                                "✓ Работает · разговоров ${s.conversations} · задач ${s.openTasks}" +
                                    if (!s.llmConfigured) " · нет ключа OpenRouter" else ""
                            } catch (e: Exception) {
                                "✗ ${e.message}"
                            }
                        }
                    },
                )
            }
        }

        Section("Ассистент", footer = "Одно нажатие на кулон — вопрос, ответ голосом в наушник. " +
            "Двойное — «запомни это».") {
            row { ListRow("Модели ИИ", subtitle = "Под каждую задачу — своя модель и цена", icon = NeuroIcons.Sparkles,
                iconColor = Ios.colors.indigo, chevron = true, onClick = { push(Page.Models) }) }
            row { ListRow("Расширения", subtitle = "Маркетплейс: поиск, сервисы, интеграции задач",
                icon = NeuroIcons.Puzzle, iconColor = Ios.colors.orange, chevron = true,
                onClick = { push(Page.Extensions) }) }
            row {
                ListRow("Проверить голос", subtitle = if (voice.ttsReady) "Синтез речи Android, русский"
                else "Синтезатор речи не готов — установите голос в настройках Android",
                    icon = NeuroIcons.Speaker, iconColor = Ios.colors.pink, onClick = { Core.voice.testVoice() })
            }
            row {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Скорость голоса", style = MaterialTheme.typography.bodyLarge, color = Ios.colors.label)
                    val rates = listOf(0.9f, 1.05f, 1.2f, 1.4f)
                    var sel by remember { mutableStateOf(rates.indexOfFirst { it == Core.prefs.voiceRate }.let { if (it < 0) 1 else it }) }
                    Spacer(Modifier.height(8.dp))
                    SegmentedControl(listOf("Медленно", "Обычно", "Быстро", "Очень"), sel, {
                        sel = it
                        Core.voice.setRate(rates[it])
                    }, Modifier.fillMaxWidth())
                }
            }
            row { ToggleRow("Звуковые сигналы", earcons, {
                earcons = it
                Core.prefs.earcons = it
            }, subtitle = "«Слушаю» и «принял» в наушнике") }
            row { ToggleRow("Говорить «Запомнил»", confirmRemember, {
                confirmRemember = it
                Core.prefs.confirmRemember = it
            }) }
        }

        val f = features
        Section("Дневник жизни", footer = "Фото не попадают в ленту все подряд: агент отбирает только связанные с " +
            "событиями дня (разговоры, места), а скриншоты и документы пропускает.") {
            row { ToggleRow("Лента дня", f?.diary ?: true, { setFeature(mapOf("diary" to it)) },
                subtitle = "История дня, хайлайты, настроение", icon = NeuroIcons.Book, iconColor = Ios.colors.purple,
                enabled = f != null) }
            row {
                ToggleRow("Геолокация во время записи", location, { on ->
                    if (on && !Core.locationPermissionGranted()) {
                        locationPerm.launch(Permissions.location())
                    } else {
                        location = on
                        Core.prefs.locationEnabled = on
                        Core.applyLocationSetting()
                    }
                }, subtitle = "Места дня на карте дневника", icon = NeuroIcons.Location, iconColor = Ios.colors.red)
            }
            row {
                ToggleRow("Фото дня с телефона", photoAuto, { on ->
                    if (on && !Permissions.granted(context, Permissions.photos())) {
                        photoPerm.launch(Permissions.photos())
                    } else {
                        photoAuto = on
                        Core.prefs.photoAutoImport = on
                        if (on) Core.syncPhotosNow()
                    }
                }, subtitle = "Снимки камеры за сегодня — кандидаты для ленты", icon = NeuroIcons.Photo,
                    iconColor = Ios.colors.teal)
            }
            row { ToggleRow("ИИ-обработка фото", f?.photoStyle ?: true, { setFeature(mapOf("photo_style" to it)) },
                subtitle = "≈ \$0.08 за кадр, только выбранные фото", icon = NeuroIcons.Sparkles,
                iconColor = Ios.colors.indigo, enabled = f != null) }
            if (f != null && f.photoStyle) {
                row {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("Стиль", style = MaterialTheme.typography.bodyLarge, color = Ios.colors.label)
                        Spacer(Modifier.height(8.dp))
                        val styles = f.styles.take(4)
                        SegmentedControl(styles.map { it.second }, styles.indexOfFirst { it.first == f.style }
                            .coerceAtLeast(0), { setFeature(mapOf("style" to styles[it].first)) }, Modifier.fillMaxWidth())
                        val more = f.styles.drop(4)
                        if (more.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            SegmentedControl(more.map { it.second }, more.indexOfFirst { it.first == f.style },
                                { setFeature(mapOf("style" to more[it].first)) }, Modifier.fillMaxWidth())
                        }
                    }
                }
                row {
                    ListRow("Фото в день", value = "${f.photosPerDay}", onClick = {
                        setFeature(mapOf("photos_per_day" to (f.photosPerDay % 6) + 1))
                    })
                }
            }
        }

        Section("Работа в фоне") {
            row {
                ListRow(
                    if (ignoringBattery) "Ограничения батареи сняты" else "Разрешить работу в фоне",
                    subtitle = if (ignoringBattery) null else "Иначе Android может остановить запись",
                    icon = NeuroIcons.Battery, iconColor = if (ignoringBattery) Ios.colors.green else Ios.colors.orange,
                    chevron = !ignoringBattery,
                    onClick = if (ignoringBattery) null else ({
                        try {
                            batteryLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")))
                        } catch (_: Exception) {
                            batteryLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }),
                )
            }
            row { ToggleRow("Включать запись после перезагрузки", autoStart, {
                autoStart = it
                Core.prefs.autoStart = it
            }) }
        }

        Section("Данные") {
            row { ListRow("Все разговоры", icon = NeuroIcons.Bubble, iconColor = Ios.colors.tint, chevron = true,
                onClick = { push(Page.Conversations) }) }
            row { ListRow("Память", icon = NeuroIcons.Brain, iconColor = Ios.colors.pink, chevron = true,
                onClick = { push(Page.Memories) }) }
            row { ListRow("Очистить чат с агентом", titleColor = Ios.colors.red, onClick = {
                scope.launch { check = try { Api.clearChat(); "Чат очищен" } catch (e: Exception) { e.message } }
            }) }
        }

        Text("NeuroSoft ${BuildConfig.VERSION_NAME} · Neuro Codes", style = MaterialTheme.typography.bodySmall,
            color = Ios.colors.tertiary, modifier = Modifier.fillMaxWidth().padding(24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private fun isIgnoringBattery(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

// ======================================================================= кулон
@Composable
private fun DevicePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val device by Core.device.collectAsState()
    val scanning by Core.scanning.collectAsState()
    val found by Core.found.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Permissions.bluetoothGranted(context)) message = if (Core.startScan()) null else "Включите Bluetooth"
    }
    fun scan() {
        if (!Permissions.bluetoothGranted(context)) perms.launch(Permissions.required())
        else message = if (Core.startScan()) null else "Включите Bluetooth"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        NavBar("Кулон", onBack, backLabel = "Настройки")
        if (device.address != null) {
            Section("Подключённый кулон") {
                row { ListRow("Устройство", value = device.name ?: "Omi") }
                row { ListRow("Состояние", value = Core.stateText(device.state)) }
                row { ListRow("Батарея", value = device.battery?.let { "$it%" } ?: "—") }
                row { ListRow("Прошивка", value = device.firmware ?: "—") }
                row { ListRow("Кодек", value = OmiProtocol.codecName(device.codec)) }
                row { ListRow("Кадров принято", value = "${device.frames}" +
                    if (device.lostPackets > 0) " (потерь ${device.lostPackets})" else "") }
                device.lastButton?.let { b -> row { ListRow("Кнопка", subtitle = "$b, ${agoText(device.lastButtonAt)}") } }
            }
        }
        Section("Кулоны рядом", footer = "Кулон должен быть включён и отключён от приложения Omi " +
            "(одновременно он работает только с одним приложением).") {
            row {
                Column {
                    ListRow(if (scanning) "Ищу…" else "Найти кулон", titleColor = Ios.colors.tint,
                        onClick = if (scanning) null else ({ scan() }))
                    if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ios.colors.tint,
                        trackColor = Ios.colors.fill)
                }
            }
            found.forEach { d ->
                row {
                    ListRow(d.name, subtitle = "${d.address} · сигнал ${d.rssi} дБм", icon = NeuroIcons.Waveform,
                        iconColor = Ios.colors.tint) {
                        PillButton(if (d.address == device.address) "Текущий" else "Выбрать",
                            { Core.selectDevice(context, d) }, small = true, enabled = d.address != device.address)
                    }
                }
            }
            message?.let { m -> row { ListRow(m, titleColor = Ios.colors.red) } }
        }
        if (device.address != null) {
            Section {
                row { ListRow("Забыть кулон", titleColor = Ios.colors.red, onClick = { Core.forgetDevice(context) }) }
            }
        }
    }
}

// ======================================================================= модели
@Composable
private fun ModelsPage(onBack: () -> Unit, onPick: (ModelTask) -> Unit) {
    var state by remember { mutableStateOf<Load<ModelsInfo>>(Load.Loading) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            state = try {
                Load.Ok(Api.models())
            } catch (e: Exception) {
                Load.Err(e.message ?: "Ошибка")
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        NavBar("Модели ИИ", onBack, backLabel = "Настройки")
        LoadContent(state, onRetry = { reload() }) { info ->
            if (!info.configured) {
                Section(footer = "Добавьте OPENROUTER_API_KEY в .env на сервере и перезапустите: docker compose up -d") {
                    row { ListRow("Нет ключа модели на сервере", titleColor = Ios.colors.red) }
                }
            }
            Section(footer = "Расход за 30 дней: ${priceText(info.spendTotal)}" +
                if (info.livePrices) " · цены OpenRouter обновляются автоматически" else "") {}
            val groups = listOf(
                "Агент" to listOf("ask", "agent", "agent_deep", "search"),
                "Речь" to listOf("ask_stt", "transcribe"),
                "Итоги и дневник" to listOf("summary", "remember", "diary", "vision", "image_style"),
            )
            groups.forEach { (title, keys) ->
                Section(title) {
                    info.tasks.filter { it.key in keys }.forEach { t ->
                        val opt = info.catalog.firstOrNull { it.id == t.model }
                        row {
                            ListRow(
                                t.title,
                                subtitle = (opt?.name ?: t.model) + " · " + priceLine(opt) +
                                    (info.spend[t.key]?.let { " · 30 дн: ${priceText(it)}" } ?: ""),
                                chevron = true, onClick = { onPick(t) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun priceLine(o: ModelOption?): String = when {
    o == null -> "цена — в каталоге"
    o.priceHour != null -> "${priceText(o.priceHour)}/час звука"
    else -> "${priceText(o.priceIn)} / ${priceText(o.priceOut)} за 1M"
}

@Composable
private fun ModelPickerPage(task: ModelTask, onBack: () -> Unit) {
    var state by remember { mutableStateOf<Load<ModelsInfo>>(Load.Loading) }
    var query by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(task.model) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        state = try {
            Load.Ok(Api.models(all = task.kind == "llm"))
        } catch (e: Exception) {
            Load.Err(e.message ?: "Ошибка")
        }
    }
    fun choose(id: String?) {
        scope.launch {
            try {
                Api.setModel(task.key, id)
                current = id ?: task.default
                error = null
            } catch (e: Exception) {
                error = e.message
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        NavBar(task.title, onBack, backLabel = "Модели")
        Text(task.description, style = MaterialTheme.typography.bodyMedium, color = Ios.colors.secondary,
            modifier = Modifier.padding(horizontal = 20.dp))
        error?.let { Text(it, color = Ios.colors.red, modifier = Modifier.padding(horizontal = 20.dp)) }
        LoadContent(state, onRetry = {}) { info ->
            val recommended = info.catalog.filter { it.kind == task.kind }
            Section("Рекомендуемые") {
                recommended.forEach { m ->
                    row {
                        ListRow(
                            m.name + if (m.id == task.default) " · по умолчанию" else "",
                            subtitle = priceLine(m) + (m.note?.let { "\n$it" } ?: ""), maxSubtitleLines = 3,
                            onClick = { choose(m.id) },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                m.badge?.let { Capsule(it, Ios.colors.indigo) }
                                if (m.id == current) Text("  ✓", color = Ios.colors.tint,
                                    style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                if (current != task.default) {
                    row { ListRow("Вернуть рекомендованную", titleColor = Ios.colors.tint, onClick = { choose(null) }) }
                }
            }
            if (task.kind == "llm" && info.all.isNotEmpty()) {
                Section("Все модели OpenRouter · ${info.all.size}") {
                    row {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            if (query.isEmpty()) Text("Поиск: claude, gemini, deepseek…", color = Ios.colors.tertiary,
                                style = MaterialTheme.typography.bodyLarge)
                            BasicTextField(query, { query = it }, singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ios.colors.label),
                                cursorBrush = SolidColor(Ios.colors.tint), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    val q = query.trim().lowercase(Locale.ROOT)
                    info.all.filter { q.isEmpty() || q in it.id.lowercase(Locale.ROOT) || q in it.name.lowercase(Locale.ROOT) }
                        .take(40).forEach { m ->
                            row {
                                ListRow(m.name, subtitle = m.id + " · " + priceLine(m), maxSubtitleLines = 1,
                                    onClick = { choose(m.id) }) {
                                    if (m.id == current) Text("✓", color = Ios.colors.tint,
                                        style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                }
            }
        }
    }
}

// ======================================================================= расширения
@Composable
private fun ExtensionsPage(onBack: () -> Unit, onOpen: (String) -> Unit) {
    var state by remember { mutableStateOf<Load<Pair<List<Extension>, List<Extension>>>>(Load.Loading) }
    var manifestUrl by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            state = try {
                Load.Ok(Api.extensions())
            } catch (e: Exception) {
                Load.Err(e.message ?: "Ошибка")
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    fun install(id: String?, url: String?) {
        note = "Устанавливаю…"
        scope.launch {
            note = try {
                Api.installExtension(id, url)
                reload()
                "Установлено"
            } catch (e: Exception) {
                e.message
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        NavBar("Расширения", onBack, backLabel = "Настройки")
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ios.colors.secondary,
            modifier = Modifier.padding(horizontal = 20.dp)) }
        LoadContent(state, onRetry = { reload() }) { (installed, available) ->
            Section("Установлено", footer = if (installed.isEmpty()) "Пока ничего. Установите из маркетплейса ниже." else
                "Агент сам решает, когда вызвать расширение. Новые задачи уходят во все расширения с интеграцией задач.") {
                installed.forEach { e ->
                    row {
                        ListRow(
                            e.name,
                            subtitle = when {
                                !e.ready -> "Нужно настроить"
                                e.taskSync != null -> "${e.tools.size} инстр. · задачи → ${e.name}"
                                else -> "${e.tools.size} инстр."
                            },
                            emoji = e.icon ?: "🧩", chevron = true, onClick = { onOpen(e.id) },
                        )
                    }
                }
            }
            Section("Маркетплейс", footer = "Проверенные расширения от Neuro Codes и сообщества. Каждое ходит только " +
                "на домены, указанные в манифесте.") {
                available.forEach { e ->
                    row {
                        ListRow(e.name + if (e.verified) " ✓" else "", subtitle = e.description, emoji = e.icon ?: "🧩",
                            maxSubtitleLines = 3) {
                            PillButton("Добавить", { install(e.id, null) }, small = true, filled = false)
                        }
                    }
                }
            }
            Section("Своё расширение", footer = "Опубликуйте manifest.json по https-ссылке и вставьте её сюда. " +
                "Как сделать расширение и выложить в маркетплейс — server/marketplace/README.md.") {
                row { FieldRow("Манифест", manifestUrl, { manifestUrl = it }, "https://…/manifest.json",
                    keyboard = KeyboardType.Uri) }
                row { ListRow("Установить по ссылке", titleColor = Ios.colors.tint,
                    onClick = { if (manifestUrl.isNotBlank()) install(null, manifestUrl.trim()) }) }
            }
        }
    }
}

@Composable
private fun ExtensionDetailPage(id: String, onBack: () -> Unit) {
    var ext by remember { mutableStateOf<Extension?>(null) }
    var values by remember { mutableStateOf(mapOf<String, String>()) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            try {
                val e = Api.extensions().first.firstOrNull { it.id == id }
                ext = e
                values = e?.settings?.associate { it.key to (it.value ?: "") } ?: emptyMap()
            } catch (e: Exception) {
                note = e.message
            }
        }
    }
    LaunchedEffect(id) { reload() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        val e = ext
        NavBar(e?.name ?: "Расширение", onBack, backLabel = "Расширения")
        if (e == null) {
            LoadContent<Unit>(if (note != null) Load.Err(note ?: "") else Load.Loading, onRetry = { reload() }) {}
            return@Column
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(emoji = e.icon ?: "🧩", color = Ios.colors.fill, size = 56)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(e.name, style = MaterialTheme.typography.headlineSmall, color = Ios.colors.label)
                Text(listOfNotNull(e.author, e.version?.let { "v$it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Ios.colors.secondary)
            }
        }
        Text(e.description, style = MaterialTheme.typography.bodyMedium, color = Ios.colors.label,
            modifier = Modifier.padding(horizontal = 20.dp))
        Section {
            row { ToggleRow("Включено", e.enabled, { on ->
                scope.launch {
                    try {
                        Api.updateExtension(e.id, enabled = on)
                        reload()
                    } catch (ex: Exception) {
                        note = ex.message
                    }
                }
            }) }
        }
        if (e.settings.isNotEmpty()) {
            Section("Настройки", footer = e.settings.mapNotNull { s -> s.hint?.let { "${s.title}: $it" } }
                .joinToString("\n").ifBlank { null }) {
                e.settings.forEach { s ->
                    row {
                        FieldRow(s.title, values[s.key] ?: "", { v -> values = values + (s.key to v) },
                            placeholder = if (s.secret && s.filled) "сохранено ••••" else if (s.required) "обязательно" else "",
                            secret = s.secret)
                    }
                }
                row {
                    ListRow("Сохранить", titleColor = Ios.colors.tint, subtitle = note, onClick = {
                        val changed = values.filterValues { it.isNotBlank() }
                        scope.launch {
                            note = try {
                                Api.updateExtension(e.id, settings = changed)
                                reload()
                                "Сохранено"
                            } catch (ex: Exception) {
                                ex.message
                            }
                        }
                    })
                }
            }
        }
        e.taskSync?.let { t -> Section("Интеграция задач") { row { ListRow(t, icon = NeuroIcons.Checklist,
            iconColor = Ios.colors.green) } } }
        Section("Что умеет") {
            e.tools.forEach { t -> row { ListRow(t.title, subtitle = t.description, maxSubtitleLines = 3) } }
        }
        Section("Доступ к интернету", footer = "Расширение может обращаться только к этим адресам.") {
            e.hosts.forEach { h -> row { ListRow(h, icon = NeuroIcons.Server, iconColor = Ios.colors.gray) } }
        }
        Section {
            row { ListRow("Удалить расширение", titleColor = Ios.colors.red, onClick = {
                scope.launch {
                    try {
                        Api.deleteExtension(e.id)
                        onBack()
                    } catch (ex: Exception) {
                        note = ex.message
                    }
                }
            }) }
        }
    }
}
