package com.codex.mobile.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codex.mobile.state.SetupState
import com.codex.mobile.ui.components.AnimatedLoadingIndicator
import com.codex.mobile.ui.components.StatusStepIndicator
import com.codex.mobile.ui.components.StepState

@Composable
fun LoadingScreen(
    state: SetupState,
    onRetry: () -> Unit,
    onApiKeySubmit: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state.phase,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "phase",
        ) { phase ->
            when (phase) {
                SetupState.Phase.ERROR -> ErrorContent(
                    message = state.error ?: "Unknown error",
                    onRetry = onRetry,
                )
                SetupState.Phase.API_KEY_PROMPT -> ApiKeyPrompt(
                    onSubmit = onApiKeySubmit,
                )
                else -> LoadingContent(state = state)
            }
        }
    }
}

@Composable
private fun LoadingContent(state: SetupState) {
    val progress by animateFloatAsState(
        targetValue = state.progress,
        label = "progress",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedLoadingIndicator(
            progress = progress,
            modifier = Modifier.size(120.dp),
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "AnyClaw",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "OpenClaw + Codex on Android",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(48.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (state.detail.isNotEmpty()) {
                    Text(
                        text = state.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(2.dp),
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progress)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }

                Text(
                    text = "${state.progressPercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        val steps = remember(state.phase) {
            buildStepStates(state)
        }

        AnimatedVisibility(
            visible = steps.isNotEmpty(),
            enter = fadeIn() + slideInVertically(),
        ) {
            StatusStepIndicator(steps = steps)
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "⚠️",
            style = MaterialTheme.typography.displayLarge,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(
                text = "Retry",
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ApiKeyPrompt(onSubmit: (String) -> Unit) {
    var apiKey by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "🔐",
            style = MaterialTheme.typography.displayLarge,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Connect to OpenAI",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Enter your OpenAI API key to use Codex.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (passwordVisible) {
                                    "Hide API key"
                                } else {
                                    "Show API key"
                                },
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (apiKey.isNotBlank()) {
                                focusManager.clearFocus()
                                onSubmit(apiKey)
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                focusManager.clearFocus()
                onSubmit(apiKey)
            },
            enabled = apiKey.isNotBlank() && apiKey.startsWith("sk-"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(
                text = "Continue",
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Your key is stored securely on device",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(0.6f),
        )
    }
}

private fun buildStepStates(state: SetupState): List<StepState> {
    val phases = listOf(
        SetupState.Phase.EXTRACTING_BOOTSTRAP to "Extracting environment",
        SetupState.Phase.INSTALLING_PROOT to "Installing proot",
        SetupState.Phase.INSTALLING_NODE to "Installing Node.js",
        SetupState.Phase.INSTALLING_CODEX to "Installing Codex CLI",
        SetupState.Phase.STARTING_SERVER to "Starting server",
        SetupState.Phase.WAITING_SERVER to "Waiting for server",
    )

    val currentPhaseIndex = phases.indexOfFirst { it.first == state.phase }

    return phases.mapIndexed { index, (phase, label) ->
        StepState(
            label = label,
            detail = if (phase == state.phase) state.detail else "",
            isCompleted = index < currentPhaseIndex,
            isActive = phase == state.phase,
            isError = state.phase == SetupState.Phase.ERROR && index == currentPhaseIndex,
        )
    }
}
