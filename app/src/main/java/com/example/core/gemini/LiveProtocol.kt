package com.example.core.gemini

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Decoded server -> client Live API messages. Pure data, no Android types. */
sealed interface LiveServerEvent {
    data object SetupComplete : LiveServerEvent
    class Audio(val pcm24k: ByteArray) : LiveServerEvent
    data object Interrupted : LiveServerEvent
    data object TurnComplete : LiveServerEvent
    class InputText(val text: String) : LiveServerEvent
    class OutputText(val text: String) : LiveServerEvent
    class ToolCall(val id: String, val name: String, val args: Map<String, Any?>) : LiveServerEvent
    class Resumption(val handle: String) : LiveServerEvent
    data object GoAway : LiveServerEvent
}

/** What a socket close means for the reconnect policy. */
enum class CloseKind {
    /** Bad or revoked API key: retrying cannot help. */
    FATAL_KEY,
    /** 429 / quota: cool the model down and use the fallback. */
    QUOTA,
    /** 404 / no access to this model: cool it down for hours. */
    MODEL_UNAVAILABLE,
    /** Anything else (network drop, server restart, goAway): reconnect with the same model. */
    TRANSIENT
}

/**
 * The Gemini Live (BidiGenerateContent) wire format, kept free of sockets and Android classes
 * so it can be unit-tested on the JVM. java.util.Base64 is available from API 26 (our minSdk).
 */
object LiveProtocol {
    const val INPUT_AUDIO_MIME = "audio/pcm;rate=16000"
    const val VIDEO_MIME = "image/jpeg"

    fun buildSetup(
        model: String,
        voice: String,
        systemInstruction: String,
        tools: JSONArray,
        resumeHandle: String?
    ): JSONObject {
        val setup = JSONObject()
        setup.put("model", "models/$model")
        setup.put(
            "generationConfig",
            JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put(
                    "speechConfig",
                    JSONObject().put(
                        "voiceConfig",
                        JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
                    )
                )
        )
        setup.put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
        )
        setup.put("inputAudioTranscription", JSONObject())
        setup.put("outputAudioTranscription", JSONObject())
        if (tools.length() > 0) setup.put("tools", tools)

        // Asking for resumption (even with no handle yet) makes the server send updates.
        val resumption = JSONObject()
        if (!resumeHandle.isNullOrEmpty()) resumption.put("handle", resumeHandle)
        setup.put("sessionResumption", resumption)

        // Sliding-window compression: unlimited-length sessions instead of a hard context limit.
        setup.put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        return JSONObject().put("setup", setup)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun audioFrame(pcm16k: ByteArray): String = JSONObject().put(
        "realtimeInput",
        JSONObject().put("audio", JSONObject().put("mimeType", INPUT_AUDIO_MIME).put("data", b64(pcm16k)))
    ).toString()

    fun videoFrame(jpeg: ByteArray): String = JSONObject().put(
        "realtimeInput",
        JSONObject().put("video", JSONObject().put("mimeType", VIDEO_MIME).put("data", b64(jpeg)))
    ).toString()

    fun text(text: String): String =
        JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString()

    fun toolResponse(id: String, name: String, output: String): String = JSONObject().put(
        "toolResponse",
        JSONObject().put(
            "functionResponses",
            JSONArray().put(
                JSONObject().put("id", id).put("name", name)
                    .put("response", JSONObject().put("output", output))
            )
        )
    ).toString()

    /** Parses one server frame (text or decoded binary) into events, in the order to apply them. */
    fun parse(raw: String): List<LiveServerEvent> {
        val root = try { JSONObject(raw) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<LiveServerEvent>()

        if (root.has("setupComplete")) out += LiveServerEvent.SetupComplete

        root.optJSONObject("sessionResumptionUpdate")?.let { u ->
            val handle = u.optString("newHandle", "")
            if (u.optBoolean("resumable", true) && handle.isNotEmpty()) out += LiveServerEvent.Resumption(handle)
        }
        if (root.has("goAway")) out += LiveServerEvent.GoAway

        root.optJSONObject("serverContent")?.let { sc ->
            // One event may carry several parts at once (audio chunks and transcript together).
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data", "").orEmpty()
                    if (data.isNotEmpty()) {
                        runCatching { Base64.getMimeDecoder().decode(data) }
                            .onSuccess { out += LiveServerEvent.Audio(it) }
                    }
                }
            }
            sc.optJSONObject("inputTranscription")?.optString("text", "")?.takeIf { it.isNotEmpty() }
                ?.let { out += LiveServerEvent.InputText(it) }
            sc.optJSONObject("outputTranscription")?.optString("text", "")?.takeIf { it.isNotEmpty() }
                ?.let { out += LiveServerEvent.OutputText(it) }
            if (sc.optBoolean("interrupted", false)) out += LiveServerEvent.Interrupted
            if (sc.optBoolean("turnComplete", false)) out += LiveServerEvent.TurnComplete
        }

        root.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val name = c.optString("name", "")
                if (name.isEmpty()) continue
                val args = HashMap<String, Any?>()
                c.optJSONObject("args")?.let { a ->
                    a.keys().forEach { k -> args[k] = a.opt(k).takeIf { it != JSONObject.NULL } }
                }
                out += LiveServerEvent.ToolCall(c.optString("id", ""), name, args)
            }
        }
        return out
    }

    fun classifyClose(code: Int, reason: String): CloseKind {
        val r = reason.lowercase()
        return when {
            "api key" in r || "api_key" in r || code == 401 -> CloseKind.FATAL_KEY
            code == 429 || "quota" in r || "resource_exhausted" in r || "exhausted" in r -> CloseKind.QUOTA
            code == 404 || code == 403 || "not found" in r || "not_found" in r ||
                "not supported" in r || "permission" in r -> CloseKind.MODEL_UNAVAILABLE
            else -> CloseKind.TRANSIENT
        }
    }

    /** Removes the API key (and anything key-shaped) from text that may be logged or shown. */
    fun redact(text: String, apiKey: String?): String {
        var out = text
        if (!apiKey.isNullOrEmpty()) out = out.replace(apiKey, "[REDACTED]")
        return Regex("AIza[0-9A-Za-z_-]{10,}").replace(out, "[REDACTED]")
    }
}
