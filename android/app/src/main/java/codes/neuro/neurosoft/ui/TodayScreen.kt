package codes.neuro.neurosoft.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.Permissions
import codes.neuro.neurosoft.ble.ConnState
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.ConversationItem
import codes.neuro.neurosoft.voice.VoiceAssistant
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodayScreen(onOpenConversation: (Long) -> Unit, onOpenChat: () -> Unit) {
    val context = LocalContext.current
    val device by Core.device.collectAsState()
    val upload by Core.upload.collectAsState()
    val running by Core.serviceRunning.collectAsState()
    val voice by Core.voice.state.collectAsState()
    var missing by remember { mutableStateOf(Permissions.missing(context)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = Permissions.missing(context)
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var today by remember { mutableStateOf<List<ConversationItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            if (n++ % 20 == 0) {
                today = try {
                    Api.conversations(30).filter { isToday(it.startedAt) }
                } catch (e: Exception) {
                    today
                }
            }
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    val connected = device.state == ConnState.CONNECTED
    val recording = running && connected && now - device.lastAudioAt < 5_000
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru")))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        LargeTitle("NeuroSoft", subtitle = date)

        if (missing.isNotEmpty()) {
            Section(footer = "Bluetooth — чтобы слышать кулон, уведомления — чтобы запись не останавливалась в фоне.") {
                row {
                    ListRow("Разрешить доступ", subtitle = "Нужно один раз", icon = NeuroIcons.Waveform,
                        iconColor = Ios.colors.orange, chevron = true,
                        onClick = { permissions.launch(Permissions.required()) })
                }
            }
        }

        // ------------------------------------------------------------ кольцо записи
        Column(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(22.dp)).background(Ios.colors.cell)
                .padding(vertical = 24.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RecordingRing(recording = recording, listening = voice.phase == VoiceAssistant.Phase.LISTENING,
                connected = connected)
            Spacer(Modifier.height(16.dp))
            Text(
                when {
                    device.address == null -> "Кулон не подключён"
                    !running -> "Запись выключена"
                    voice.phase == VoiceAssistant.Phase.LISTENING -> "Слушаю вопрос…"
                    voice.phase == VoiceAssistant.Phase.THINKING -> "Думаю…"
                    voice.phase == VoiceAssistant.Phase.SPEAKING -> "Отвечаю в наушник"
                    recording -> "Идёт запись"
                    else -> Core.stateText(device.state)
                },
                style = MaterialTheme.typography.headlineSmall, color = Ios.colors.label,
            )
            Text(
                if (device.address == null) "Откройте Настройки → Кулон"
                else "${device.name ?: "Omi"} · одно нажатие — вопрос, два — «запомни»",
                style = MaterialTheme.typography.bodySmall, color = Ios.colors.secondary, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            if (device.address != null) {
                if (running) {
                    PillButton("Остановить запись", { Core.stopCapture(context) }, Modifier.fillMaxWidth(),
                        color = Ios.colors.red, filled = false)
                } else {
                    PillButton("Начать запись", {
                        if (Permissions.bluetoothGranted(context)) Core.startCapture(context)
                        else permissions.launch(Permissions.required())
                    }, Modifier.fillMaxWidth())
                }
            }
        }

        // ------------------------------------------------------------ плитки
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Батарея", device.battery?.let { "$it%" } ?: "—", NeuroIcons.Battery,
                if ((device.battery ?: 100) < 20) Ios.colors.red else Ios.colors.green, Modifier.weight(1f))
            Tile("Сервер", if (upload.connected) "на связи" else if (upload.configured) "нет связи" else "не задан",
                NeuroIcons.Server, if (upload.connected) Ios.colors.tint else Ios.colors.orange, Modifier.weight(1f))
            Tile("Очередь", if (upload.pendingFiles <= 1) "пусто" else bytesText(upload.pendingBytes),
                NeuroIcons.Waveform, Ios.colors.purple, Modifier.weight(1f))
        }

        // ------------------------------------------------------------ ассистент
        if (voice.answer != null || voice.phase != VoiceAssistant.Phase.IDLE) {
            Section("Ассистент") {
                row {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        voice.question?.let {
                            Text("«$it»", style = MaterialTheme.typography.bodyMedium, color = Ios.colors.secondary)
                        }
                        if (voice.phase == VoiceAssistant.Phase.LISTENING || voice.phase == VoiceAssistant.Phase.THINKING) {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ios.colors.tint,
                                trackColor = Ios.colors.fill)
                        }
                        voice.answer?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Ios.colors.label) }
                    }
                }
                if (voice.pending) {
                    row { ListRow("Подробный ответ — в чате агента", icon = NeuroIcons.Sparkles,
                        iconColor = Ios.colors.indigo, chevron = true, onClick = onOpenChat) }
                }
            }
        } else if (device.address != null) {
            Section(footer = "Нажмите кнопку на кулоне один раз и задайте вопрос — ответ прозвучит в наушнике. " +
                "Длинные задачи агент доделает и пришлёт в чат.") {}
        }

        // ------------------------------------------------------------ разговоры сегодня
        Section("Разговоры сегодня", footer = if (today.isEmpty()) "Разговоры появятся через пару минут после записи." else null) {
            today.forEach { c ->
                row {
                    ListRow(
                        c.title ?: c.preview ?: "Без названия",
                        subtitle = formatShortTime(c.startedAt) + "–" + formatShortTime(c.endedAt) +
                            (c.summary?.let { " · $it" } ?: ""),
                        chevron = true, onClick = { onOpenConversation(c.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Tile(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color,
                 modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Ios.colors.cell).padding(12.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(8.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = Ios.colors.label, maxLines = 1)
        Text(title, style = MaterialTheme.typography.labelMedium, color = Ios.colors.secondary)
    }
}

@Composable
private fun RecordingRing(recording: Boolean, listening: Boolean, connected: Boolean) {
    val transition = rememberInfiniteTransition(label = "ring")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "angle")
    val pulse by transition.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse")
    val c = Ios.colors
    val accent = when {
        listening -> c.orange
        recording -> c.green
        connected -> c.tint
        else -> c.gray
    }
    Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            drawCircle(color = accent.copy(alpha = 0.16f), radius = size.minDimension / 2 - stroke / 2,
                style = Stroke(width = stroke))
            if (recording || listening) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(accent.copy(alpha = 0f), accent, accent)),
                    startAngle = angle, sweepAngle = 300f, useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            Modifier.size(120.dp).scale(if (recording || listening) pulse else 1f).clip(CircleShape)
                .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.7f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (listening) NeuroIcons.Mic else NeuroIcons.Waveform, null, tint = Color.White,
                modifier = Modifier.size(48.dp))
        }
    }
}
