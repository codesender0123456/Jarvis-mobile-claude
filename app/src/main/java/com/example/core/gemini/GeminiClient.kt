package com.example.core.gemini

import android.util.Log
import com.example.core.config.SecureStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class GeminiCallResult(
    val text: String,
    val modelUsed: String,
    val isError: Boolean = false,
    /** (title, url) pairs from Google Search grounding; empty for ungrounded calls. */
    val sources: List<Pair<String, String>> = emptyList()
)

class GeminiClient(
    private val secureStorage: SecureStorage,
    private val okHttpClient: OkHttpClient
) {
    private val modelLadder = MODEL_LADDER

    // Cooldown tracker: modelName -> expiration timestamp (System.currentTimeMillis())
    private val cooldowns = ConcurrentHashMap<String, Long>()

    // Dedicated client with >= 10s timeout (configured for 30s)
    private val client = okHttpClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun isModelInCooldown(model: String): Boolean {
        val until = cooldowns[model] ?: return false
        if (System.currentTimeMillis() >= until) {
            cooldowns.remove(model)
            return false
        }
        return true
    }

    fun applyCooldown(model: String, code: Int) {
        val durationMs = when (code) {
            429 -> TimeUnit.MINUTES.toMillis(5) // Quota exceeded: 5 minutes
            503, 504 -> TimeUnit.MINUTES.toMillis(30) // Gateway/Deadline: 30 minutes
            404 -> TimeUnit.HOURS.toMillis(6) // Not found: 6 hours
            // 400/401/403 are configuration problems (bad key, malformed request, unsupported
            // region). Cooling every model down for 5 minutes just delays the same error, so
            // these fail fast instead.
            else -> 0L
        }
        if (durationMs <= 0L) return
        val expiresAt = System.currentTimeMillis() + durationMs
        cooldowns[model] = expiresAt
        Log.w("GeminiClient", "Model $model cooled down for ${durationMs / 1000}s due to HTTP $code")
    }

    suspend fun callOneShot(prompt: String, grounded: Boolean = false): GeminiCallResult = withContext(Dispatchers.IO) {
        val apiKey = secureStorage.getApiKey()
        if (apiKey.isEmpty()) {
            return@withContext GeminiCallResult(
                text = "Gemini API key is not configured. Please supply an API key in Settings.",
                modelUsed = "None",
                isError = true
            )
        }

        var lastError = "No models available in ladder."

        for (model in modelLadder) {
            if (isModelInCooldown(model)) {
                Log.d("GeminiClient", "Skipping $model (cooling down)")
                continue
            }

            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

                val bodyObj = JSONObject()
                val contentsArr = JSONArray()
                val contentObj = JSONObject()
                val partsArr = JSONArray()
                partsArr.put(JSONObject().put("text", prompt))
                contentObj.put("parts", partsArr)
                contentsArr.put(contentObj)
                bodyObj.put("contents", contentsArr)
                // Grounded mode lets the model run Google Search and cite its sources.
                if (grounded) bodyObj.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

                val req = Request.Builder()
                    .url(url)
                    // Header rather than ?key= so the secret stays out of URLs and access logs.
                    .header("x-goog-api-key", apiKey)
                    .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                // use{} closes the body and returns the connection to the pool.
                client.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()

                    if (resp.isSuccessful) {
                        val text = extractText(respBody)
                        if (text.isNotEmpty()) {
                            return@withContext GeminiCallResult(text = text.trim(), modelUsed = model, sources = extractSources(respBody))
                        }
                        // HTTP 200 with no text (safety block, empty candidates) must not be
                        // reported as a ladder failure.
                        lastError = "Model $model returned an empty response."
                        Log.w("GeminiClient", "Empty response from $model: ${sanitize(respBody, apiKey)}")
                        return@withContext GeminiCallResult(
                            text = "$model did not return any text for that request.",
                            modelUsed = model,
                            isError = true
                        )
                    } else {
                        val code = resp.code
                        lastError = "HTTP $code: ${sanitize(respBody, apiKey)}"
                        applyCooldown(model, code)
                        if (code == 400 || code == 401 || code == 403) {
                            // Retrying other models cannot help; surface the real problem.
                            return@withContext GeminiCallResult(
                                text = when (code) {
                                    401, 403 -> "Gemini rejected the API key. Please re-enter a valid key in Settings."
                                    else -> "Gemini rejected the request (HTTP $code): $lastError"
                                },
                                modelUsed = model,
                                isError = true
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "Exception on $model: ${e.message}"
                Log.e("GeminiClient", "Call failed on $model", e)
            }
        }

        GeminiCallResult(
            text = "Intelligence core ladder failed. Last failure: $lastError",
            modelUsed = "Failed",
            isError = true
        )
    }

    private fun extractSources(respBody: String): List<Pair<String, String>> = try {
        val chunks = JSONObject(respBody).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        if (chunks == null) emptyList() else (0 until chunks.length()).mapNotNull { i ->
            val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: return@mapNotNull null
            val uri = web.optString("uri", ""); if (uri.isEmpty()) null else web.optString("title", uri) to uri
        }.distinctBy { it.second }.take(5)
    } catch (e: JSONException) { emptyList() }

    /** Joins every text part; a candidate can legitimately contain more than one. */
    private fun extractText(respBody: String): String {
        return try {
            val candidates = JSONObject(respBody).optJSONArray("candidates") ?: return ""
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val text = parts.optJSONObject(i)?.optString("text", "").orEmpty()
                if (text.isNotEmpty()) sb.append(text)
            }
            sb.toString()
        } catch (e: JSONException) {
            Log.e("GeminiClient", "Could not parse Gemini response", e)
            ""
        }
    }

    /** Strips anything key-shaped out of server error text before it reaches the UI or a log. */
    private fun sanitize(text: String, apiKey: String): String {
        var out = text.replace(apiKey, "[REDACTED]")
        out = Regex("AIza[0-9A-Za-z_-]{10,}").replace(out, "[REDACTED]")
        return out.take(400)
    }

    fun getLadderStatus(): List<Pair<String, Boolean>> {
        return modelLadder.map { it to isModelInCooldown(it) }
    }

    companion object {
        /** One-shot model ladder, best first. Cooldowns apply per model (see [applyCooldown]). */
        val MODEL_LADDER = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-3.5-flash"
        )
    }
}
