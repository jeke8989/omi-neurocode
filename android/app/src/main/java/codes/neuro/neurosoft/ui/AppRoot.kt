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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier

@Composable
fun AppRoot() {
    val top = Nav.stack.last()
    val holder = rememberSaveableStateHolder()
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
    }
}
