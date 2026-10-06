package com.example

import com.example.core.gemini.LiveModelLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveModelLadderTest {
    private var now = 0L
    private val a = LiveModelLadder.PRIMARY
    private val b = LiveModelLadder.FALLBACK
    private val ladder = LiveModelLadder(clock = { now })

    @Test fun startsOnPrimary() = assertEquals(a, ladder.current())

    @Test fun quotaStepsDownThenRecoversAfterFiveMinutes() {
        assertTrue(ladder.markFailure(a, 429))
        assertEquals(b, ladder.current())
        now += 5 * 60_000L + 1
        assertEquals(a, ladder.current())
    }

    @Test fun lostAccessCoolsForSixHours() {
        assertTrue(ladder.markFailure(a, 404))
        now += 5 * 60 * 60_000L
        assertEquals(b, ladder.current())
        now += 61 * 60_000L
        assertEquals(a, ladder.current())
    }

    @Test fun networkAndServerErrorsNeverStep() {
        assertFalse(ladder.markFailure(a, 503))
        assertFalse(ladder.markFailure(a, -1))
        assertEquals(a, ladder.current())
    }

    @Test fun whenEverythingCoolsPicksSoonestToRecover() {
        ladder.markFailure(a, 404)
        now += 1000
        ladder.markFailure(b, 429)
        assertEquals(b, ladder.current())
    }
}
