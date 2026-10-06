package com.example

import com.example.core.wake.WakePhraseMatcher
import com.example.service.BootAction
import com.example.service.BootPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAndBootTest {

    @Test fun acceptsGreetingPlusName() {
        assertTrue(WakePhraseMatcher.matches("Hey Jarvis"))
        assertTrue(WakePhraseMatcher.matches("hey, jarvis what's the weather"))
        assertTrue(WakePhraseMatcher.matches("OK Jarvis"))
        assertTrue(WakePhraseMatcher.matches("heyjarvis"))
    }

    @Test fun rejectsBareNameAndUnrelatedSpeech() {
        assertFalse(WakePhraseMatcher.matches("jarvis"))
        assertFalse(WakePhraseMatcher.matches("I watched the Jarvis movie yesterday"))
        assertFalse(WakePhraseMatcher.matches("hey there"))
        assertFalse(WakePhraseMatcher.matches(""))
    }

    @Test fun followsTheConfiguredAssistantName() {
        assertTrue(WakePhraseMatcher.matches("hey friday", "Friday"))
        assertFalse(WakePhraseMatcher.matches("hey friday", "Jarvis"))
        assertTrue(WakePhraseMatcher.matches("hey mark five", "Mark Five"))
    }

    @Test fun bootDoesNothingWhenAutoStartIsOffOrMicDenied() {
        assertEquals(BootAction.NONE, BootPolicy.decide(false, true, 33))
        assertEquals(BootAction.NONE, BootPolicy.decide(true, false, 33))
    }

    @Test fun bootStartsServiceBeforeAndroid14() {
        assertEquals(BootAction.START_WAKE_SERVICE, BootPolicy.decide(true, true, 33))
    }

    @Test fun androidFourteenAsksForATapInstead() {
        assertEquals(BootAction.POST_TAP_TO_START, BootPolicy.decide(true, true, 34))
        assertEquals(BootAction.POST_TAP_TO_START, BootPolicy.decide(true, true, 36))
    }
}
