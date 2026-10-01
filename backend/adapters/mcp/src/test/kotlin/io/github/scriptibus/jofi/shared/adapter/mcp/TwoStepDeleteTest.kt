// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRejection
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The tool-side of the two steps: a use case fake that records every call, a human that answers as told. */
class TwoStepDeleteTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val token = ConfirmationToken("secret-token-value")
    private val required =
        ConfirmationResult.Required(
            token,
            Instant.parse("2026-10-01T10:05:00Z"),
            ConfirmableAction(
                "applications.delete",
                listOf(id.toString()),
                ConfirmationEffect("application", "Backend Engineer", mapOf("interviews" to 2, "sources" to 0)),
            ),
        )
    private val calls = mutableListOf<Pair<ConfirmationRequester, ConfirmationToken?>>()
    private val asked = mutableListOf<String>()

    private fun run(
        answer: HumanAnswer,
        second: ConfirmationResult.Unconfirmed? = null,
    ): ToolAnswer {
        val call =
            ToolCall(ToolArguments(emptyMap()), Actor.Ai, "mcp-session", {
                asked += it
                answer
            })
        return TwoStepDelete.run(
            call,
            id,
            { requester, given ->
                calls += requester to given
                if (given == null) required else second ?: DELETED
            },
            { it as? ConfirmationResult.Unconfirmed },
            { if (it is ConfirmationResult.Unconfirmed) ToolAnswer.Error("x", "x") else ToolAnswer.Result(Unit) },
        )
    }

    @Test
    fun `a confirmed first step runs the second step with the server's token for the same requester`() {
        val answer = run(HumanAnswer.CONFIRMED)

        answer shouldBe ToolAnswer.Result(DeleteOutcome("deleted", "application", id))
        calls.map { it.second } shouldBe listOf(null, token)
        calls.map { it.first }.toSet() shouldBe setOf(ConfirmationRequester(Actor.Ai, "mcp-session"))
    }

    @Test
    fun `the question names the effect the server derived`() {
        run(HumanAnswer.CONFIRMED)

        asked.single() shouldContain "application \"Backend Engineer\""
        asked.single() shouldContain "interviews: 2"
        asked.single() shouldNotContain "sources"
        asked.single() shouldNotContain token.value
    }

    @Test
    fun `without a human to ask nothing runs after the first step`() {
        val answer = run(HumanAnswer.UNAVAILABLE)

        answer.shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "confirmation-unavailable"
        calls.map { it.second } shouldContainExactly listOf(null)
    }

    @Test
    fun `a declined confirmation runs nothing and says so`() {
        val answer = run(HumanAnswer.DECLINED)

        answer shouldBe ToolAnswer.Result(DeleteOutcome("declined", "application", id))
        calls.map { it.second } shouldContainExactly listOf(null)
    }

    @Test
    fun `a token the gate refuses answers confirmation-invalid and never the token`() {
        val answer =
            run(HumanAnswer.CONFIRMED, second = ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH))

        answer.shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "confirmation-invalid"
        answer.toString() shouldNotContain token.value
    }

    @Test
    fun `an answer without a confirmation step, such as not found, goes back unasked`() {
        val call = ToolCall(ToolArguments(emptyMap()), Actor.Ai, "mcp-session", { error("must not ask") })

        val answer =
            TwoStepDelete.run(
                call,
                id,
                { _, _ -> "not found" },
                { null },
                { ToolAnswer.Error("not-found", "none") },
            )

        answer.shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "not-found"
    }

    private companion object {
        const val DELETED = "deleted"
    }
}
