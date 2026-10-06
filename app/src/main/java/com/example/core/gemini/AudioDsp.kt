package com.example.core.gemini

import kotlin.math.min
import kotlin.math.sqrt

/** RMS of little-endian PCM16 in 0..1. */
fun pcm16Rms(bytes: ByteArray, length: Int = bytes.size): Float {
    val samples = length / 2
    if (samples == 0) return 0f
    var sum = 0.0
    for (i in 0 until samples) {
        val s = (bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF)
        val v = s.toShort().toInt()
        sum += v.toDouble() * v
    }
    return (sqrt(sum / samples) / 32767.0).toFloat().coerceIn(0f, 1f)
}

/**
 * Re-cuts whatever AudioRecord returns into exact fixed-size frames (default 40 ms of 16 kHz
 * mono PCM16 = 1280 bytes). Without this the chunk size depended on the device's minimum buffer
 * (anything from ~20 ms to over 100 ms), which made realtime streaming irregular.
 */
class PcmChunker(
    private val frameBytes: Int = DEFAULT_FRAME_BYTES,
    private val onFrame: (ByteArray) -> Unit
) {
    private val buffer = ByteArray(frameBytes)
    private var filled = 0

    fun push(data: ByteArray, length: Int = data.size) {
        var offset = 0
        while (offset < length) {
            val n = min(length - offset, frameBytes - filled)
            System.arraycopy(data, offset, buffer, filled, n)
            filled += n
            offset += n
            if (filled == frameBytes) {
                onFrame(buffer.copyOf())
                filled = 0
            }
        }
    }

    fun reset() { filled = 0 }

    companion object {
        const val DEFAULT_FRAME_BYTES = 1280 // 40 ms at 16 kHz, mono, 16-bit
    }
}

enum class MicDecision {
    /** Send the frame as captured. */
    FORWARD,
    /** The frame is the assistant's own voice leaking into the mic: send silence instead. */
    SILENCE,
    /** Sustained, clearly-louder speech over the assistant: cut playback and forward the frame. */
    BARGE_IN
}

/**
 * Self-echo guard and barge-in detector (pure logic, no Android).
 *
 * While the assistant is audible on a loudspeaker, mic energy at or below what the speaker is
 * putting out is treated as echo and replaced by silence, so the model never hears (and answers)
 * its own voice. Energy clearly above that for [holdMs] is the user talking over it.
 * With a headset there is no acoustic path, so everything is forwarded and only sustained
 * speech is treated as barge-in.
 */
class EchoGate(
    private val holdMs: Long = 320L,
    private val cooldownMs: Long = 1500L,
    private val margin: Float = 1.6f,
    private val floor: Float = 0.05f
) {
    private var loudSince = 0L
    private var lastBargeInAt = Long.MIN_VALUE / 2

    fun decide(
        nowMs: Long,
        micAmp: Float,
        outputActive: Boolean,
        lastOutputAmp: Float,
        headset: Boolean
    ): MicDecision {
        if (!outputActive) { loudSince = 0L; return MicDecision.FORWARD }

        val echoCeiling = if (headset) floor else lastOutputAmp * margin + floor
        if (micAmp <= echoCeiling) {
            loudSince = 0L
            return if (headset) MicDecision.FORWARD else MicDecision.SILENCE
        }
        if (loudSince == 0L) loudSince = nowMs
        if (nowMs - loudSince >= holdMs && nowMs - lastBargeInAt >= cooldownMs) {
            loudSince = 0L
            lastBargeInAt = nowMs
            return MicDecision.BARGE_IN
        }
        return MicDecision.FORWARD
    }
}
