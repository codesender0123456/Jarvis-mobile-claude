package com.example.core.gemini

/**
 * Live-session model ladder: one primary rung and ONE fallback rung.
 *
 * The ladder only steps down on quota (429 -> 5 min) or lost model access (404/403 on the model
 * -> 6 h). Network drops, 5xx and bad keys never move it, and each rung recovers on its own once
 * its cooldown expires, so the app returns to the primary model without a restart.
 *
 * Verify the ids against Google's current Live model list before release.
 */
class LiveModelLadder(
    private val models: List<String> = DEFAULT_MODELS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val coolUntil = HashMap<String, Long>()

    /** First model that is not cooling down; if all are, the one that recovers soonest. */
    @Synchronized
    fun current(): String {
        val now = clock()
        return models.firstOrNull { (coolUntil[it] ?: 0L) <= now }
            ?: models.minBy { coolUntil[it] ?: 0L }
    }

    /** Returns true if the failure was a stepping failure (quota / lost access) and was recorded. */
    @Synchronized
    fun markFailure(model: String, httpCode: Int): Boolean {
        val ms = when (httpCode) {
            429 -> 5 * MINUTE
            404 -> 6 * 60 * MINUTE
            else -> return false
        }
        coolUntil[model] = clock() + ms
        return true
    }

    companion object {
        const val PRIMARY = "gemini-3.1-flash-live-preview"
        const val FALLBACK = "gemini-2.5-flash-native-audio-preview-12-2025"
        val DEFAULT_MODELS = listOf(PRIMARY, FALLBACK)
        private const val MINUTE = 60_000L
    }
}
