// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

/** How the client's answer, silence and failures become [HumanAnswer]s; only a real yes confirms. */
class ElicitingConfirmerTest {
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val exchange = mockk<McpSyncServerExchange>()

    private fun confirmer(
        capabilities: McpSchema.ClientCapabilities? =
            McpSchema.ClientCapabilities
                .builder()
                .elicitation()
                .build(),
        session: String? = "session",
        filter: (String) -> String? = { it },
    ): ElicitingConfirmer {
        every { exchange.sessionId() } returns session
        every { exchange.clientCapabilities } returns capabilities
        return ElicitingConfirmer(exchange, pending, filter)
    }

    private fun answers(
        action: McpSchema.ElicitResult.Action,
        content: Map<String, Any>?,
    ) {
        every { exchange.createElicitation(any()) } returns McpSchema.ElicitResult(action, content)
    }

    @Test
    fun `only accept with the box checked as a real boolean confirms`() {
        val confirmer = confirmer()

        answers(McpSchema.ElicitResult.Action.ACCEPT, mapOf("confirm" to true))
        confirmer.ask("q") shouldBe HumanAnswer.CONFIRMED

        listOf(
            McpSchema.ElicitResult.Action.ACCEPT to mapOf<String, Any>("confirm" to false),
            McpSchema.ElicitResult.Action.ACCEPT to mapOf<String, Any>("confirm" to "true"),
            McpSchema.ElicitResult.Action.ACCEPT to mapOf<String, Any>("confirm" to 1),
            McpSchema.ElicitResult.Action.ACCEPT to mapOf<String, Any>(),
            McpSchema.ElicitResult.Action.DECLINE to mapOf<String, Any>("confirm" to true),
            McpSchema.ElicitResult.Action.CANCEL to mapOf<String, Any>("confirm" to true),
        ).forEach { (action, content) ->
            answers(action, content)
            confirmer.ask("q") shouldBe HumanAnswer.DECLINED
        }
    }

    @Test
    fun `a missing result declines`() {
        val confirmer = confirmer()
        every { exchange.createElicitation(any()) } returns null

        confirmer.ask("q") shouldBe HumanAnswer.DECLINED
    }

    @Test
    fun `no answer in time is a timeout, a broken transport is unavailable`() {
        val confirmer = confirmer()

        every { exchange.createElicitation(any()) } throws RuntimeException(TimeoutException("no answer"))
        confirmer.ask("q") shouldBe HumanAnswer.TIMED_OUT

        every { exchange.createElicitation(any()) } throws IllegalStateException("connection closed")
        confirmer.ask("q") shouldBe HumanAnswer.UNAVAILABLE
        pending.shouldBeEmpty()
    }

    @Test
    fun `a client without form elicitation is unsupported and is never asked`() {
        val none = confirmer(capabilities = McpSchema.ClientCapabilities.builder().build())
        none.availability shouldBe Availability.UNSUPPORTED
        none.ask("q") shouldBe HumanAnswer.UNAVAILABLE

        val urlOnly =
            confirmer(
                capabilities =
                    McpSchema.ClientCapabilities
                        .builder()
                        .elicitation(false, true)
                        .build(),
            )
        urlOnly.availability shouldBe Availability.UNSUPPORTED
        urlOnly.ask("q") shouldBe HumanAnswer.UNAVAILABLE

        val formOnly =
            confirmer(
                capabilities =
                    McpSchema.ClientCapabilities
                        .builder()
                        .elicitation(true, false)
                        .build(),
            )
        formOnly.availability shouldBe Availability.READY

        confirmer(session = null).availability shouldBe Availability.UNSUPPORTED
        verify(exactly = 0) { exchange.createElicitation(any()) }
    }

    @Test
    fun `a text the filter refuses is not asked`() {
        val confirmer = confirmer(filter = { null })

        confirmer.ask("q") shouldBe HumanAnswer.UNAVAILABLE
        verify(exactly = 0) { exchange.createElicitation(any()) }
    }

    @Test
    fun `one confirmation per session may wait, and the slot is free again afterwards`() {
        val confirmer = confirmer()
        var duringAsk: Availability? = null
        var second: HumanAnswer? = null
        every { exchange.createElicitation(any()) } answers {
            duringAsk = confirmer.availability
            second = confirmer.ask("another")
            McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, null)
        }

        confirmer.ask("first") shouldBe HumanAnswer.DECLINED

        duringAsk shouldBe Availability.BUSY
        second shouldBe HumanAnswer.BUSY
        confirmer.availability shouldBe Availability.READY
        pending.shouldBeEmpty()
    }
}
