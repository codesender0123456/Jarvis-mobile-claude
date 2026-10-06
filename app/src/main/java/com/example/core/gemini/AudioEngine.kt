package com.example.core.gemini

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

data class AudioVisualState(
    val inputAmplitude: Float = 0f,
    val outputAmplitude: Float = 0f,
    val dominantFrequency: Float = 0f,
    val isSpeaking: Boolean = false,
    val isListening: Boolean = false
)

/**
 * Microphone capture (16 kHz mono PCM16, fixed 40 ms frames) and speaker playback (24 kHz mono
 * PCM16). Owns every AudioRecord/AudioTrack it creates and releases them on every exit path.
 */
class AudioEngine(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _visualState = MutableStateFlow(AudioVisualState())
    val visualState: StateFlow<AudioVisualState> = _visualState.asStateFlow()

    /** Last capture error, surfaced to the UI so a dead microphone is not silent. */
    @Volatile var lastRecordError: String? = null
        private set

    var onBargeInDetected: (() -> Unit)? = null

    @Volatile private var isRecording = false
    @Volatile private var isPlaying = false
    @Volatile private var recordJob: Job? = null
    @Volatile private var playbackJob: Job? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private val trackLock = Any()
    private val outputQueue = ConcurrentLinkedQueue<ByteArray>()

    @Volatile private var preferredInputLabel: String = DEFAULT_LABEL
    @Volatile private var preferredOutputLabel: String = DEFAULT_LABEL

    private val echoGate = EchoGate()
    @Volatile private var playUntilMs = 0L
    @Volatile private var lastOutputAmplitude = 0f
    @Volatile private var lastLevelPublishAt = 0L

    // ---- Devices ------------------------------------------------------------------------

    /** "Product name (Type)" labels for every audio device, plus a "Default Device" entry. */
    fun getAvailableAudioDevices(): List<String> {
        val list = mutableListOf(DEFAULT_LABEL)
        for (dev in audioManager.getDevices(AudioManager.GET_DEVICES_ALL)) list.add(labelFor(dev))
        return list.distinct()
    }

    private fun labelFor(dev: AudioDeviceInfo): String {
        val type = when (dev.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microphone"
            else -> "Audio Device"
        }
        return "${dev.productName} ($type)"
    }

    private fun findDevice(label: String, flag: Int): AudioDeviceInfo? {
        if (label.isBlank() || label.startsWith("Default")) return null
        return audioManager.getDevices(flag).firstOrNull { labelFor(it) == label }
    }

    /** Applies the saved input/output choice now (live) and to every recorder/track created later. */
    fun setPreferredDevices(inputLabel: String, outputLabel: String) {
        preferredInputLabel = inputLabel
        preferredOutputLabel = outputLabel
        audioRecord?.let { applyInputPreference(it) }
        synchronized(trackLock) { audioTrack?.let { applyOutputPreference(it) } }
    }

    private fun applyInputPreference(rec: AudioRecord) {
        val dev = findDevice(preferredInputLabel, AudioManager.GET_DEVICES_INPUTS)
        if (!rec.setPreferredDevice(dev) && dev != null) Log.w(TAG, "Input device '$preferredInputLabel' rejected")
    }

    private fun applyOutputPreference(track: AudioTrack) {
        val dev = findDevice(preferredOutputLabel, AudioManager.GET_DEVICES_OUTPUTS)
        if (!track.setPreferredDevice(dev) && dev != null) Log.w(TAG, "Output device '$preferredOutputLabel' rejected")
    }

    private fun headsetConnected(): Boolean = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    // ---- Capture ------------------------------------------------------------------------

    fun startRecording(scope: CoroutineScope, onAudioChunk: (ByteArray) -> Unit) {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            lastRecordError = "Microphone permission is not granted"
            Log.e(TAG, "Cannot start recording: RECORD_AUDIO not granted")
            return
        }
        val minBuffer = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            lastRecordError = "Device does not support ${IN_RATE}Hz mono PCM capture"
            return
        }
        val recorder = createRecorder(max(minBuffer * 2, 4096)) ?: return
        val effects = enableCaptureEffects(recorder)
        audioRecord = recorder
        isRecording = true
        lastRecordError = null

        recordJob = scope.launch(Dispatchers.IO) {
            val read = ByteArray(PcmChunker.DEFAULT_FRAME_BYTES)
            val silence = ByteArray(PcmChunker.DEFAULT_FRAME_BYTES)
            val chunker = PcmChunker { frame -> handleFrame(frame, silence, onAudioChunk) }
            try {
                while (isActive && isRecording) {
                    val n = try { recorder.read(read, 0, read.size) } catch (e: IllegalStateException) {
                        Log.w(TAG, "AudioRecord read failed, stopping capture", e); break
                    }
                    if (n > 0) chunker.push(read, n) else if (n < 0) { Log.w(TAG, "AudioRecord error $n"); break }
                }
            } finally {
                // Every exit path releases the hardware, including a self-terminating loop.
                isRecording = false
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                effects.forEach { runCatching { it.release() } }
                if (audioRecord === recorder) audioRecord = null
                _visualState.update { it.copy(inputAmplitude = 0f, isListening = false) }
            }
        }
    }

    private fun handleFrame(frame: ByteArray, silence: ByteArray, send: (ByteArray) -> Unit) {
        val amp = pcm16Rms(frame)
        publishInputLevel(amp)
        val now = SystemClock.elapsedRealtime()
        val outputActive = now < playUntilMs + ECHO_TAIL_MS
        when (echoGate.decide(now, amp, outputActive, lastOutputAmplitude, headsetConnected())) {
            MicDecision.FORWARD -> send(frame)
            MicDecision.SILENCE -> send(silence)
            MicDecision.BARGE_IN -> {
                interruptPlayback()
                onBargeInDetected?.invoke()
                send(frame)
            }
        }
    }

    /** VOICE_COMMUNICATION first: it is the source that engages the platform echo canceller. */
    private fun createRecorder(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            var candidate: AudioRecord? = null
            try {
                candidate = AudioRecord(source, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                if (candidate.state != AudioRecord.STATE_INITIALIZED) { candidate.release(); continue }
                applyInputPreference(candidate)
                candidate.startRecording()
                if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    runCatching { candidate.stop() }; candidate.release(); continue
                }
                Log.i(TAG, "Recording via AudioSource $source at ${IN_RATE}Hz")
                return candidate
            } catch (e: Exception) {
                Log.w(TAG, "AudioSource $source failed", e)
                candidate?.let { runCatching { it.release() } }
            }
        }
        lastRecordError = "Could not open the microphone on this device"
        return null
    }

    private fun enableCaptureEffects(rec: AudioRecord): List<AudioEffect> {
        val out = ArrayList<AudioEffect>()
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true; out += it }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true; out += it }
        }.onFailure { Log.w(TAG, "Could not enable AEC/NS", it) }
        return out
    }

    private fun publishInputLevel(amp: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLevelPublishAt < LEVEL_PUBLISH_INTERVAL_MS) return
        lastLevelPublishAt = now
        _visualState.update { it.copy(inputAmplitude = amp, isListening = true) }
    }

    fun stopRecording() {
        isRecording = false
        // stop() unblocks a pending read(); the capture job's finally block releases everything.
        runCatching { audioRecord?.stop() }
        recordJob?.cancel()
        recordJob = null
        _visualState.update { it.copy(inputAmplitude = 0f, isListening = false) }
    }

    // ---- Playback -----------------------------------------------------------------------

    fun queueAudioOutput(pcmChunk: ByteArray) {
        while (outputQueue.size >= MAX_QUEUED_CHUNKS) outputQueue.poll() ?: break
        outputQueue.offer(pcmChunk)
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = if (min <= 0) DEFAULT_BUFFER_BYTES else min * 2
        return AudioTrack.Builder()
            // Same "voice communication" usage as the capture source so the echo canceller has a
            // reference signal. Side effect: volume keys control call volume while speaking.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build().also { applyOutputPreference(it) }
    }

    private fun ensureTrack(): AudioTrack = synchronized(trackLock) {
        var t = audioTrack
        if (t == null || t.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { t?.release() }
            t = newTrack()
            audioTrack = t
        }
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        t
    }

    fun startPlayback(scope: CoroutineScope) {
        if (isPlaying) return
        isPlaying = true
        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && isPlaying) {
                    val chunk = outputQueue.poll()
                    if (chunk == null) {
                        _visualState.update { it.copy(outputAmplitude = 0f, isSpeaking = false) }
                        delay(20)
                        continue
                    }
                    val amp = pcm16Rms(chunk)
                    lastOutputAmplitude = amp
                    val now = SystemClock.elapsedRealtime()
                    playUntilMs = max(playUntilMs, now) + chunk.size / 2 * 1000L / OUT_RATE
                    val written = try { ensureTrack().write(chunk, 0, chunk.size) } catch (e: Exception) { -1 }
                    if (written < 0) {
                        Log.w(TAG, "AudioTrack write failed ($written), recreating track")
                        synchronized(trackLock) { runCatching { audioTrack?.release() }; audioTrack = null }
                    }
                    if (now - lastLevelPublishAt >= LEVEL_PUBLISH_INTERVAL_MS) {
                        lastLevelPublishAt = now
                        _visualState.update {
                            it.copy(outputAmplitude = amp, dominantFrequency = zeroCrossingFreq(chunk), isSpeaking = true)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback loop terminated", e)
            } finally {
                isPlaying = false
                synchronized(trackLock) { runCatching { audioTrack?.release() }; audioTrack = null }
            }
        }
    }

    /**
     * Drops audio that has not been spoken yet (barge-in / server `interrupted`) while keeping
     * the playback loop alive. AudioTrack.flush() is a no-op while the track is PLAYING, so it
     * has to be paused first; without that, buffered speech kept playing after an interruption.
     */
    fun interruptPlayback() {
        outputQueue.clear()
        playUntilMs = 0L
        lastOutputAmplitude = 0f
        _visualState.update { it.copy(outputAmplitude = 0f, isSpeaking = false) }
        synchronized(trackLock) {
            audioTrack?.let { t ->
                runCatching { t.pause(); t.flush(); t.play() }
                    .onFailure { Log.w(TAG, "Error flushing AudioTrack", it) }
            }
        }
    }

    fun stopPlayback() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        outputQueue.clear()
        playUntilMs = 0L
        lastOutputAmplitude = 0f
        _visualState.update { it.copy(outputAmplitude = 0f, isSpeaking = false) }
        synchronized(trackLock) { runCatching { audioTrack?.pause(); audioTrack?.flush() } }
    }

    fun release() {
        stopRecording()
        stopPlayback()
        synchronized(trackLock) { runCatching { audioTrack?.release() }; audioTrack = null }
    }

    private fun zeroCrossingFreq(bytes: ByteArray): Float {
        val n = bytes.size / 2
        if (n < 2) return 0f
        var crossings = 0
        var prev = (bytes[1].toInt() shl 8) or (bytes[0].toInt() and 0xFF)
        for (i in 1 until n) {
            val cur = (bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF)
            if ((prev >= 0 && cur < 0) || (prev < 0 && cur >= 0)) crossings++
            prev = cur
        }
        return (crossings / (2f * (n.toFloat() / OUT_RATE))).coerceIn(100f, 3500f)
    }

    private companion object {
        const val TAG = "AudioEngine"
        const val DEFAULT_LABEL = "Default Device"
        const val IN_RATE = 16_000
        const val OUT_RATE = 24_000
        const val DEFAULT_BUFFER_BYTES = 4096
        const val LEVEL_PUBLISH_INTERVAL_MS = 50L
        const val MAX_QUEUED_CHUNKS = 64
        /** Echo keeps arriving briefly after the last sample was written to the speaker. */
        const val ECHO_TAIL_MS = 200L
    }
}
