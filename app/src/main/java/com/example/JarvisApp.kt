package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.actions.ActionRegistry
import com.example.actions.SafetyGuard
import com.example.actions.UndoAction
import com.example.actions.UndoStack
import com.example.actions.impl.AccessibilityControlAction
import com.example.actions.impl.AgentModeAction
import com.example.actions.impl.AppLauncherAction
import com.example.actions.impl.BrowserControlAction
import com.example.actions.impl.CalendarActions
import com.example.actions.impl.ClipboardAssistantAction
import com.example.actions.impl.ContactsLookupAction
import com.example.actions.impl.CreateReminderAction
import com.example.actions.impl.DeviceHealthAction
import com.example.actions.impl.DoNotDisturbAction
import com.example.actions.impl.FileOperationsActions
import com.example.actions.impl.FlashlightControlAction
import com.example.actions.impl.MakePhoneCallAction
import com.example.actions.impl.MediaPlaybackControlAction
import com.example.actions.impl.MediaVideoAction
import com.example.actions.impl.NavigationAction
import com.example.actions.impl.NetworkPanelsAction
import com.example.actions.impl.RecallMemoryAction
import com.example.actions.impl.RememberFactAction
import com.example.actions.impl.SendMessagingAppAction
import com.example.actions.impl.SendSmsAction
import com.example.actions.impl.SetAlarmAction
import com.example.actions.impl.SetTimerAction
import com.example.actions.impl.VideoPlayerController
import com.example.actions.impl.VisionContextAction
import com.example.actions.impl.VolumeControlAction
import com.example.actions.impl.WeatherAction
import com.example.actions.impl.WebSearchAction
import com.example.core.config.AppPreferences
import com.example.core.config.SecureStorage
import com.example.core.gemini.AudioEngine
import com.example.core.gemini.GeminiClient
import com.example.core.gemini.GeminiLiveSession
import com.example.core.vision.VisionController
import com.example.memory.MemoryDatabase
import com.example.memory.MemoryRepository
import com.example.service.DailyBriefingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Who started the conversation; UI-started sessions end with the screen, the others do not. */
enum class SessionOrigin { UI, WAKE, TILE, OVERLAY }

class JarvisApp : Application() {

    @Volatile var sessionOrigin: SessionOrigin = SessionOrigin.UI


    lateinit var secureStorage: SecureStorage
    lateinit var appPreferences: AppPreferences
    lateinit var memoryRepository: MemoryRepository
    lateinit var undoStack: UndoStack
    lateinit var safetyGuard: SafetyGuard
    lateinit var actionRegistry: ActionRegistry
    lateinit var okHttpClient: OkHttpClient
    lateinit var geminiClient: GeminiClient
    lateinit var audioEngine: AudioEngine
    lateinit var liveSession: GeminiLiveSession
    lateinit var videoPlayerController: VideoPlayerController
    lateinit var visionController: VisionController

    override fun onCreate() {
        super.onCreate()

        // 1. Storage & Config
        secureStorage = SecureStorage(this)
        appPreferences = AppPreferences(this)

        // The Gemini API key is deliberately NOT injected here. It used to be seeded from
        // .env via a removed secrets plugin, which baked the key into the APK and silently
        // overrode the user's own setting. The user now supplies it on first launch and it
        // lives only in EncryptedSharedPreferences.

        // 2. Memory Database
        val memoryDb = MemoryDatabase.getInstance(this)
        memoryRepository = MemoryRepository(memoryDb.memoryDao())

        // 3. Safety & Undo
        undoStack = UndoStack()
        // Setting changes (volume, brightness, DND, flashlight) stay undoable across restarts.
        val undoFactory = com.example.actions.UndoFactory(this)
        undoStack.persistence = com.example.actions.PrefsUndoPersistence(this)
        undoStack.restore(undoStack.persistence!!.load(), undoFactory::rebuild)
        safetyGuard = SafetyGuard()

        // 4. Networking & AI Clients
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        geminiClient = GeminiClient(secureStorage, okHttpClient)
        audioEngine = AudioEngine(this)
        videoPlayerController = VideoPlayerController()
        visionController = VisionController(this)

        // 5. Actions Registry & Bundled Tools Auto-Registration
        actionRegistry = ActionRegistry(undoStack, safetyGuard)
        registerBundledActions()

        // 6. Gemini Live Session
        liveSession = GeminiLiveSession(secureStorage, actionRegistry, audioEngine, okHttpClient)

        // Vision: frames and source notes flow into the live session; ending the session
        // always switches the camera and screen sharing off.
        visionController.frameSink = { liveSession.sendVideoFrame(it) }
        visionController.noteSink = { liveSession.sendSourceNote(it) }
        liveSession.onSessionEnded = {
            visionController.stopAll()
            saveSessionRecap()
        }

        // 7. Notification Channels & Background Tasks
        createNotificationChannels()
        DailyBriefingWorker.scheduleDaily(this)

        // Restore the floating bubble if the user left it on.
        appScope.launch {
            if (appPreferences.settingsFlowOnce().overlayBubbleEnabled) {
                runCatching { com.example.service.JarvisOverlayService.start(this@JarvisApp) }
            }
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Summarises the finished conversation in two sentences and stores it as a one-shot recap
     * that the next morning briefing mentions and then consumes.
     */
    /**
     * Starts a conversation from anywhere (HUD, wake word, tile, bubble). The microphone service
     * is started first because Android only lets a foreground service keep the mic alive in the
     * background. Returns false when that service could not be started (no mic permission, or
     * Android refused a background start); no session is opened then.
     */
    fun startAssistantSession(origin: SessionOrigin): Boolean {
        if (liveSession.isWanted) return true
        if (!com.example.service.JarvisVoiceService.hostSession(this)) return false
        sessionOrigin = origin
        appScope.launch {
            val settings = appPreferences.settingsFlowOnce()
            audioEngine.setPreferredDevices(settings.preferredAudioInput, settings.preferredAudioOutput)
            val prompt = com.example.core.config.SystemPromptManager.buildPrompt(
                context = this@JarvisApp,
                assistantName = settings.assistantName,
                userName = settings.userName,
                capabilitiesSummary = actionRegistry.getCapabilitiesSummary(),
                limitsSummary = buildLimits(),
                activeMemoriesCompact = memoryRepository.getPromptIndex(),
                language = settings.language
            )
            liveSession.startSession(appScope, prompt, settings.voiceName)
        }
        return true
    }

    fun stopAssistantSession() = liveSession.endSession()

    /** Limits that reflect what this device and the user's grants allow right now. */
    private fun buildLimits(): String {
        fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(this, p) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val a11y = (getSystemService(ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager)
            ?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.resolveInfo.serviceInfo.packageName == packageName } == true
        return buildList {
            add("- Android 10+ blocks silent Wi-Fi toggling; you can only open the system panel, and you must say so.")
            add("- Sensitive operations (direct calls, SMS, file deletion) only run after the user taps Confirm on screen; the token is never available to you.")
            if (!a11y) add("- Screen automation (tap, scroll, type) is unavailable: the accessibility service is not enabled.")
            if (!granted(android.Manifest.permission.CAMERA)) add("- The camera is unavailable: permission not granted.")
            if (!android.provider.Settings.System.canWrite(this)) add("- Changing brightness needs 'Modify system settings' access, which is not granted.")
            if (!com.example.service.NotificationGate.canPost(this)) add("- Notifications are off, so reminders and alerts cannot be shown.")
            add("- You only see through inspect_visual_input; never describe what you have not been shown.")
        }.joinToString("\n")
    }

    private fun saveSessionRecap() {
        val lines = liveSession.activityLog.value
            .filter { it.type == "user" || it.type == "assistant" }
            .takeLast(30)
        if (lines.size < 4) return // too short to be worth a recap
        val transcript = lines.joinToString("\n") { "${it.type}: ${it.content}" }
        appScope.launch {
            val result = geminiClient.callOneShot(
                "Summarise this conversation between a user and their voice assistant in at most two " +
                    "short sentences, as a recap to mention the next morning. Plain text only.\n\n$transcript"
            )
            if (!result.isError && result.text.isNotBlank()) {
                memoryRepository.recordSessionRecap(result.text)
            }
        }
    }

    private fun registerBundledActions() {
        // App Launching
        actionRegistry.register(AppLauncherAction(this))

        // Device Controls
        actionRegistry.register(VolumeControlAction(this, undoStack))
        actionRegistry.register(FlashlightControlAction(this, undoStack))
        actionRegistry.register(BrightnessControlAction(this, undoStack))
        actionRegistry.register(NetworkPanelsAction(this))
        actionRegistry.register(MediaPlaybackControlAction(this))
        actionRegistry.register(DoNotDisturbAction(this, undoStack))

        // Communication
        actionRegistry.register(MakePhoneCallAction(this))
        actionRegistry.register(SendSmsAction(this))
        actionRegistry.register(SendMessagingAppAction(this))
        actionRegistry.register(ContactsLookupAction(this))

        // Alarms, Timers, Reminders
        actionRegistry.register(SetAlarmAction(this))
        actionRegistry.register(SetTimerAction(this))
        actionRegistry.register(CreateReminderAction(this, undoStack))

        // Calendar & Navigation
        actionRegistry.register(CalendarActions(this))
        actionRegistry.register(NavigationAction(this))

        // Weather & Web Search
        actionRegistry.register(WeatherAction(okHttpClient))
        actionRegistry.register(WebSearchAction(okHttpClient, geminiClient))
        actionRegistry.register(BrowserControlAction(this))

        // Clipboard & Files
        actionRegistry.register(ClipboardAssistantAction(this, geminiClient))
        actionRegistry.register(FileOperationsActions(this, geminiClient, undoStack))

        // Vision & Sensors
        actionRegistry.register(DeviceHealthAction(this))
        actionRegistry.register(VisionContextAction(this, visionController))

        // Accessibility & Media Video
        actionRegistry.register(AccessibilityControlAction())
        actionRegistry.register(MediaVideoAction(videoPlayerController))

        // Memory Actions
        actionRegistry.register(RememberFactAction(memoryRepository, undoStack))
        actionRegistry.register(RecallMemoryAction(memoryRepository))

        // Multi-step Agent Mode
        actionRegistry.register(AgentModeAction({ prompt -> geminiClient.callOneShot(prompt) }) { actionRegistry })

        // Undo (registered last so its description is not shadowed by a same-named tool)
        actionRegistry.register(UndoAction(undoStack))
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val voiceChannel = NotificationChannel(
                "jarvis_voice_service_channel",
                "JARVIS Foreground Listener",
                NotificationManager.IMPORTANCE_LOW
            )
            val reminderChannel = NotificationChannel(
                "jarvis_reminders_channel",
                "JARVIS Reminders & Alarms",
                NotificationManager.IMPORTANCE_HIGH
            )
            val briefingChannel = NotificationChannel(
                "jarvis_briefing_channel",
                "JARVIS Morning Briefing",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            manager.createNotificationChannel(voiceChannel)
            manager.createNotificationChannel(reminderChannel)
            manager.createNotificationChannel(briefingChannel)
        }
    }

    /** Restarts the morning briefing if boot-time recovery kicked in. */
    fun rescheduleDailyBriefing() {
        DailyBriefingWorker.scheduleDaily(this)
    }
