package com.codex.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Animated circular progress indicator with gradient stroke.
 * Pulses gently while loading, spins during active progress.
 */
@Composable
fun AnimatedLoadingIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    strokeWidth: Dp = 6.dp,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    val infiniteTransition = rememberInfiniteTransition(label = "loading")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Canvas(
        modifier = modifier.size(size * pulse),
    ) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val radius = (this.size.minDimension - strokeWidth.toPx()) / 2
        val center = Offset(this.size.width / 2, this.size.height / 2)

        // Background track
        drawCircle(
            color = tertiaryColor.copy(alpha = 0.15f),
            radius = radius,
            center = center,
        )

        // Progress arc
        val sweepAngle = progress * 360f
        drawArc(
            color = primaryColor,
            startAngle = rotation - 90f,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = stroke,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
        )

        // Accent dot at the tip
        if (progress > 0f) {
            val tipAngle = Math.toRadians((rotation - 90f + sweepAngle).toDouble())
            val tipX = center.x + radius * kotlin.math.cos(tipAngle).toFloat()
            val tipY = center.y + radius * kotlin.math.sin(tipAngle).toFloat()
            drawCircle(
                color = secondaryColor,
                radius = strokeWidth.toPx() * 1.5f,
                center = Offset(tipX, tipY),
            )
        }
    }
}
