package codes.neuro.neurosoft.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import kotlinx.coroutines.launch

/**
 * Под сводкой события, разговора или дня: «Скопировать» — всё (итог, люди, части, расшифровка) в буфер обмена;
 * «Обсудить в чате» — чат, где агент уже видит это событие и отвечает с его учётом.
 * ref — «event:12», «conversation:5», «day:2026-10-31».
 */
@Composable
fun ShareActions(ref: String, title: String?, modifier: Modifier = Modifier,
                 padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copying by remember(ref) { mutableStateOf(false) }
    val copied = stringResource(R.string.copied)
    val failed = stringResource(R.string.copy_failed)
    Row(modifier.fillMaxWidth().padding(padding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionPill(Icons.Copy, stringResource(if (copying) R.string.copying else R.string.copy_all), Modifier.weight(1f)) {
            if (copying) return@ActionPill
            copying = true
            scope.launch {
                val ok = try {
                    val (_, text) = Api.share(ref)
                    copy(context, title ?: "Mnemo", text)
                    true
                } catch (e: Exception) {
                    false
                }
                copying = false
                Toast.makeText(context, if (ok) copied else failed, Toast.LENGTH_SHORT).show()
            }
        }
        ActionPill(Icons.Sparkle, stringResource(R.string.discuss_in_chat), Modifier.weight(1f), accent = true) {
            Nav.push(Route.Chat(context = ref, contextTitle = title))
        }
    }
}

private fun copy(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
private fun ActionPill(icon: ImageVector, text: String, modifier: Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val c = Mn.c
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(c.paper).tap(onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (accent) c.lens else c.ink, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Txt(text, T.smallStrong, if (accent) c.lens else c.ink, maxLines = 1)
    }
}
