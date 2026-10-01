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
            ToolCall(
                ToolArguments(emptyMap()),
                Actor.Ai,
                "mcp-session",
                HumanConfirmer.answering {
                    asked += it
                    answer
                },
            )
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

        asked.single() shouldContain "delete this application"
        asked.single() shouldContain "    Backend Engineer"
        asked.single() shouldContain "2 interviews"
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
    fun `a client that cannot ask never reaches the use case, so no token is issued`() {
        val call = ToolCall(ToolArguments(emptyMap()), Actor.Ai, "mcp-session", HumanConfirmer.NONE)

        val answer = TwoStepDelete.run(call, id, { _, _ -> error("must not run") }, { null }, { error("no") })

        answer.shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "confirmation-unavailable"
    }

    @Test
    fun `a missing answer is a timeout without deleting`() {
        run(HumanAnswer.TIMED_OUT).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "confirmation-timeout"
        calls.map { it.second } shouldBe listOf(null)
    }

    @Test
    fun `a session or server at its limit is refused before a token is issued`() {
        val busy =
            object : HumanConfirmer {
                override fun reserve() = Reservation.Busy

                override fun ask(message: String) = error("must not ask")
            }
        val call = ToolCall(ToolArguments(emptyMap()), Actor.Ai, "mcp-session", busy)

        val answer = TwoStepDelete.run(call, id, { _, _ -> error("must not run") }, { null }, { error("no") })

        answer.shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "confirmation-pending"
    }

    @Test
    fun `the slot is held for the whole run and given back afterwards, also when the run fails`() {
        var released = 0
        val confirmer =
            object : HumanConfirmer {
                override fun reserve() = Reservation.Granted { released++ }

                override fun screen(stored: String) = stored

                override fun ask(message: String): HumanAnswer {
                    released shouldBe 0
                    return HumanAnswer.DECLINED
                }
            }
        val call = ToolCall(ToolArguments(emptyMap()), Actor.Ai, "mcp-session", confirmer)

        TwoStepDelete.run(call, id, { _, _ -> required }, { it as? ConfirmationResult.Unconfirmed }, { error("no") })
        released shouldBe 1
        runCatching { TwoStepDelete.run(call, id, { _, _ -> error("store down") }, { null }, { error("no") }) }
        released shouldBe 2
    }

    private val hostileName = "O'Brien & \"Söhne\" #1 [intern]"

    private fun gateFor(name: String) =
        ConfirmationResult.Required(
            token,
            Instant.parse("2026-10-01T10:05:00Z"),
            ConfirmableAction(
                "applications.delete",
                listOf(id.toString()),
                ConfirmationEffect("application", name, emptyMap()),
            ),
        )

    private fun runWith(
        human: HumanConfirmer,
        gate: ConfirmationResult.Required,
    ) = TwoStepDelete.run(
        ToolCall(ToolArguments(emptyMap()), Actor.Ai, "s", human),
        id,
        { _, _ -> gate },
        { it as? ConfirmationResult.Unconfirmed },
        { error("no") },
    )

    @Test
    fun `the privacy filter sees the stored name as stored, before it is neutralised`() {
        val seen = mutableListOf<String>()
        val flagging =
            object : HumanConfirmer {
                override fun reserve() = Reservation.Granted {}

                override fun screen(stored: String): String {
                    seen += stored
                    return stored.replace(hostileName, "[withheld]")
                }

                override fun ask(message: String): HumanAnswer {
                    asked += message
                    return HumanAnswer.DECLINED
                }
            }

        runWith(flagging, gateFor(hostileName))

        seen shouldBe listOf(hostileName)
        asked.single() shouldContain "    withheld"
        asked.single() shouldNotContain "Brien"
    }

    @Test
    fun `a stored name the privacy filter cannot screen is not asked`() {
        val refusing =
            object : HumanConfirmer {
                override fun reserve() = Reservation.Granted {}

                override fun screen(stored: String): String? = null

                override fun ask(message: String) = error("must not ask")
            }

        val answer = runWith(refusing, gateFor(hostileName))

        answer.shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "confirmation-unavailable"
    }

    @Test
    fun `without an MCP session no confirmation is started`() {
        val call =
            ToolCall(ToolArguments(emptyMap()), Actor.Ai, human = HumanConfirmer.answering { HumanAnswer.CONFIRMED })

        val answer = TwoStepDelete.run(call, id, { _, _ -> error("must not run") }, { null }, { error("no") })

        answer.shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "confirmation-unavailable"
    }

    @Test
    fun `a confirmer that does not say it can ask is treated as unsupported`() {
        val silent =
            object : HumanConfirmer {
                override fun ask(message: String) = HumanAnswer.CONFIRMED
            }
        val call = ToolCall(ToolArguments(emptyMap()), Actor.Ai, "mcp-session", silent)

        val answer = TwoStepDelete.run(call, id, { _, _ -> error("must not run") }, { null }, { error("no") })

        answer.shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "confirmation-unavailable"
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
        val call =
            ToolCall(
                ToolArguments(emptyMap()),
                Actor.Ai,
                "mcp-session",
                HumanConfirmer.answering { error("must not ask") },
            )

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
