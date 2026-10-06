package com.example

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.ui.components.ApiKeyDialog
import com.example.ui.screens.AboutScreen
import com.example.ui.screens.HudScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.theme.JarvisTheme
import com.example.ui.viewmodel.JarvisViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    private val vision get() = (application as JarvisApp).visionController

    // Result of the system "start recording or casting?" prompt. Nothing is captured unless
    // the user taps Start in that dialog.
    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                vision.onScreenCaptureGranted(result.resultCode, data)
            } else {
                vision.onScreenCaptureDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // CameraX binds to this activity; screen capture needs it to show the system prompt.
        vision.lifecycleOwner = this
        vision.screenCaptureRequester = {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                runOnUiThread {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                }
                true
            } else false
        }

        handleLaunchIntent(intent)

        setContent {
            val userSettings by viewModel.userSettings.collectAsState()
            val currentScreen by viewModel.currentScreen.collectAsState()
            val apiKeyConfigured by viewModel.apiKeyConfigured.collectAsState()
            val savingApiKey by viewModel.savingApiKey.collectAsState()
            val apiKeyPostponed by viewModel.apiKeyPromptPostponed.collectAsState()
            val apiKeyStatusLoaded by viewModel.apiKeyStatusLoaded.collectAsState()

            LaunchedEffect(Unit) {
                viewModel.refreshApiKeyStatus()
            }

            val showApiKeyPrompt = apiKeyStatusLoaded &&
                !apiKeyConfigured &&
                !(apiKeyPostponed && userSettings.hasCompletedOnboarding)

            JarvisTheme(primaryHue = userSettings.themeHue) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .safeDrawingPadding()
                ) {
                    when (currentScreen) {
                        "onboarding" -> {
                            OnboardingScreen(
                                onComplete = { viewModel.completeOnboarding() }
                            )
                        }
                        "memory" -> {
                            val memories by viewModel.memories.collectAsState()
                            BackHandler { viewModel.navigateTo("hud") }
                            MemoryScreen(
                                memories = memories,
                                onBack = { viewModel.navigateTo("hud") },
                                onDeleteMemory = { viewModel.deleteMemory(it) },
                                onAddMemory = { key, fact, cat -> viewModel.addMemory(key, fact, cat) }
                            )
                        }
                        "about" -> {
                            BackHandler { viewModel.navigateTo("hud") }
                            AboutScreen(
                                onBack = { viewModel.navigateTo("hud") }
                            )
                        }
                        else -> {
                            HudScreen(
                                viewModel = viewModel,
                                onNavigateToMemory = { viewModel.navigateTo("memory") },
                                onNavigateToAbout = { viewModel.navigateTo("about") },
                                onOpenPermissions = { viewModel.navigateTo("onboarding") }
                            )
                        }
                    }

                    if (showApiKeyPrompt) {
                        ApiKeyDialog(
                            isFirstRun = !userSettings.hasCompletedOnboarding,
                            isSaving = savingApiKey,
                            onSubmit = { viewModel.saveApiKey(it) },
                            onSkip = { viewModel.postponeApiKeyPrompt() }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    /** Launch extras from the tile, bubble, wake service and the boot notification. */
    private fun handleLaunchIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_WAKE_SERVICE, false) == true) {
            com.example.service.JarvisVoiceService.start(this)
        }
        if (intent?.getBooleanExtra(EXTRA_START_LISTENING, false) == true) {
            viewModel.connectLiveSession()
        }
    }

    override fun onDestroy() {
        if (vision.lifecycleOwner === this) {
            vision.stopCamera() // the camera is bound to this activity and ends with it
            vision.lifecycleOwner = null
            vision.screenCaptureRequester = null
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_START_LISTENING = "EXTRA_START_LISTENING"
        const val EXTRA_START_WAKE_SERVICE = "EXTRA_START_WAKE_SERVICE"
    }
}
