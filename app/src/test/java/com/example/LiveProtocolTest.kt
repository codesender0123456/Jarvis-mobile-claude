package com.example

import com.example.core.gemini.CloseKind
import com.example.core.gemini.LiveProtocol
import com.example.core.gemini.LiveServerEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveProtocolTest {

    private val tools = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()))

    @Test fun setupHasResumptionCompressionTranscriptionAndModel() {
        val s = LiveProtocol.buildSetup("m-1", "Puck", "be brief", tools, null).getJSONObject("setup")
        assertEquals("models/m-1", s.getString("model"))
        assertTrue("empty sessionResumption must be present so the server sends handles", s.has("sessionResumption"))
        assertFalse(s.getJSONObject("sessionResumption").has("handle"))
        assertTrue(s.getJSONObject("contextWindowCompression").has("slidingWindow"))
        assertTrue(s.has("inputAudioTranscription") && s.has("outputAudioTranscription"))
        assertEquals("AUDIO", s.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertEquals(
            "Puck",
            s.getJSONObject("generationConfig").getJSONObject("speechConfig").getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig").getString("voiceName")
        )
    }

    @Test fun setupReplaysTheStoredHandle() {
        val s = LiveProtocol.buildSetup("m", "Puck", "x", tools, "handle-123").getJSONObject("setup")
        assertEquals("handle-123", s.getJSONObject("sessionResumption").getString("handle"))
        assertFalse("legacy top-level field must not be used", s.has("sessionResumptionHandle"))
    }

    @Test fun audioUsesRealtimeInputAudioWithRate() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val a = JSONObject(LiveProtocol.audioFrame(pcm)).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", a.getString("mimeType"))
        assertEquals(pcm.toList(), Base64.getDecoder().decode(a.getString("data")).toList())
        assertFalse(JSONObject(LiveProtocol.audioFrame(pcm)).getJSONObject("realtimeInput").has("mediaChunks"))
    }

    @Test fun videoAndTextFrames() {
        val v = JSONObject(LiveProtocol.videoFrame(byteArrayOf(9))).getJSONObject("realtimeInput").getJSONObject("video")
        assertEquals("image/jpeg", v.getString("mimeType"))
        assertEquals("hi", JSONObject(LiveProtocol.text("hi")).getJSONObject("realtimeInput").getString("text"))
    }

    @Test fun parsesSetupCompleteAndResumptionUpdate() {
        val ev = LiveProtocol.parse("""{"setupComplete":{}}""") +
            LiveProtocol.parse("""{"sessionResumptionUpdate":{"newHandle":"abc","resumable":true}}""")
        assertTrue(ev[0] is LiveServerEvent.SetupComplete)
        assertEquals("abc", (ev[1] as LiveServerEvent.Resumption).handle)
    }

    @Test fun ignoresNonResumableUpdates() {
        assertTrue(LiveProtocol.parse("""{"sessionResumptionUpdate":{"newHandle":"abc","resumable":false}}""").isEmpty())
    }

    @Test fun parsesAudioTranscriptsAndTurnFlagsFromOneEvent() {
        val data = Base64.getEncoder().encodeToString(byteArrayOf(5, 6))
        val raw = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm","data":"$data"}}]},
            "outputTranscription":{"text":"hello"},"inputTranscription":{"text":"hey"},"turnComplete":true}}"""
        val ev = LiveProtocol.parse(raw)
        assertEquals(listOf(5.toByte(), 6.toByte()), (ev[0] as LiveServerEvent.Audio).pcm24k.toList())
        assertEquals("hey", ev.filterIsInstance<LiveServerEvent.InputText>().single().text)
        assertEquals("hello", ev.filterIsInstance<LiveServerEvent.OutputText>().single().text)
        assertTrue(ev.last() is LiveServerEvent.TurnComplete)
    }

    @Test fun interruptedIsReportedBeforeTurnComplete() {
        val ev = LiveProtocol.parse("""{"serverContent":{"interrupted":true}}""")
        assertEquals(1, ev.size)
        assertTrue(ev[0] is LiveServerEvent.Interrupted)
    }

    @Test fun parsesToolCallsWithArgsAndNulls() {
        val raw = """{"toolCall":{"functionCalls":[{"id":"c1","name":"set_volume","args":{"level":40,"stream":null}}]}}"""
        val c = LiveProtocol.parse(raw).single() as LiveServerEvent.ToolCall
        assertEquals("c1", c.id)
        assertEquals("set_volume", c.name)
        assertEquals(40, c.args["level"])
        assertEquals(null, c.args["stream"])
    }

    @Test fun toolResponseCarriesIdNameAndOutput() {
        val r = JSONObject(LiveProtocol.toolResponse("c1", "set_volume", "done"))
            .getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("c1", r.getString("id"))
        assertEquals("set_volume", r.getString("name"))
        assertEquals("done", r.getJSONObject("response").getString("output"))
    }

    @Test fun garbageFramesAreIgnored() {
        assertTrue(LiveProtocol.parse("not json").isEmpty())
        assertTrue(LiveProtocol.parse("{}").isEmpty())
    }

    @Test fun closeClassification() {
        assertEquals(CloseKind.FATAL_KEY, LiveProtocol.classifyClose(1007, "API key not valid. Please pass a valid API key."))
        assertEquals(CloseKind.QUOTA, LiveProtocol.classifyClose(1011, "RESOURCE_EXHAUSTED: quota"))
        assertEquals(CloseKind.QUOTA, LiveProtocol.classifyClose(429, ""))
        assertEquals(CloseKind.MODEL_UNAVAILABLE, LiveProtocol.classifyClose(404, "models/x is not found"))
        assertEquals(CloseKind.TRANSIENT, LiveProtocol.classifyClose(1006, "network dropped"))
        assertEquals(CloseKind.TRANSIENT, LiveProtocol.classifyClose(-1, "SocketTimeoutException"))
    }

    @Test fun redactRemovesKeys() {
        assertEquals("url=[REDACTED]", LiveProtocol.redact("url=secret-key-1", "secret-key-1"))
        assertFalse(LiveProtocol.redact("x AIzaSyA1234567890abcdef y", null).contains("AIza"))
    }
}
