package com.example.actions.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import com.example.core.gemini.GeminiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

class WebSearchAction(
    private val client: OkHttpClient,
    private val geminiClient: GeminiClient
) : Action {

    override val name: String = "web_search"
    override val description: String =
        "Searches the live web (Google Search grounding, DuckDuckGo fallback). Modes: 'news' = what happened " +
        "in the last days, 'research' = in-depth multi-source findings, 'price' = current prices and sellers, " +
        "'compare' = side-by-side comparison with a verdict, 'search' = a short direct answer."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "query" to ParamDefinition("string", "Search query or question. For compare: the items, e.g. 'iPhone 16 vs Pixel 9'", required = true),
        "mode" to ParamDefinition(
            "string", "Search mode", required = false,
            enumValues = listOf("search", "news", "research", "price", "compare")
        )
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult = withContext(Dispatchers.IO) {
        val query = (args["query"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@withContext ActionResult("Query required.", isError = true)
        val mode = (args["mode"] as? String)?.lowercase()?.takeIf { it in MODES } ?: "search"

        val grounded = geminiClient.callOneShot(promptFor(mode, query), grounded = true)
        if (!grounded.isError && grounded.text.isNotBlank()) {
            val sources = grounded.sources
            val spoken = grounded.text.take(MAX_SPOKEN_CHARS)
            return@withContext ActionResult(
                spokenResult = spoken,
                cardData = mapOf(
                    "title" to "${mode.replaceFirstChar { it.uppercase() }}: $query",
                    "snippet" to spoken,
                    "url" to (sources.firstOrNull()?.second ?: "https://duckduckgo.com/?q=${URLEncoder.encode(query, "UTF-8")}"),
                    "sources" to sources.joinToString("\n") { "${it.first} - ${it.second}" },
                    "mode" to mode
                )
            )
        }
        duckDuckGoFallback(query, mode, grounded.text)
    }

    private fun promptFor(mode: String, q: String): String = when (mode) {
        "news" -> "Use Google Search. Report the most recent news (last 48 hours if available) about: $q. " +
            "Give at most 4 items, each one sentence with the outlet and date. Plain text, no markdown."
        "research" -> "Use Google Search and consult several independent sources on: $q. Summarise the key " +
            "findings, note where sources disagree, and say how confident the evidence is. At most 8 sentences, plain text."
        "price" -> "Use Google Search to find the current price of: $q. List up to 4 sellers with price, currency " +
            "and date checked. If prices differ by region or are uncertain, say so. Plain text."
        "compare" -> "Use Google Search to compare: $q. Cover the 4 to 5 criteria that matter most with one line " +
            "per criterion, then give a one-sentence verdict. Plain text."
        else -> "Use Google Search and answer concisely (at most 3 sentences): $q"
    }

    /** Fallback when the Gemini path is unavailable: DuckDuckGo instant answers only (limited). */
    private fun duckDuckGoFallback(query: String, mode: String, geminiProblem: String): ActionResult {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1").build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val json = JSONObject(body)
            val abstract = json.optString("AbstractText", "")
            val related = json.optJSONArray("RelatedTopics")?.optJSONObject(0)?.optString("Text", "").orEmpty()
            val text = abstract.ifEmpty { related }
            if (text.isEmpty()) {
                ActionResult(
                    "I couldn't get live results for '$query' ($mode). ${geminiProblem.take(120)} " +
                        "I can open a search page in the browser if you want.",
                    isError = true
                )
            } else {
                ActionResult(
                    spokenResult = "$text (basic instant answer; live search was unavailable)",
                    cardData = mapOf(
                        "title" to json.optString("Heading", query),
                        "snippet" to text,
                        "url" to json.optString("AbstractURL", "").ifEmpty { "https://duckduckgo.com/?q=$encoded" },
                        "mode" to mode
                    )
                )
            }
        } catch (e: Exception) {
            ActionResult("Search is unreachable right now: ${e.message}", isError = true)
        }
    }

    private companion object {
        val MODES = setOf("search", "news", "research", "price", "compare")
        const val MAX_SPOKEN_CHARS = 1800
    }
}

class BrowserControlAction(private val context: Context) : Action {

    override val name: String = "open_browser"
    override val description: String = "Navigates to a specific URL or web page in the default web browser."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "url" to ParamDefinition("string", "The URL or web address to load", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        var url = (args["url"] as? String)?.trim() ?: return ActionResult("URL required.", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            ActionResult("Accessing $url, Sir.")
        } catch (e: Exception) {
            ActionResult("Could not open browser for $url: ${e.message}", isError = true)
        }
    }
}
