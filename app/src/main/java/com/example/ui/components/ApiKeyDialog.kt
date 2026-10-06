package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Blocking prompt for the Gemini API key.
 *
 * Shown on first launch once permissions are granted, and again on any later launch where no
 * key is stored, because every AI feature in the app is dead without one. The user cannot
 * dismiss it without making a choice, so the app never sits in a broken state.
 */
@Composable
fun ApiKeyDialog(
    isFirstRun: Boolean,
    isSaving: Boolean,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit
) {
    var key by rememberSaveable { mutableStateOf("") }
    val trimmed = key.trim()
    val canSubmit = trimmed.isNotEmpty() && !isSaving

    AlertDialog(
        onDismissRequest = { /* Deliberately modal: the app cannot work without a key. */ },
        title = {
            Text(
                text = if (isFirstRun) "AUTHORIZE NEURAL CORE" else "API KEY REQUIRED",
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column {
                Text(
                    text = if (isFirstRun) {
                        "Subsystems are online. JARVIS needs your Gemini API key to access the " +
                            "neural network. It is stored only on this device, encrypted, and is " +
                            "never included in the app package."
                    } else {
                        "No Gemini API key is configured, so voice and chat are disabled. " +
                            "Paste your key to continue. Create one free at " +
                            "aistudio.google.com/apikey"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = key,
                    onValueChange = { newKey: String -> key = newKey },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    singleLine = true,
                    enabled = !isSaving,
                    label = { Text("Gemini API Key", fontSize = 12.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    textStyle = FontFamily.Monospace
                )

                if (isSaving) {
                    Text(
                        text = "Securing key...",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(trimmed) },
                enabled = canSubmit,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color(0xFF001F26)
                )
            ) {
                Text("SAVE KEY", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            if (!isFirstRun) {
                TextButton(onClick = onSkip) {
                    Text("Later", fontSize = 13.sp)
                }
            }
        }
    )
}
