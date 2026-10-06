package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.config.UserSettings
import com.example.core.gemini.VoiceCatalog

@Composable
fun SettingsDrawerContent(
    isApiKeyConfigured: Boolean,
    isStorageEncrypted: Boolean,
    settings: UserSettings,
    availableDevices: List<String>,
    onSaveApiKey: (String) -> Unit,
    onUpdateAssistantName: (String) -> Unit,
    onUpdateUserName: (String) -> Unit,
    onSelectVoice: (String) -> Unit,
    onUpdateHue: (Float) -> Unit,
    onUpdateLanguage: (String) -> Unit,
    onToggleWakeWord: (Boolean) -> Unit,
    onToggleAutoStart: (Boolean) -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onSelectAudioDevice: (String) -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The stored key is never loaded into the composition. The field starts empty and only
    // commits on the explicit Save action, so a keystroke no longer triggers an encrypted
    // SharedPreferences write and the secret is not held in composition state.
    var apiKeyText by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    var assistantNameText by remember(settings.assistantName) { mutableStateOf(settings.assistantName) }
    var userNameText by remember(settings.userName) { mutableStateOf(settings.userName) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(340.dp)
            .background(Color(0xFF0A0F1A))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "SYSTEM CONFIGURATION",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // API Key Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Gemini API Key",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (isStorageEncrypted) "Keystore encrypted" else "NOT ENCRYPTED",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (isStorageEncrypted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color(0xFFFFB300)
                }
            )
        }
        OutlinedTextField(
            value = apiKeyText,
            onValueChange = { apiKeyText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            singleLine = true,
            label = {
                Text(
                    if (isApiKeyConfigured) {
                        "Configured - type to replace"
                    } else {
                        "Paste key"
                    },
                    fontSize = 11.sp
                )
            },
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle key visibility"
                    )
                }
            }
        )
        Button(
            onClick = { onSaveApiKey(apiKeyText) },
            enabled = apiKeyText.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text("Save Key", fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Names
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Assistant Name", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = assistantNameText,
                    onValueChange = {
                        assistantNameText = it
                        onUpdateAssistantName(it)
                    },
                    singleLine = true,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("User Address", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = userNameText,
                    onValueChange = {
                        userNameText = it
                        onUpdateUserName(it)
                    },
                    singleLine = true,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Voice Selector
        Text(
            text = "Gemini Live Voice Profile",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Column(
            modifier = Modifier.padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (voice in VoiceCatalog.VOICES) {
                val isSelected = voice.id.equals(settings.voiceName, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color(0xFF111A2C))
                        .border(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { onSelectVoice(voice.id) }
                        .padding(10.dp)
                ) {
                    Column {
                        Text(
                            text = voice.displayName,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = voice.description,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Theme Hue Wheel Slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("HUD Hologram Hue", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text("${settings.themeHue.toInt()}°", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = settings.themeHue,
            onValueChange = { onUpdateHue(it) },
            valueRange = 0f..360f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Wake Word Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Wake Word 'Hey Jarvis'", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Text("Continuous microphone foreground service", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = settings.wakeWordEnabled,
                onCheckedChange = { onToggleWakeWord(it) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Language
        Text(
            text = "Conversation Language",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Column(
            modifier = Modifier.padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (language in SUPPORTED_LANGUAGES) {
                OptionRow(
                    title = language,
                    isSelected = language == settings.language,
                    onClick = { onUpdateLanguage(language) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Audio routing. These were previously passed in as no-op lambdas, so the setting
        // existed in the ViewModel and DataStore but could never be changed from the UI.
        if (availableDevices.isNotEmpty()) {
            Text(
                text = "Audio Input Device",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Column(
                modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (device in availableDevices) {
                    OptionRow(
                        title = device,
                        isSelected = device == settings.preferredAudioInput,
                        onClick = { onSelectAudioDevice(device) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Floating bubble
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Floating Bubble", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Text("Shows live status; tap to talk, long-press for the HUD", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = settings.overlayBubbleEnabled,
                onCheckedChange = { onToggleOverlay(it) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Auto-start on boot
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Start Listener On Boot", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Text("Wake-word listener after reboot (Android 14+ asks you to tap a notification first)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = settings.autoStartOnBoot,
                onCheckedChange = { onToggleAutoStart(it) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Navigation Actions
        DrawerNavButton(
            icon = Icons.Default.Memory,
            title = "Neural Memory Vault",
            subtitle = "View and manage persistent local memories",
            onClick = onNavigateToMemory
        )

        Spacer(modifier = Modifier.height(8.dp))

        DrawerNavButton(
            icon = Icons.Default.Lock,
            title = "Permissions & System Access",
            subtitle = "Camera, Audio, Accessibility, SAF",
            onClick = onOpenPermissions
        )

        Spacer(modifier = Modifier.height(8.dp))

        DrawerNavButton(
            icon = Icons.Default.Info,
            title = "About & Credits",
            subtitle = "Mark LV port, CC BY-NC 4.0 license",
            onClick = onNavigateToAbout
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private val SUPPORTED_LANGUAGES = listOf("Auto", "English", "Hindi", "Spanish", "French", "German")

@Composable
private fun OptionRow(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color(0xFF111A2C))
            .border(
                1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun DrawerNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF131D30))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp)
            )
            Column {
                Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(text = subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
