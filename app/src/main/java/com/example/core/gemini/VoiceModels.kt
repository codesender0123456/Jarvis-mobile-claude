package com.example.core.gemini

data class GeminiVoice(
    val id: String,
    val displayName: String,
    val description: String
)

object VoiceCatalog {
    val VOICES = listOf(
        GeminiVoice("Puck", "Puck (British, Dry-witted)", "Crisp, calm, and subtly sarcastic. Perfect for JARVIS."),
        GeminiVoice("Charon", "Charon (Deep, Authoritative)", "Deep, commanding baritone tone."),
        GeminiVoice("Kore", "Kore (Warm, Professional)", "Clear, composed, and soothing tone."),
        GeminiVoice("Fenrir", "Fenrir (Energetic, Focused)", "Sharp, agile, and alert."),
        GeminiVoice("Aoede", "Aoede (Melodic, Polite)", "Refined and smooth phrasing.")
    )

    fun getVoice(id: String): GeminiVoice {
        return VOICES.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: VOICES.first()
    }
}
