package com.example.core.gemini

import android.util.Log
import com.example.actions.ActionRegistry
import com.example.actions.ActionResult
import com.example.core.config.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

enum class LiveSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

data class LiveActivityEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String, // "user", "assistant", "tool", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val cardData: Map<String, Any?>? = null
)

/**
 * Gemini Live session. The wire format lives in [LiveProtocol]; this class owns the socket, the
 * reconnect policy and the audio hand-off.
 *
 * Lifecycle: [startSession] -> socket opens -> setup sent -> server answers `setupComplete`
 * -> audio streaming starts. Any drop reconnects with exponential backoff and replays the
 * server's resumption handle so the conversation continues. [endSession] is the only call that
 * discards the handle.
 */
class GeminiLiveSession(
    private val secureStorage: SecureStorage,
    private val actionRegistry: ActionRegistry,
    private val audioEngine: AudioEngine,
    private val okHttpClient: OkHttpClient
) {
    private val ladder = LiveModelLadder()

    private var webSocket: WebSocket? = null
    private val _sessionState = MutableStateFlow(LiveSessionState.DISCONNECTED)
    val sessionState: StateFlow<LiveSessionState> = _sessionState.asStateFlow()

    private val _activityLog = MutableStateFlow<List<LiveActivityEvent>>(emptyList())
    val activityLog: StateFlow<List<LiveActivityEvent>> = _activityLog.asStateFlow()

    /** Most recent specific failure (close code + redacted reason), for the UI and bug reports. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var coroutineScope: CoroutineScope? = null
    private var reconnectJob: Job? = null

    private var sessionHandle: String? = secureStorage.getSessionHandle()
    private var systemInstruction: String = ""
    private var voiceName: String = DEFAULT_VOICE

    @Volatile private var wantConnected = false
    @Volatile private var ready = false
    @Volatile private var generation = 0
    private var attempt = 0

    /** Invoked when the session ends or fails fatally (stops camera/screen sharing, saves recap). */
    @Volatile var onSessionEnded: (() -> Unit)? = null

    private val inBuf = StringBuilder()
    private val outBuf = StringBuilder()

    private val wsClient = okHttpClient.newBuilder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val isWanted: Boolean get() = wantConnected

    init {
        audioEngine.onBargeInDetected = { handleBargeIn() }
    }

    fun startSession(
        scope: CoroutineScope,
        systemInstruction: String,
        voiceName: String = DEFAULT_VOICE,
        resumeSession: Boolean = false
    ) {
        coroutineScope = scope
        this.systemInstruction = systemInstruction
        this.voiceName = voiceName

        if (secureStorage.getApiKey().isEmpty()) {
            _sessionState.value = LiveSessionState.ERROR
            addLog("system", "Gemini API key missing. Add it in settings.")
            return
        }
        if (!resumeSession) {
            sessionHandle = null
            secureStorage.clearSessionHandle()
        }
        wantConnected = true
        attempt = 0
        reconnectJob?.cancel()
        _lastError.value = null
        connect()
    }

    /** Switches voice mid-conversation by reconnecting with the stored resumption handle. */
    fun changeVoice(newVoice: String) {
        if (newVoice == voiceName) return
        voiceName = newVoice
        if (!wantConnected) return
        addLog("system", "Switching voice to $newVoice...")
        generation++
        ready = false
        runCatching { webSocket?.close(1000, "Voice change") }
        webSocket = null
        attempt = 0
        reconnectJob?.cancel()
        connect()
    }

    private fun connect() {
        val apiKey = secureStorage.getApiKey()
        if (apiKey.isEmpty()) {
            fail("Gemini API key missing. Add it in settings.")
            return
        }
        val model = ladder.current()
        val gen = ++generation
        ready = false
        _sessionState.value = LiveSessionState.CONNECTING

        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).header("x-goog-api-key", apiKey).build()

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                _sessionState.value = LiveSessionState.CONNECTED
                webSocket.send(
                    LiveProtocol.buildSetup(
                        model, voiceName, systemInstruction, actionRegistry.buildGeminiToolsJson(), sessionHandle
                    ).toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation) handleFrame(text, gen)
            }

            // Gemini Live often delivers its JSON as binary frames.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen == generation) handleFrame(bytes.utf8(), gen)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                handleClosed(gen, model, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleClosed(gen, model, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleClosed(gen, model, response?.code ?: -1, "${t.javaClass.simpleName}: ${t.message.orEmpty()} ${response?.message.orEmpty()}")
            }
        })
    }

    private fun handleClosed(gen: Int, model: String, code: Int, rawReason: String) {
        synchronized(this) {
            if (gen != generation) return
            generation++ // dedupe onClosing + onClosed + onFailure for one socket
        }
        val wasReady = ready
        ready = false
        webSocket = null
        flushTranscripts()
        val reason = LiveProtocol.redact(rawReason, secureStorage.getApiKey()).take(300)
        Log.w(TAG, "Live socket closed: code=$code reason=$reason model=$model")

        if (!wantConnected) {
            _sessionState.value = LiveSessionState.DISCONNECTED
            return
        }
        if (code != 1000) {
            _lastError.value = "Live connection closed (code $code): ${reason.ifBlank { "no reason given" }} [model $model]"
            addLog("error", _lastError.value!!)
        }

        when (LiveProtocol.classifyClose(code, reason)) {
            CloseKind.FATAL_KEY -> {
                fail("Gemini rejected the API key. Check it in settings.")
                return
            }
            CloseKind.QUOTA -> if (ladder.markFailure(model, 429)) {
                addLog("system", "Quota reached on $model. Switching to ${ladder.current()}.")
            }
            CloseKind.MODEL_UNAVAILABLE -> if (ladder.markFailure(model, 404)) {
                addLog("system", "$model is unavailable to this key. Switching to ${ladder.current()}.")
            }
            CloseKind.TRANSIENT -> if (!wasReady && sessionHandle != null) {
                // Died before setupComplete while resuming: the handle is probably stale.
                sessionHandle = null
                secureStorage.clearSessionHandle()
            }
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        attempt++
        val delayMs = min(30_000L, 1_000L shl min(attempt - 1, 5)) + Random.nextLong(0, 500)
        _sessionState.value = LiveSessionState.CONNECTING
        addLog("system", "Connection lost. Reconnecting in ${delayMs / 1000}s...")
        reconnectJob = reconnectScope.launch {
            delay(delayMs)
            if (wantConnected) connect()
        }
    }

    private fun fail(message: String) {
        wantConnected = false
        reconnectJob?.cancel()
        audioEngine.stopRecording()
        audioEngine.stopPlayback()
        _lastError.value = message
        _sessionState.value = LiveSessionState.ERROR
        addLog("system", message)
        onSessionEnded?.invoke()
    }

    // ---- Outgoing -----------------------------------------------------------------------

    fun sendRealtimeAudio(pcm16k: ByteArray) {
        if (ready) webSocket?.send(LiveProtocol.audioFrame(pcm16k))
    }

    fun sendVideoFrame(jpeg: ByteArray) {
        if (ready) webSocket?.send(LiveProtocol.videoFrame(jpeg))
    }

    /** Bracketed system note for the model (vision source changes, memory edits). */
    fun sendSourceNote(note: String) {
        if (!ready) return
        addLog("system", note.trim('[', ']'))
        webSocket?.send(LiveProtocol.text(note))
    }

    fun sendTextMessage(text: String) {
        addLog("user", text)
        if (!ready) {
            addLog("system", "Not connected yet; that message was not sent.")
            return
        }
        webSocket?.send(LiveProtocol.text(text))
    }

    // ---- Incoming -----------------------------------------------------------------------

    private fun handleFrame(raw: String, gen: Int) {
        try {
            for (e in LiveProtocol.parse(raw)) handleEvent(e, gen)
        } catch (t: Exception) {
            Log.e(TAG, "Error handling server frame", t)
        }
    }

    private fun handleEvent(e: LiveServerEvent, gen: Int) {
        when (e) {
            LiveServerEvent.SetupComplete -> onSetupComplete()
            is LiveServerEvent.Resumption -> {
                sessionHandle = e.handle
                secureStorage.setSessionHandle(e.handle)
            }
            LiveServerEvent.GoAway -> Log.d(TAG, "goAway received; the reconnect will resume the session")
            is LiveServerEvent.Audio -> {
                _sessionState.value = LiveSessionState.SPEAKING
                audioEngine.queueAudioOutput(e.pcm24k)
            }
            is LiveServerEvent.InputText -> inBuf.append(e.text)
            is LiveServerEvent.OutputText -> outBuf.append(e.text)
            LiveServerEvent.Interrupted -> {
                audioEngine.interruptPlayback()
                flushTranscripts()
                _sessionState.value = LiveSessionState.LISTENING
            }
            LiveServerEvent.TurnComplete -> {
                flushTranscripts()
                _sessionState.value = LiveSessionState.LISTENING
            }
            is LiveServerEvent.ToolCall -> dispatchToolCall(e, gen)
        }
    }

    private fun onSetupComplete() {
        ready = true
        attempt = 0
        _lastError.value = null
        _sessionState.value = LiveSessionState.LISTENING
        addLog("system", "Neural core connected [${ladder.current()}, voice: $voiceName]")
        coroutineScope?.let { scope ->
            // Audio starts only now, never in onOpen. Both calls are idempotent across reconnects.
            audioEngine.startRecording(scope) { sendRealtimeAudio(it) }
            audioEngine.startPlayback(scope)
        }
    }

    private fun dispatchToolCall(call: LiveServerEvent.ToolCall, gen: Int) {
        _sessionState.value = LiveSessionState.THINKING
        flushTranscripts()
        // Concurrent: the assistant keeps talking while the tool runs.
        (coroutineScope ?: reconnectScope).launch(Dispatchers.IO) {
            addLog("tool", "Executing ${call.name}...")
            val result: ActionResult = actionRegistry.executeAction(call.name, call.args)
            addLog("tool", "Completed ${call.name}: ${result.spokenResult}", result.cardData)
            // A call id is only valid on the socket that issued it.
            if (ready && gen == generation) {
                webSocket?.send(LiveProtocol.toolResponse(call.id, call.name, result.spokenResult))
            } else {
                Log.w(TAG, "Dropping tool response for ${call.name}: session changed")
            }
        }
    }

    /** Local barge-in: silence our own speaker at once; the server's VAD confirms with `interrupted`. */
    private fun handleBargeIn() {
        audioEngine.interruptPlayback()
        _sessionState.value = LiveSessionState.LISTENING
    }

    private fun flushTranscripts() {
        if (inBuf.isNotBlank()) addLog("user", inBuf.toString().trim())
        if (outBuf.isNotBlank()) addLog("assistant", outBuf.toString().trim())
        inBuf.clear()
        outBuf.clear()
    }

    fun addLog(type: String, content: String, cardData: Map<String, Any?>? = null) {
        val current = _activityLog.value.toMutableList()
        current.add(LiveActivityEvent(type = type, content = content, cardData = cardData))
        if (current.size > 100) current.removeAt(0)
        _activityLog.value = current
    }

    fun endSession() {
        val wasActive = wantConnected
        wantConnected = false
        generation++
        ready = false
        reconnectJob?.cancel()
        reconnectJob = null
        audioEngine.stopRecording()
        audioEngine.stopPlayback()
        sessionHandle = null
        secureStorage.clearSessionHandle()
        runCatching { webSocket?.close(1000, "User ended session") }
        webSocket = null
        flushTranscripts()
        _sessionState.value = LiveSessionState.DISCONNECTED
        if (wasActive) onSessionEnded?.invoke()
    }

    companion object {
        const val DEFAULT_VOICE = "Puck"
        private const val TAG = "GeminiLiveSession"
    }
}
