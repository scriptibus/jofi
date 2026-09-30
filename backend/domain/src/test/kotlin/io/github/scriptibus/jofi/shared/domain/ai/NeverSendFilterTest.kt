// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

import io.github.scriptibus.jofi.shared.domain.ai.NeverSendFilter.REDACTION
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class NeverSendFilterTest {
    private val address = ContentSource(ContentSourceType.KNOWLEDGE_ENTRY, "profile/address")
    private val skills = ContentSource(ContentSourceType.KNOWLEDGE_ENTRY, "skills")
    private val flaggedText = "Musterstraße 5, 12345 Berlin"
    private val phone = FlaggedValue("+49 170 1234567")
    private val rules =
        NeverSendRules(
            verdicts = mapOf(address to AiVisibility.NEVER_SEND, skills to AiVisibility.SENDABLE),
            flaggedValues = setOf(FlaggedValue(flaggedText), phone),
        )

    @Test
    fun `a flagged source is withheld from every message part that can carry one`() {
        val flagged = ContentPart.Sourced(flaggedText, address)
        val request =
            LlmRequest(
                AiTask.CHAT,
                listOf(
                    LlmMessage.System(listOf(ContentPart.Plain("Profile: "), flagged)),
                    LlmMessage.User(listOf(ContentPart.Plain("Where do I live? "), flagged)),
                    LlmMessage.Assistant("", listOf(ToolCall("call-1", "read_profile", "{}"))),
                    LlmMessage.ToolResult("call-1", listOf(flagged, ContentPart.Sourced("Kotlin", skills))),
                ),
            )

        val passed = NeverSendFilter.apply(request, rules).shouldBeInstanceOf<FilterOutcome.Passed<LlmRequest>>()

        passed.redactions shouldBe 3
        val sent = passed.value.toWireText()
        sent shouldNotContain "Musterstraße"
        sent shouldContain "Profile: $REDACTION"
        sent shouldContain "Where do I live? $REDACTION"
        sent shouldContain "${REDACTION}Kotlin"
        passed.value.messages
            .flatMap(::partsOf)
            .all { it is ContentPart.Plain } shouldBe true
    }

    @Test
    fun `a flagged value is redacted wherever it appears, also without a source`() {
        val request =
            LlmRequest(
                AiTask.CHAT,
                listOf(
                    LlmMessage.System("Call +49 170 1234567 if needed"),
                    LlmMessage.User("My number is +49  170\n1234567, my address MUSTERSTRASSE 5, 12345 berlin?"),
                    LlmMessage.Assistant(
                        "You live at musterstraße 5, 12345 Berlin",
                        listOf(ToolCall("c", "save_note", """{"text":"+49 170 1234567"}""")),
                    ),
                    LlmMessage.ToolResult("c", "saved +49 170 1234567"),
                ),
                tools = listOf(ToolDefinition("save_note", "Saves a note", """{"type":"object"}""")),
            )

        val passed = NeverSendFilter.apply(request, rules).shouldBeInstanceOf<FilterOutcome.Passed<LlmRequest>>()

        val sent = passed.value.toWireText()
        sent shouldNotContain "1234567"
        sent.lowercase() shouldNotContain "musterstraße 5"
        sent shouldContain """{"text":"$REDACTION"}"""
        passed.redactions shouldBe 5
        // "STRASSE" is not "straße": the filter does not guess spellings, it matches the value.
        sent shouldContain "MUSTERSTRASSE"
    }

    @Test
    fun `values are matched after Unicode normalisation, longest first`() {
        val decomposed = "Musterstraße 5, 12345 Berlin".replace("ß", "ß")
        val combining = "José García"
        val nfcRules = NeverSendRules(emptyMap(), setOf(FlaggedValue("José García"), FlaggedValue("José")))
        val request = LlmRequest(AiTask.CHAT, listOf(LlmMessage.User("Hi $combining and $decomposed")))

        val passed = NeverSendFilter.apply(request, nfcRules).shouldBeInstanceOf<FilterOutcome.Passed<LlmRequest>>()

        passed.value.toWireText() shouldBe "Hi $REDACTION and $decomposed"
        passed.redactions shouldBe 1
    }

    @Test
    fun `a source without a verdict makes the whole request undecided`() {
        val unknown = ContentSource(ContentSourceType.KNOWLEDGE_ENTRY, "unknown")
        val request =
            LlmRequest(AiTask.EXTRACTION, listOf(LlmMessage.User(listOf(ContentPart.Sourced("anything", unknown)))))

        NeverSendFilter.apply(request, rules) shouldBe FilterOutcome.Undecided(1)
        NeverSendFilter.apply(request, NeverSendRules.NONE) shouldBe FilterOutcome.Undecided(1)
        NeverSendFilter.sourcesOf(request) shouldBe setOf(unknown)
    }

    @Test
    fun `an embedding of a flagged source is withheld, other inputs are redacted`() {
        val flagged = EmbeddingRequest(listOf(ContentPart.Plain("posting"), ContentPart.Sourced(flaggedText, address)))
        NeverSendFilter.apply(flagged, rules) shouldBe FilterOutcome.Withheld

        val undecided =
            EmbeddingRequest(listOf(ContentPart.Sourced("x", ContentSource(ContentSourceType.KNOWLEDGE_ENTRY, "?"))))
        NeverSendFilter.apply(undecided, rules) shouldBe FilterOutcome.Undecided(1)

        val mixed =
            EmbeddingRequest(listOf(ContentPart.Sourced("Kotlin", skills), ContentPart.Plain("call +49 170 1234567")))
        val passed = NeverSendFilter.apply(mixed, rules).shouldBeInstanceOf<FilterOutcome.Passed<EmbeddingRequest>>()
        passed.value.texts shouldBe listOf("Kotlin", "call $REDACTION")
        passed.value.inputs.all { it is ContentPart.Plain } shouldBe true
        passed.redactions shouldBe 1
    }

    @Test
    fun `without flags a request passes unchanged`() {
        val request =
            LlmRequest(
                AiTask.CHAT,
                listOf(LlmMessage.System("Be brief"), LlmMessage.User("Hello"), LlmMessage.ToolResult("c", "")),
            )

        NeverSendFilter.apply(request, NeverSendRules.NONE) shouldBe FilterOutcome.Passed(request, 0)
    }

    @Test
    fun `rules and parts never print their content`() {
        rules.toString() shouldNotContain "Muster"
        phone.toString() shouldNotContain "170"
        ContentPart.Sourced(flaggedText, address).toString() shouldNotContain "Muster"
        ContentPart.Plain(flaggedText).toString() shouldNotContain "Muster"
    }

    private fun partsOf(message: LlmMessage): List<ContentPart> =
        when (message) {
            is LlmMessage.System -> message.parts
            is LlmMessage.User -> message.parts
            is LlmMessage.ToolResult -> message.parts
            is LlmMessage.Assistant -> listOf(ContentPart.Plain(message.text))
        }

    /** Everything a provider adapter would put on the wire. */
    private fun LlmRequest.toWireText(): String =
        messages.joinToString("\n") { message ->
            when (message) {
                is LlmMessage.System -> message.text
                is LlmMessage.User -> message.text
                is LlmMessage.ToolResult -> message.content
                is LlmMessage.Assistant -> message.text + message.toolCalls.joinToString { it.arguments }
            }
        }
}
