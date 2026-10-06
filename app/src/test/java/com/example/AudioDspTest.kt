package com.example

import com.example.core.gemini.EchoGate
import com.example.core.gemini.MicDecision
import com.example.core.gemini.PcmChunker
import com.example.core.gemini.pcm16Rms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDspTest {

    @Test fun chunkerEmitsOnlyExactFrames() {
        val frames = ArrayList<ByteArray>()
        val c = PcmChunker(frameBytes = 8) { frames += it }
        c.push(ByteArray(5)); assertEquals(0, frames.size)
        c.push(ByteArray(5)); assertEquals(1, frames.size)   // 10 bytes in -> 1 frame, 2 left over
        c.push(ByteArray(30)); assertEquals(5, frames.size)  // 32 total -> 4 frames, so 5 overall
        assertTrue(frames.all { it.size == 8 })
    }

    @Test fun chunkerKeepsByteOrder() {
        val out = ArrayList<Byte>()
        val c = PcmChunker(frameBytes = 4) { f -> out += f.toList() }
        c.push(byteArrayOf(1, 2, 3)); c.push(byteArrayOf(4, 5, 6, 7, 8))
        assertEquals((1..8).map { it.toByte() }, out)
    }

    @Test fun defaultFrameIsFortyMilliseconds() {
        assertEquals(16_000 * 2 * 40 / 1000, PcmChunker.DEFAULT_FRAME_BYTES)
    }

    @Test fun rmsOfSilenceAndLoudSignal() {
        assertEquals(0f, pcm16Rms(ByteArray(100)), 0f)
        val loud = ByteArray(100) { if (it % 2 == 1) 0x40 else 0 } // sample 0x4000
        assertEquals(0.5f, pcm16Rms(loud), 0.01f)
    }

    @Test fun nothingPlayingMeansForward() {
        val g = EchoGate()
        assertEquals(MicDecision.FORWARD, g.decide(1000, 0.9f, outputActive = false, lastOutputAmp = 0f, headset = false))
    }

    @Test fun speakerBleedIsReplacedBySilence() {
        val g = EchoGate()
        // output at 0.3 RMS; mic at 0.2 is echo
        assertEquals(MicDecision.SILENCE, g.decide(1000, 0.2f, true, 0.3f, headset = false))
    }

    @Test fun briefLoudBurstIsNotBargeIn_sustainedSpeechIs() {
        val g = EchoGate(holdMs = 320, cooldownMs = 1500)
        assertEquals(MicDecision.FORWARD, g.decide(1000, 0.9f, true, 0.2f, false)) // starts the hold timer
        assertEquals(MicDecision.FORWARD, g.decide(1200, 0.9f, true, 0.2f, false)) // 200 ms: not yet
        assertEquals(MicDecision.BARGE_IN, g.decide(1400, 0.9f, true, 0.2f, false)) // 400 ms sustained
    }

    @Test fun quietGapResetsTheHoldTimer() {
        val g = EchoGate(holdMs = 320)
        g.decide(1000, 0.9f, true, 0.2f, false)
        g.decide(1200, 0.01f, true, 0.2f, false)                       // echo level: resets
        assertEquals(MicDecision.FORWARD, g.decide(1400, 0.9f, true, 0.2f, false)) // timer restarted
    }

    @Test fun bargeInHasACooldown() {
        val g = EchoGate(holdMs = 100, cooldownMs = 1500)
        g.decide(1000, 0.9f, true, 0.2f, false)
        assertEquals(MicDecision.BARGE_IN, g.decide(1200, 0.9f, true, 0.2f, false))
        g.decide(1300, 0.9f, true, 0.2f, false)
        assertEquals(MicDecision.FORWARD, g.decide(1500, 0.9f, true, 0.2f, false)) // within cooldown
    }

    @Test fun headsetForwardsEverythingExceptSustainedSpeech() {
        val g = EchoGate(holdMs = 100)
        assertEquals(MicDecision.FORWARD, g.decide(1000, 0.02f, true, 0.5f, headset = true))
    }
}
