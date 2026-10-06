package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun ReactiveWaveform(
    amplitude: Float,
    isSpeaking: Boolean,
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val accentColor = if (isSpeaking) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    val transition = rememberInfiniteTransition(label = "waveform_phase")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        val effectiveAmp = if (isSpeaking || isListening) {
            (amplitude.coerceIn(0.05f, 1f)) * (height * 0.32f)
        } else {
            2.dp.toPx()
        }

        // Base horizontal centerline glow
        drawLine(
            color = primaryColor.copy(alpha = 0.25f),
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1.dp.toPx()
        )

        // Wave 1: Primary harmonic
        val path1 = Path()
        path1.moveTo(0f, centerY)
        val step = 4f
        var x = 0f
        while (x <= width) {
            val progress = x / width
            val envelope = sin(progress * Math.PI).toFloat() // window envelope
            val y = centerY + sin((x * 0.035f) + phase).toFloat() * effectiveAmp * envelope
            path1.lineTo(x, y)
            x += step
        }

        drawPath(
            path = path1,
            color = accentColor,
            style = Stroke(width = 2.5.dp.toPx())
        )

        // Wave 2: Secondary harmonic
        val path2 = Path()
        path2.moveTo(0f, centerY)
        x = 0f
        while (x <= width) {
            val progress = x / width
            val envelope = sin(progress * Math.PI).toFloat()
            val y = centerY + sin((x * 0.05f) - phase * 1.3f).toFloat() * (effectiveAmp * 0.65f) * envelope
            path2.lineTo(x, y)
            x += step
        }

        drawPath(
            path = path2,
            color = primaryColor.copy(alpha = 0.6f),
            style = Stroke(width = 1.8.dp.toPx())
        )
    }
}
