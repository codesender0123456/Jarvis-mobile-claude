package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.JarvisApp
import com.example.actions.PendingConfirmation
import com.example.core.config.SystemPromptManager
import com.example.core.config.UserSettings
import com.example.core.gemini.AudioVisualState
import com.example.core.gemini.LiveActivityEvent
import com.example.core.gemini.LiveSessionState
import com.example.memory.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JarvisApp
    private val liveSession = app.liveSession
    private val audioEngine = app.audioEngine
    private val appPreferences = app.appPreferences
    private val memoryRepo = app.memoryRepository
    private val undoStack = app.undoStack
    private val safetyGuard = app.safetyGuard
    private val actionRegistry = app.actionRegistry
    private val geminiClient = app.geminiClient

    val sessionState: StateFlow<LiveSessionState> = liveSession.sessionState
    val activityLog: StateFlow<List<LiveActivityEvent>> = liveSession.activityLog
    val visualState: StateFlow<AudioVisualState> = audioEngine.visualState

    val userSettings: StateFlow<UserSettings> = appPreferences.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val pendingConfirmation: StateFlow<PendingConfirmation?> = safetyGuard.pendingRequest
    val canUndo: StateFlow<Boolean> = undoStack.canUndo
    val lastActionDesc: StateFlow<String?> = undoStack.lastActionDesc

    val memories: StateFlow<List<MemoryEntity>> = memoryRepo.allMemoriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentScreen = MutableStateFlow("hud") // "hud", "memory", "about", "onboarding"
    val currentScreen: StateFlow<String> = _currentScreen.asStateFlow()

    // Whether a Gemini key is stored, never the key itself.
    private val _apiKeyConfigured = MutableStateFlow(false)
    val apiKeyConfigured: StateFlow<Boolean> = _apiKeyConfigured.asStateFlow()

    // False until the first read out of encrypted storage completes. Without it the UI would
    // briefly treat "not loaded yet" as "no key" and flash the API key dialog on every launch.
    private val _apiKeyStatusLoaded = MutableStateFlow(false)
    val apiKeyStatusLoaded: StateFlow<Boolean> = _apiKeyStatusLoaded.asStateFlow()

    // True while the key is being written to encrypted storage.
    private val _savingApiKey = MutableStateFlow(false)
    val savingApiKey: StateFlow<Boolean> = _savingApiKey.asStateFlow()

    // Set when the user explicitly postpones entering a key, so the dialog stops nagging
    // within this process but still returns on the next cold start.
    private val _apiKeyPromptPostponed = MutableStateFlow(false)
    val apiKeyPromptPostponed: StateFlow<Boolean> = _apiKeyPromptPostponed.asStateFlow()

    private val _audioDevices = MutableStateFlow<List<String>>(emptyList())
    val audioDevices: StateFlow<List<String>> = _audioDevices.asStateFlow()

    private val _storageEncrypted = MutableStateFlow(true)
    val storageEncrypted: StateFlow<Boolean> = _storageEncrypted.asStateFlow()

    init {
        refreshApiKeyStatus()
        viewModelScope.launch(Dispatchers.IO) {
            _audioDevices.value = audioEngine.getAvailableAudioDevices()
            _storageEncrypted.value = app.secureStorage.isEncrypted()
        }
        viewModelScope.launch {
            // Only route to onboarding once, when the flag is first seen as false. Collecting
            // every emission and forcing the screen hijacked navigation on unrelated
            // preference changes (e.g. while the user was on the About screen).
            var evaluated = false
            appPreferences.settingsFlow.collect { settings ->
                if (!evaluated) {
                    evaluated = true
                    if (!settings.hasCompletedOnboarding) {
                        _currentScreen.value = "onboarding"
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // A conversation the user started from this screen ends with it. Sessions started from
        // the wake word, tile or bubble are hosted by the foreground service and keep running.
        if (liveSession.isWanted && app.sessionOrigin == com.example.SessionOrigin.UI) {
            liveSession.endSession()
        }
    }

    fun navigateTo(screen: String) {
        _currentScreen.value = screen
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            appPreferences.setOnboardingCompleted(true)
            _currentScreen.value = "hud"
        }
    }

    /** One source of truth: whether a session is wanted, not a snapshot of the socket state. */
    fun toggleVoiceConnection() {
        if (liveSession.isWanted) app.stopAssistantSession() else connectLiveSession()
    }

    fun connectLiveSession() {
        if (!app.startAssistantSession(com.example.SessionOrigin.UI)) {
            liveSession.addLog("error", "Could not start the microphone service. Check the microphone permission.")
        }
    }

    fun sendUserText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        if (liveSession.isWanted && sessionState.value != LiveSessionState.DISCONNECTED &&
            sessionState.value != LiveSessionState.CONNECTING && sessionState.value != LiveSessionState.ERROR) {
            liveSession.sendTextMessage(clean)
        } else {
            // One-shot execution
            liveSession.addLog("user", clean)
            viewModelScope.launch {
                liveSession.addLog("system", "Querying neural core ladder...")
                val result = geminiClient.callOneShot(clean)
                // A ladder failure must not be logged as if JARVIS had answered.
                liveSession.addLog(
                    if (result.isError) "error" else "assistant",
                    result.text
                )
            }
        }
    }

    fun approveConfirmation(token: String) {
        viewModelScope.launch {
            val result = safetyGuard.approve(token)
            liveSession.addLog("system", result)
        }
    }

    fun rejectConfirmation() {
        safetyGuard.reject()
        liveSession.addLog("system", "Action authorization declined by user.")
    }

    fun triggerUndo() {
        viewModelScope.launch {
            val result = undoStack.undoLast()
            liveSession.addLog("system", result)
        }
    }

    fun saveApiKey(key: String) {
        // commit() on EncryptedSharedPreferences is blocking I/O plus a keystore round trip, so
        // it must never be driven straight from onValueChange.
        viewModelScope.launch(Dispatchers.IO) {
            _savingApiKey.value = true
            try {
                app.secureStorage.setApiKey(key)
                _apiKeyConfigured.value = key.isNotBlank()
                _apiKeyPromptPostponed.value = false
                if (key.isNotBlank()) {
                    liveSession.addLog("system", "Gemini API key saved securely on this device.")
                }
            } catch (e: Exception) {
                Log.e("JarvisViewModel", "Failed to store API key", e)
                liveSession.addLog("error", "Could not save that key.")
            } finally {
                _savingApiKey.value = false
            }
        }
    }

    fun postponeApiKeyPrompt() {
        _apiKeyPromptPostponed.value = true
    }

    /**
     * Deliberately does not expose the stored key. Handing the plaintext secret to Compose kept
     * it in the composition's state on every recomposition; the drawer only needs to know
     * whether one is configured.
     */
    fun refreshApiKeyStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _apiKeyConfigured.value = app.secureStorage.hasApiKey()
            _apiKeyStatusLoaded.value = true
        }
    }

    fun updateLanguage(language: String) {
        viewModelScope.launch { appPreferences.updateLanguage(language) }
    }

    fun setOverlayBubble(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !android.provider.Settings.canDrawOverlays(app)) {
                liveSession.addLog("error", "Allow 'display over other apps' first (Permissions screen).")
                return@launch
            }
            appPreferences.setOverlayBubbleEnabled(enabled)
            if (enabled) com.example.service.JarvisOverlayService.start(app)
            else com.example.service.JarvisOverlayService.stop(app)
        }
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setAutoStartOnBoot(enabled) }
    }

    /** One device list serves both directions: a headset is both a mic and a speaker. */
    fun selectAudioInput(device: String) {
        viewModelScope.launch {
            appPreferences.setAudioInput(device)
            appPreferences.setAudioOutput(device)
            audioEngine.setPreferredDevices(device, device) // applies live as well
        }
    }

    fun selectAudioOutput(device: String) {
        viewModelScope.launch { appPreferences.setAudioOutput(device) }
    }

    fun updateAssistantName(name: String) {
        viewModelScope.launch { appPreferences.updateAssistantName(name) }
    }

    fun updateUserName(name: String) {
        viewModelScope.launch { appPreferences.updateUserName(name) }
    }

    fun selectVoice(voice: String) {
        viewModelScope.launch {
            appPreferences.updateVoiceName(voice)
            // Reconnects with the stored resumption handle, so the conversation continues.
            liveSession.changeVoice(voice)
        }
    }

    fun updateHue(hue: Float) {
        viewModelScope.launch { appPreferences.updateThemeHue(hue) }
    }

    fun toggleWakeWord(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setWakeWordEnabled(enabled)
            if (enabled) {
                if (!com.example.service.JarvisVoiceService.start(app)) {
                    liveSession.addLog("error", "Wake word needs the microphone permission.")
                }
            } else if (!liveSession.isWanted) {
                com.example.service.JarvisVoiceService.stop(app)
            }
        }
    }

    fun addMemory(key: String, fact: String, category: String) {
        viewModelScope.launch {
            // A Room failure inside viewModelScope's SupervisorJob would otherwise surface as an
            // uncaught exception with no user feedback.
            runCatching { memoryRepo.rememberFact(key, fact, category, isCore = true) }
                .onSuccess { liveSession.sendSourceNote("[Memory updated: $key = $fact. Do not reply.]") }
                .onFailure {
                    Log.e("JarvisViewModel", "Failed to store memory '$key'", it)
                    liveSession.addLog("error", "Could not store that fact.")
                }
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            runCatching { memoryRepo.deleteMemory(id) }
                .onSuccess { liveSession.sendSourceNote("[A memory entry was deleted by the user; forget it. Do not reply.]") }
                .onFailure {
                    Log.e("JarvisViewModel", "Failed to delete memory $id", it)
                    liveSession.addLog("error", "Could not delete that fact.")
                }
        }
    }

    // Device enumeration used to be reachable synchronously from Compose, which ran the
    // AudioManager query on the main thread on every recomposition. It is exposed only as the
    // audioDevices StateFlow, populated once from an IO dispatcher.
}