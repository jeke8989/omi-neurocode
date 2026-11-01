package codes.neuro.neurosoft.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.ConversationItem
import kotlinx.coroutines.launch

/** Важные разговоры: отмечены двойным нажатием кнопки кулона или звёздочкой. */
@Composable
fun ImportantScreen() {
    var state by remember { mutableStateOf<Res<List<ConversationItem>>>(Res(loading = true)) }
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    fun reload() {
        scope.launch {
            state = try {
                Res(Api.important())
            } catch (e: Exception) {
                Res(error = e)
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.important_title), { Nav.pop() })
        val items = state.data
        when {
            items == null && state.error != null -> ErrorBlock(state.error, { reload() })
            items == null -> LoadingBlock()
            items.isEmpty() -> EmptyBlock(Icons.Star, stringResource(R.string.important_empty_title),
                stringResource(R.string.important_empty_text))
            else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(items, key = { _, c -> c.id }) { i, conv ->
                    Grouped(i, items.size) { ConversationRow(conv, fmt, showDay = true) }
                }
            }
        }
    }
}
