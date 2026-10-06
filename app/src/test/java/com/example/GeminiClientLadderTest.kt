package com.example

import android.app.Application
import com.example.core.config.SecureStorage
import com.example.core.gemini.GeminiClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Tests the ladder the app really uses (the old test only asserted against its own local lists). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GeminiClientLadderTest {

    private fun client() = GeminiClient(SecureStorage(RuntimeEnvironment.getApplication()), OkHttpClient())

    @Test fun ladderIsTheSpecifiedOrder() {
        assertEquals(
            listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3.5-flash"),
            GeminiClient.MODEL_LADDER
        )
    }

    @Test fun quotaCoolsAModelAndOthersStayAvailable() {
        val c = client()
        val m = GeminiClient.MODEL_LADDER[0]
        assertFalse(c.isModelInCooldown(m))
        c.applyCooldown(m, 429)
        assertTrue(c.isModelInCooldown(m))
        assertFalse(c.isModelInCooldown(GeminiClient.MODEL_LADDER[1]))
    }

    @Test fun configErrorsDoNotCoolDownAnything() {
        val c = client()
        c.applyCooldown(GeminiClient.MODEL_LADDER[0], 403)
        assertFalse(c.isModelInCooldown(GeminiClient.MODEL_LADDER[0]))
    }

    @Test fun serverErrorsAndMissingModelsCool() {
        val c = client()
        c.applyCooldown("a", 503); c.applyCooldown("b", 404)
        assertTrue(c.isModelInCooldown("a")); assertTrue(c.isModelInCooldown("b"))
    }
}
