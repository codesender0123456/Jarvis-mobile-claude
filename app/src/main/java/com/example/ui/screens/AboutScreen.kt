package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "ABOUT JARVIS MOBILE",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Origin & Attribution Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0F1828))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "ORIGINAL DESKTOP SYSTEM CREDIT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Standalone mobile port of the desktop assistant 'Mark LV' created by FatihMakes.",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        text = "Licensed under Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0). This mobile port is strictly personal, educational, and non-commercial.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Core Technical Specifications
            Text(
                text = "SYSTEM ARCHITECTURE & CAPABILITIES",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            SpecItem(
                title = "Gemini Live Real-Time Voice Loop",
                description = "Bidirectional WebSocket connection streaming 16 kHz mono PCM from AudioRecord and playing 24 kHz PCM through low-latency AudioTrack. Includes natural barge-in detection, session resumption handle persistence, and self-echo suppression."
            )

            SpecItem(
                title = "Measured Model Ladder & Cooldowns",
                description = "One-shot tasks execute through a priority ladder (gemini-3.1-flash-lite, 2.5-flash-lite, 2.5-flash, 3.5-flash) with 10s+ timeouts and per-model cooldowns: 5m for 429, 30m for 503/504, 6h for 404."
            )

            SpecItem(
                title = "Local-Only Persistent Memory (Room)",
                description = "Full offline Room database keeping core identity facts, user preferences, project statuses, and morning session recaps with zero cloud telemetry or leakage."
            )

            SpecItem(
                title = "Cryptographic Safety Guard & Global Undo",
                description = "Reversible operations push to a shared undo stack ('undo' reverses last action). Irreversible actions (calls, SMS, deletion) require direct physical authorization with one-time UI tokens."
            )

            SpecItem(
                title = "Universal System Integration",
                description = "Controls installed apps, volume, brightness, flashlight, DND, media, calendar, alarms/reminders via WorkManager, navigation, live weather, camera vision, and Android Accessibility gestures."
            )
        }
    }
}

@Composable
fun SpecItem(title: String, description: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0D1422))
            .padding(12.dp)
    ) {
        Column {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
