package codes.neuro.neurosoft.ui

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.ble.ConnState
import codes.neuro.neurosoft.voice.VoiceAssistant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Что показывает огонёк линзы. Это и есть сигнатура Mnemo. */
enum class LensState { OFF, IDLE, RECORDING, LISTENING, THINKING, SPEAKING, PAUSED, OFFLINE }

/** Состояние линзы из живого состояния кулона, сервера и голосового ассистента. */
@Composable
fun rememberLensState(): LensState {
    val device by Core.device.collectAsState()
    val running by Core.serviceRunning.collectAsState()
    val paused by Core.paused.collectAsState()
    val voice by Core.voice.state.collectAsState()
    val upload by Core.upload.collectAsState()
    return when {
        device.address == null || !running -> LensState.OFF
        voice.phase == VoiceAssistant.Phase.LISTENING -> LensState.LISTENING
        voice.phase == VoiceAssistant.Phase.THINKING -> LensState.THINKING
        voice.phase == VoiceAssistant.Phase.SPEAKING -> LensState.SPEAKING
        device.state != ConnState.CONNECTED -> LensState.OFFLINE
        paused -> LensState.PAUSED
        !upload.connected && !upload.resting -> LensState.OFFLINE
        System.currentTimeMillis() - device.lastAudioAt < 5_000 -> LensState.RECORDING
        else -> LensState.IDLE
    }
}

@Composable
private fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
    }
}

@Composable
fun lensColor(state: LensState): Color {
    val c = Mn.c
    val target = when (state) {
        LensState.OFF, LensState.PAUSED -> c.faint
        LensState.OFFLINE -> c.amber
        else -> c.lens
    }
    val color by animateColorAsState(target, tween(500), label = "lens")
    return color
}

/**
 * Линза: светящаяся точка. Дышит — пишет; расходится кольцами — слушает вопрос;
 * по кругу бежит искра — думает; ровный свет — на связи; янтарь — нет связи.
 */
@Composable
fun Lens(state: LensState, size: Dp, modifier: Modifier = Modifier, core: Float = 0.34f) {
    val color = lensColor(state)
    val still = reducedMotion()
    val t = rememberInfiniteTransition(label = "lens")
    val breath by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing),
        RepeatMode.Reverse), label = "breath")
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "ripple")
    val orbit by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "orbit")

    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val glowStrength = when (state) {
            LensState.OFF, LensState.PAUSED -> 0.0f
            LensState.RECORDING -> if (still) 0.8f else breath
            else -> 0.75f
        }
        if (glowStrength > 0f) {
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.45f * glowStrength), Color.Transparent),
                    center = center, radius = r),
                radius = r, center = center,
            )
        }
        if (state == LensState.LISTENING && !still) {
            for (k in 0..1) {
                val p = (ripple + k * 0.5f) % 1f
                drawCircle(color.copy(alpha = (1f - p) * 0.7f), radius = r * (core + (1f - core) * p), center = center,
                    style = Stroke(width = r * 0.06f))
            }
        }
        val coreR = r * core
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = if (state == LensState.OFF) 0.4f else 0.95f), color),
                center = Offset(center.x - coreR * 0.25f, center.y - coreR * 0.25f), radius = coreR * 1.3f),
            radius = coreR, center = center,
        )
        if (state == LensState.THINKING) {
            val a = (if (still) 0.15f else orbit) * 2 * PI
            val orbitR = coreR + r * 0.22f
            drawCircle(color, radius = r * 0.07f,
                center = Offset(center.x + orbitR * cos(a).toFloat(), center.y + orbitR * sin(a).toFloat()))
        }
        if (state == LensState.SPEAKING) {
            drawCircle(color.copy(alpha = 0.5f), radius = coreR + r * 0.16f, center = center,
                style = Stroke(width = r * 0.05f))
        }
    }
}

/** Кулон крупно: тёмное кольцо, матовый диск и огонёк в центре — как на экране устройства у Omi. */
@Composable
fun PendantRender(state: LensState, size: Dp, modifier: Modifier = Modifier) {
    val light = lensColor(state)
    val still = reducedMotion()
    val t = rememberInfiniteTransition(label = "pendant")
    val breath by t.animateFloat(0.75f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing),
        RepeatMode.Reverse), label = "breath")
    val dark = Mn.c.dark
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val glow = if (state == LensState.OFF || state == LensState.PAUSED) 0.25f
            else if (state == LensState.RECORDING && !still) breath else 0.9f
            // мягкое свечение вокруг корпуса
            drawCircle(Brush.radialGradient(listOf(light.copy(alpha = 0.32f * glow), Color.Transparent),
                center = Offset(c.x - r * 0.08f, c.y + r * 0.1f), radius = r), radius = r, center = c)
            val body = r * 0.62f
            // внешнее кольцо корпуса
            drawCircle(Brush.linearGradient(listOf(Color(0xFF3A3D44), Color(0xFF15161A)),
                start = Offset(c.x - body, c.y - body), end = Offset(c.x + body, c.y + body)), radius = body, center = c)
            drawCircle(Color(0xFF5B5F68).copy(alpha = if (dark) 0.6f else 0.9f), radius = body, center = c,
                style = Stroke(width = body * 0.03f))
            // матовый диск
            val disc = body * 0.8f
            drawCircle(Brush.radialGradient(listOf(Color(0xFF8C8F96), Color(0xFF4A4D54), Color(0xFF2B2D32)),
                center = Offset(c.x - disc * 0.3f, c.y - disc * 0.35f), radius = disc * 1.5f), radius = disc, center = c)
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = disc, center = c, style = Stroke(width = disc * 0.04f))
            // огонёк
            val led = disc * 0.07f
            drawCircle(Brush.radialGradient(listOf(light.copy(alpha = 0.9f * glow), Color.Transparent),
                center = c, radius = led * 5f), radius = led * 5f, center = c)
            drawCircle(Color.White.copy(alpha = if (state == LensState.OFF) 0.35f else 0.95f), radius = led, center = c)
            // кнопка сверху корпуса
            drawCircle(Color(0xFF6B6F78), radius = body * 0.05f, center = Offset(c.x, c.y - body * 0.93f))
        }
    }
}
