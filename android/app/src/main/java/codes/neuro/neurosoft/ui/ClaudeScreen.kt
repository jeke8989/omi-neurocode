package codes.neuro.neurosoft.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.ApiException
import kotlinx.coroutines.launch

/** Настройки → Claude Code: токен из `claude setup-token`, модель, сессия, ключ для GitHub. */
@Composable
fun ClaudeScreen() {
    val res by Repo.claude.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var token by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var copied by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    val saved = stringResource(R.string.saved)
    val badToken = stringResource(R.string.claude_token_bad)
    val sessionReset = stringResource(R.string.claude_session_reset)
    LaunchedEffect(Unit) { Repo.claude.refresh() }
    val c = Mn.c

    fun act(done: String? = null, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                Repo.claude.refresh()
                error = null
                note = done
            } catch (e: Exception) {
                note = if ((e as? ApiException)?.detail == "400") badToken else null
                error = if (note == null) e else null
            }
        }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            containerColor = c.paper,
            title = { Text(stringResource(R.string.claude_disconnect_q), style = T.section, color = c.ink) },
            text = { Text(stringResource(R.string.claude_disconnect_text), style = T.body, color = c.graphite) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = false
                    act { Api.disconnectClaude() }
                }) { Text(stringResource(R.string.claude_disconnect), color = c.red, style = T.bodyStrong) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOff = false }) {
                    Text(stringResource(R.string.cancel), color = c.ink, style = T.bodyStrong)
                }
            },
        )
    }

    SettingsPage(stringResource(R.string.claude_code)) {
        val info = res.data
        when {
            info == null && res.error != null -> ErrorBlock(res.error, { scope.launch { Repo.claude.refresh() } })
            info == null -> LoadingBlock()
            else -> {
                Txt(stringResource(R.string.claude_intro), T.body, c.graphite,
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                error?.let { StaleNote(it) }
                note?.let { Txt(it, T.caption, c.graphite, Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }

                Group(stringResource(R.string.claude_token), footer = stringResource(R.string.claude_token_footer),
                    inset = 22.dp) {
                    item {
                        FieldRow(stringResource(R.string.claude_token), token, { token = it },
                            placeholder = info.tokenHint?.let { stringResource(R.string.claude_token_saved, it) }
                                ?: stringResource(R.string.claude_token_paste), secret = true)
                    }
                    item {
                        ListRow(stringResource(R.string.save), icon = Icons.Check, accent = token.isNotBlank(),
                            onClick = if (token.isBlank()) null else ({
                                val t = token
                                act(saved) {
                                    Api.setClaude(token = t)
                                    token = ""
                                }
                            }))
                    }
                }

                SectionTitle(stringResource(R.string.claude_model))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(info.models, key = { it }) { m ->
                        Chip(if (m.isEmpty()) stringResource(R.string.claude_model_default) else m.replaceFirstChar { it.uppercase() },
                            m == info.model, { act { Api.setClaude(model = m) } })
                    }
                }

                Group(stringResource(R.string.claude_workspace), inset = 22.dp) {
                    item {
                        ListRow(stringResource(R.string.claude_service), icon = Icons.Cloud,
                            value = if (info.available) info.version?.substringBefore(" ") else null,
                            subtitle = if (info.available) null else stringResource(R.string.claude_service_off))
                    }
                    info.head?.let { head ->
                        item {
                            ListRow(head, icon = Icons.Code, subtitle = listOfNotNull(info.repo?.removePrefix("https://"),
                                if (info.dirty) stringResource(R.string.claude_dirty) else null).joinToString(", "))
                        }
                    }
                    item {
                        ListRow(stringResource(R.string.claude_new_session), icon = Icons.Refresh,
                            subtitle = stringResource(R.string.claude_new_session_sub),
                            onClick = { act(sessionReset) { Api.setClaude(newSession = true) } })
                    }
                }

                info.deployKey?.let { key ->
                    Group(stringResource(R.string.claude_deploy_key), footer = stringResource(R.string.claude_deploy_footer)) {
                        item {
                            ListRow(key.substringBeforeLast(" ").takeLast(24).let { "…$it" }, icon = Icons.Link,
                                subtitle = if (copied) stringResource(R.string.copied) else null,
                                trailing = { IconCircle(Icons.Copy, stringResource(R.string.copy), {
                                    clipboard.setText(AnnotatedString(key))
                                    copied = true
                                }, size = 38.dp, background = c.raised) })
                        }
                    }
                }

                if (info.configured) {
                    Group {
                        item {
                            ListRow(stringResource(R.string.claude_disconnect), icon = Icons.Trash, danger = true,
                                onClick = { confirmOff = true })
                        }
                    }
                }
            }
        }
    }
}
