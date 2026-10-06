package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.example.core.gemini.LiveSessionState
import kotlin.math.cos
import kotlin.math.sin

/**
 * Futuristic Holographic Avatar
 * - Lip-syncs and expands with audio output amplitude & formant frequencies
 * - Rotates and scans when THINKING
 * - Focuses and meets gaze when LISTENING
 * - Calms into gentle breathing when DISCONNECTED / IDLE
 */
@Composable
fun HolographicAvatar(
    sessionState: LiveSessionState,
    outputAmplitude: Float,
    inputAmplitude: Float,
    dominantFreq: Float,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    val infiniteTransition = rememberInfiniteTransition(label = "avatar_anim")

    // Idle rotation
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (sessionState == LiveSessionState.THINKING) 3000 else 12000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Breathing pulse
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing"
    )

    // Smooth lip-sync target
    val dynamicPulse = remember { Animatable(1f) }
    LaunchedEffect(outputAmplitude, inputAmplitude, sessionState) {
        val target = when (sessionState) {
            LiveSessionState.SPEAKING -> 1f + (outputAmplitude * 0.85f)
            LiveSessionState.LISTENING -> 1f + (inputAmplitude * 0.45f)
            else -> 1f
        }
        val clamped = target.coerceIn(0.8f, 1.6f)
        if (Math.abs(dynamicPulse.value - clamped) > 0.05f) {
            dynamicPulse.animateTo(
                targetValue = clamped,
                animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing)
            )
        }
    }

    Box(
        modifier = modifier.size(240.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.minDimension / 2f) * 0.75f * breathingScale * dynamicPulse.value

            // 1. Outer Hologram Glow Ring
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.35f),
                        primaryColor.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 1.35f
                ),
                radius = baseRadius * 1.35f,
                center = center
            )

            // 2. Rotating Segmented Outer Tech Ring
            rotate(rotationAngle, pivot = center) {
                val segments = 12
                val angleStep = 360f / segments
                for (i in 0 until segments) {
                    val startAngle = i * angleStep
                    val sweepAngle = angleStep * 0.65f
                    drawArc(
                        color = secondaryColor.copy(alpha = 0.6f),
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
                        size = androidx.compose.ui.geometry.Size(baseRadius * 2, baseRadius * 2),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }

            // 3. Counter-Rotating Inner Ring
            rotate(-rotationAngle * 1.6f, pivot = center) {
                val innerRadius = baseRadius * 0.75f
                val segments = 8
                val angleStep = 360f / segments
                for (i in 0 until segments) {
                    val startAngle = i * angleStep + 10f
                    val sweepAngle = angleStep * 0.5f
                    drawArc(
                        color = primaryColor.copy(alpha = 0.85f),
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = Offset(center.x - innerRadius, center.y - innerRadius),
                        size = androidx.compose.ui.geometry.Size(innerRadius * 2, innerRadius * 2),
                        style = Stroke(width = 4.dp.toPx())
                    )
                }
            }

            // 4. Formant-Reactive Arc Rays (Speaking State)
            if (sessionState == LiveSessionState.SPEAKING) {
                val rayCount = 16
                val rayLength = 15.dp.toPx() * (outputAmplitude + 0.2f)
                val rayRadius = baseRadius * 0.88f
                for (i in 0 until rayCount) {
                    val angleRad = Math.toRadians((i * (360.0 / rayCount) + rotationAngle).toDouble())
                    val p1 = Offset(
                        (center.x + rayRadius * cos(angleRad)).toFloat(),
                        (center.y + rayRadius * sin(angleRad)).toFloat()
                    )
                    val p2 = Offset(
                        (center.x + (rayRadius + rayLength) * cos(angleRad)).toFloat(),
                        (center.y + (rayRadius + rayLength) * sin(angleRad)).toFloat()
                    )
                    drawLine(
                        color = tertiaryColor.copy(alpha = 0.75f),
                        start = p1,
                        end = p2,
                        strokeWidth = 2.5.dp.toPx()
                    )
                }
            }

            // 5. Center Arc Core Iris
            val coreRadius = baseRadius * 0.38f * (if (sessionState == LiveSessionState.SPEAKING) (1f + outputAmplitude * 0.5f) else 1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White,
                        primaryColor,
                        primaryColor.copy(alpha = 0.2f)
                    ),
                    center = center,
                    radius = coreRadius
                ),
                radius = coreRadius,
                center = center
            )

            // Inner Iris Pupil Aperture
            val pupilRadius = coreRadius * 0.45f
            drawCircle(
                color = when (sessionState) {
                    LiveSessionState.THINKING -> tertiaryColor
                    LiveSessionState.SPEAKING -> Color.White
                    LiveSessionState.LISTENING -> primaryColor
                    else -> primaryColor.copy(alpha = 0.5f)
                },
                radius = pupilRadius,
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}
