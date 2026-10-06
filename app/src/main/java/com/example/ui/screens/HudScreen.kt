package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.gemini.LiveSessionState
import com.example.ui.components.ActivityLogView
import com.example.ui.components.HolographicAvatar
import com.example.ui.components.ReactiveWaveform
import com.example.ui.components.SafetyConfirmationDialog
import com.example.ui.viewmodel.JarvisViewModel
import kotlinx.coroutines.launch

@Composable
fun HudScreen(
    viewModel: JarvisViewModel,
    onNavigateToMemory: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val sessionState by viewModel.sessionState.collectAsState()
    val activityLog by viewModel.activityLog.collectAsState()
    val visualState by viewModel.visualState.collectAsState()
    val userSettings by viewModel.userSettings.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val lastActionDesc by viewModel.lastActionDesc.collectAsState()
    val isApiKeyConfigured by viewModel.apiKeyConfigured.collectAsState()
    val isStorageEncrypted by viewModel.storageEncrypted.collectAsState()
    val audioDevices by viewModel.audioDevices.collectAsState()

    var inputPrompt by remember { mutableStateOf("") }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF0A0F1A)
            ) {
                SettingsDrawerContent(
                    isApiKeyConfigured = isApiKeyConfigured,
                    isStorageEncrypted = isStorageEncrypted,
                    settings = userSettings,
                    availableDevices = audioDevices,
                    onSaveApiKey = { viewModel.saveApiKey(it) },
                    onUpdateAssistantName = { viewModel.updateAssistantName(it) },
                    onUpdateUserName = { viewModel.updateUserName(it) },
                    onSelectVoice = { viewModel.selectVoice(it) },
                    onUpdateHue = { viewModel.updateHue(it) },
                    onUpdateLanguage = { viewModel.updateLanguage(it) },
                    onToggleWakeWord = { viewModel.toggleWakeWord(it) },
                    onToggleAutoStart = { viewModel.setAutoStartOnBoot(it) },
                    onToggleOverlay = { viewModel.setOverlayBubble(it) },
                    onSelectAudioDevice = { viewModel.selectAudioInput(it) },
                    onNavigateToMemory = {
                        scope.launch { drawerState.close() }
                        onNavigateToMemory()
                    },
                    onNavigateToAbout = {
                        scope.launch { drawerState.close() }
                        onNavigateToAbout()
                    },
                    onOpenPermissions = {
                        scope.launch { drawerState.close() }
                        onOpenPermissions()
                    }
                )
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                HudTopBar(
                    assistantName = userSettings.assistantName,
                    sessionState = sessionState,
                    canUndo = canUndo,
                    lastActionDesc = lastActionDesc,
                    onUndoClick = { viewModel.triggerUndo() },
                    onOpenSettings = { scope.launch { drawerState.open() } }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Central Holographic Avatar Area
                HolographicAvatar(
                    sessionState = sessionState,
                    outputAmplitude = visualState.outputAmplitude,
                    inputAmplitude = visualState.inputAmplitude,
                    dominantFreq = visualState.dominantFrequency,
                    modifier = Modifier.padding(top = 8.dp)
                )

                // Real-time Reactive Waveform
                ReactiveWaveform(
                    amplitude = if (visualState.isSpeaking) visualState.outputAmplitude else visualState.inputAmplitude,
                    isSpeaking = visualState.isSpeaking,
                    isListening = visualState.isListening,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // Quick Action Chips Row
                QuickActionChipsRow(
                    onAction = { cmd -> viewModel.sendUserText(cmd) }
                )

                // Scrolling HUD Activity Log
                ActivityLogView(
                    events = activityLog,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                )

                // Bottom Interaction Bar (Mic button + Text Input)
                HudBottomBar(
                    sessionState = sessionState,
                    inputPrompt = inputPrompt,
                    onPromptChange = { inputPrompt = it },
                    onSendText = {
                        viewModel.sendUserText(inputPrompt)
                        inputPrompt = ""
                    },
                    onToggleVoice = { viewModel.toggleVoiceConnection() }
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // Safety Confirmation Dialog for sensitive irreversible actions
    pendingConfirmation?.let { request ->
        SafetyConfirmationDialog(
            request = request,
            onApprove = { token -> viewModel.approveConfirmation(token) },
            onReject = { viewModel.rejectConfirmation() }
        )
    }
}

@Composable
fun HudTopBar(
    assistantName: String,
    sessionState: LiveSessionState,
    canUndo: Boolean,
    lastActionDesc: String?,
    onUndoClick: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = when (sessionState) {
            LiveSessionState.CONNECTED -> MaterialTheme.colorScheme.primary
            LiveSessionState.LISTENING -> MaterialTheme.colorScheme.secondary
            LiveSessionState.THINKING -> MaterialTheme.colorScheme.tertiary
            LiveSessionState.SPEAKING -> Color.White
            LiveSessionState.ERROR -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        },
        label = "status_color"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = assistantName.uppercase(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 2.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    text = sessionState.name,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (canUndo) {
                IconButton(
                    onClick = onUndoClick,
                    modifier = Modifier.testTag("undo_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo $lastActionDesc",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("settings_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun QuickActionChipsRow(onAction: (String) -> Unit) {
    val chips = listOf(
        "Weather Report" to "What is the live weather forecast?",
        "Diagnostics" to "Check device health and diagnostics",
        "Inspect Clipboard" to "Analyze the clipboard contents",
        "Agenda" to "What is on my calendar today?"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for ((label, prompt) in chips) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0F1728))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .clickable { onAction(prompt) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun HudBottomBar(
    sessionState: LiveSessionState,
    inputPrompt: String,
    onPromptChange: (String) -> Unit,
    onSendText: () -> Unit,
    onToggleVoice: () -> Unit
) {
    val isLive = sessionState == LiveSessionState.CONNECTED ||
            sessionState == LiveSessionState.LISTENING ||
            sessionState == LiveSessionState.SPEAKING ||
            sessionState == LiveSessionState.THINKING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Large Mic Toggle Button
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    if (isLive) MaterialTheme.colorScheme.primary else Color(0xFF162238)
                )
                .border(
                    2.dp,
                    if (isLive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
                    CircleShape
                )
                .clickable { onToggleVoice() }
                .testTag("voice_toggle_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isLive) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = if (isLive) "Disconnect Voice" else "Connect Voice",
                tint = if (isLive) Color(0xFF001F26) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Text Input Box for hybrid use
        OutlinedTextField(
            value = inputPrompt,
            onValueChange = onPromptChange,
            placeholder = {
                Text(
                    text = if (isLive) "Voice live. Type or speak..." else "Enter command or question...",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .testTag("prompt_input_field"),
            shape = RoundedCornerShape(26.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                focusedContainerColor = Color(0xFF0C1322),
                unfocusedContainerColor = Color(0xFF0C1322)
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSendText() }),
            trailingIcon = {
                if (inputPrompt.isNotEmpty()) {
                    IconButton(
                        onClick = onSendText,
                        modifier = Modifier.testTag("send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        )
    }
}
