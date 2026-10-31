package codes.neuro.neurosoft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier

@Composable
fun AppRoot() {
    val top = Nav.stack.last()
    val holder = rememberSaveableStateHolder()
    PairRequestDialog()
    BackHandler(enabled = Nav.stack.size > 1) { Nav.pop() }
    Box(Modifier.fillMaxSize().background(Mn.c.fog)) {
        AnimatedContent(
            targetState = top,
            transitionSpec = {
                if (Nav.forward) {
                    (slideInHorizontally(tween(260)) { it / 5 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
                } else {
                    fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(240)) { it / 5 } + fadeOut(tween(180)))
                }
            },
            label = "nav",
        ) { route ->
            holder.SaveableStateProvider(route.toString()) {
                Box(Modifier.fillMaxSize().background(Mn.c.fog)) { Screen(route) }
            }
        }
    }
}

@Composable
private fun Screen(route: Route) {
    when (route) {
        Route.Onboarding -> OnboardingScreen()
        Route.Home -> HomeScreen()
        is Route.Conversation -> ConversationScreen(route.id)
        is Route.Event -> EventScreen(route.id, route.day)
        Route.Trash -> TrashScreen()
        is Route.Chat -> ChatScreen(route.prefill)
        Route.Search -> SearchScreen()
        is Route.Day -> DayScreen(route.day)
        Route.Settings -> SettingsScreen()
        Route.Device -> DeviceScreen()
        Route.Voice -> VoiceSettingsScreen()
        Route.DiarySettings -> DiarySettingsScreen()
        Route.Language -> LanguageScreen()
        Route.SttLanguage -> SttLanguageScreen()
        Route.Models -> ModelsScreen()
        is Route.ModelPicker -> ModelPickerScreen(route.task)
        Route.Extensions -> ExtensionsScreen()
        is Route.Extension -> ExtensionScreen(route.id)
        Route.Memories -> MemoriesScreen()
        is Route.Person -> PersonScreen(route.id)
        Route.VoicesSettings -> VoicesSettingsScreen()
        Route.AudioKeep -> AudioKeepScreen()
        Route.Theme -> ThemeScreen()
        Route.Claude -> ClaudeScreen()
        Route.Server -> ServerScreen()
    }
}

/** Новый телефон просит доступ к серверу: подтверждение на уже подключённом телефоне. */
@Composable
private fun PairRequestDialog() {
    val upload by Core.upload.collectAsState()
    var request by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val c = Mn.c
    LaunchedEffect(upload.connected) {
        while (upload.connected) {
            try {
                if (request == null) request = Api.pairRequests().firstOrNull()
            } catch (_: Exception) {
            }
            delay(20_000)
        }
    }
    val r = request ?: return
    fun answer(approve: Boolean) {
        request = null
        scope.launch { try { Api.answerPairRequest(r.first, approve) } catch (_: Exception) {} }
    }
    AlertDialog(
        onDismissRequest = {},
        containerColor = c.paper,
        title = { Text(stringResource(R.string.pair_request_title, r.second), style = T.section, color = c.ink) },
        text = { Text(stringResource(R.string.pair_request_text), style = T.body, color = c.graphite) },
        confirmButton = {
            TextButton(onClick = { answer(true) }) { Text(stringResource(R.string.allow), color = c.ink, style = T.bodyStrong) }
        },
        dismissButton = {
            TextButton(onClick = { answer(false) }) { Text(stringResource(R.string.deny), color = c.red, style = T.bodyStrong) }
        },
    )
}
