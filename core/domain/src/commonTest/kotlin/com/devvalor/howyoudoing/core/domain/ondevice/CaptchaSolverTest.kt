package com.devvalor.howyoudoing.core.domain.ondevice

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptchaSolverTest {

    private val challenge = CaptchaChallenge(ChatNetwork.DISCORD, "hcaptcha", "site", null, "https://discord.com/")

    @Test
    fun theAnswerGoesBackToWhoAsked() = runTest {
        val solver = CaptchaSolver()
        val answer = async { solver.solve(challenge) }
        runCurrent()
        assertEquals(challenge, solver.pending.value)

        solver.submit("token")
        assertEquals("token", answer.await())
        assertNull(solver.pending.value)
    }

    @Test
    fun closingItGivesNoAnswer() = runTest {
        val solver = CaptchaSolver()
        val answer = async { solver.solve(challenge) }
        runCurrent()
        solver.submit(null)
        assertNull(answer.await())
    }
}
