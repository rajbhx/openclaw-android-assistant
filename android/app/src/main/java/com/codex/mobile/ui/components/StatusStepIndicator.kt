package com.codex.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codex.mobile.ui.theme.AnyClawError
import com.codex.mobile.ui.theme.AnyClawSuccess
import kotlinx.coroutines.delay

/**
 * Displays a vertical list of setup steps with animated appearance.
 * Shows completed, active, and pending states.
 */
@Composable
fun StatusStepIndicator(
    steps: List<StepState>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        steps.forEachIndexed { index, step ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(index * 80L)
                visible = true
            }

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInVertically(),
            ) {
                StepRow(step = step)
            }
        }
    }
}

@Composable
private fun StepRow(step: StepState) {
    val alpha by animateFloatAsState(
        targetValue = if (step.isCompleted || step.isActive) 1f else 0.4f,
        label = "stepAlpha",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (step.isActive) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status icon
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    when {
                        step.isCompleted -> AnyClawSuccess.copy(alpha = 0.15f)
                        step.isError -> AnyClawError.copy(alpha = 0.15f)
                        step.isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else -> Color.Transparent
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when {
                    step.isCompleted -> Icons.Filled.Check
                    step.isError -> Icons.Filled.Error
                    else -> Icons.Filled.Info
                },
                contentDescription = null,
                tint = when {
                    step.isCompleted -> AnyClawSuccess
                    step.isError -> AnyClawError
                    step.isActive -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Text
        Column {
            Text(
                text = step.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (step.isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (step.detail.isNotEmpty()) {
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

data class StepState(
    val label: String,
    val detail: String = "",
    val isCompleted: Boolean = false,
    val isActive: Boolean = false,
    val isError: Boolean = false,
)
