package codes.neuro.neurosoft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import codes.neuro.neurosoft.ui.ChatScreen
import codes.neuro.neurosoft.ui.ConversationScreen
import codes.neuro.neurosoft.ui.DiaryScreen
import codes.neuro.neurosoft.ui.Ios
import codes.neuro.neurosoft.ui.NeuroIcons
import codes.neuro.neurosoft.ui.NeuroTheme
import codes.neuro.neurosoft.ui.SettingsScreen
import codes.neuro.neurosoft.ui.TasksScreen
import codes.neuro.neurosoft.ui.TodayScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        enableEdgeToEdge()
        setContent {
            NeuroTheme { NeuroRoot() }
        }
    }

    override fun onResume() {
        super.onResume()
        Core.syncPhotosNow()
    }
}

enum class Tab(val title: String, val icon: ImageVector) {
    Today("Сегодня", NeuroIcons.Waveform),
    Chat("Агент", NeuroIcons.Sparkles),
    Diary("Дневник", NeuroIcons.Book),
    Tasks("Задачи", NeuroIcons.Checklist),
    Settings("Настройки", NeuroIcons.Gear),
}

@Composable
fun NeuroRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    // открытый разговор (из «Сегодня», задач или настроек)
    var openConversation by rememberSaveable { mutableStateOf<Long?>(null) }

    BackHandler(enabled = openConversation != null) { openConversation = null }
    BackHandler(enabled = openConversation == null && tab != Tab.Today) { tab = Tab.Today }

    Scaffold(
        containerColor = Ios.colors.bg,
        bottomBar = {
            TabBar(tab) {
                if (it == tab) openConversation = null
                tab = it
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(Ios.colors.bg)) {
            val open = openConversation
            if (open != null) {
                ConversationScreen(open, onBack = { openConversation = null })
            } else {
                when (tab) {
                    Tab.Today -> TodayScreen(onOpenConversation = { openConversation = it },
                        onOpenChat = { tab = Tab.Chat })
                    Tab.Chat -> ChatScreen()
                    Tab.Diary -> DiaryScreen(onOpenSettings = { tab = Tab.Settings })
                    Tab.Tasks -> TasksScreen(onOpenConversation = { openConversation = it })
                    Tab.Settings -> SettingsScreen(onOpenConversation = { openConversation = it })
                }
            }
        }
    }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ios.colors.bar)) {
        HorizontalDivider(thickness = 0.5.dp, color = Ios.colors.separator)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(54.dp)) {
            Tab.entries.forEach { t ->
                val color = if (t == selected) Ios.colors.tint else Ios.colors.gray
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, contentDescription = t.title, tint = color, modifier = Modifier.size(25.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(t.title, color = color, fontSize = 10.sp)
                }
            }
        }
    }
}
