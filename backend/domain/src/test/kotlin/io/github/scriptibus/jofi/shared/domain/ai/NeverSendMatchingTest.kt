// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

import io.github.scriptibus.jofi.shared.domain.ai.NeverSendFilter.REDACTION
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** How flagged values are found: separators, invisible characters, digits, overlaps, short values, JSON. */
class NeverSendMatchingTest {
    private val name = FlaggedValue("Anna Schmidt")
    private val street = FlaggedValue("Schmidt Str. 5")
    private val phone = FlaggedValue("0170 1234567")
    private val rules = NeverSendRules(emptyMap(), setOf(name, street, phone, FlaggedValue("Kai")))

    private fun sent(vararg parts: ContentPart): FilterOutcome.Passed<LlmRequest> =
        NeverSendFilter
            .apply(LlmRequest(AiTask.CHAT, listOf(LlmMessage.User(parts.toList()))), rules)
            .shouldBeInstanceOf<FilterOutcome.Passed<LlmRequest>>()

    private fun sentText(text: String): String =
        (sent(ContentPart.Plain(text)).value.messages.single() as LlmMessage.User).text

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Anna Schmidt", // no-break space
            "Anna Schmidt", // narrow no-break space
            "Anna Schmidt", // thin space
            "Anna 　 Schmidt", // ideographic space
            "An​na Schmidt", // zero-width space
            "Anna Sch‍midt", // zero-width joiner
            "Anna﻿ Schmidt", // byte order mark
            "ＡＮＮＡ ｓｃｈｍｉｄｔ", // full-width letters
        ],
    )
    fun `separators and invisible characters do not hide a value`(written: String) {
        sentText("Kontakt: $written.") shouldBe "Kontakt: $REDACTION."
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "0170 1234567",
            "0170-1234567",
            "0170/1234567",
            "0170.123.45.67",
            "0170 123 45 67",
            "０１７０ １２３４５６７", // full-width digits
            "0170​1234567",
        ],
    )
    fun `a phone number is found however it is written`(written: String) {
        sentText("Ruf $written an") shouldBe "Ruf $REDACTION an"
    }

    @Test
    fun `an international prefix leaves only the prefix`() {
        sentText("Tel. +49 (0)170 1234567") shouldBe "Tel. +49 ($REDACTION"
    }

    @Test
    fun `a value split across parts is found in the text the provider receives`() {
        val passed = sent(ContentPart.Plain("Ruf 0170 12"), ContentPart.Plain("34567 an"))

        (passed.value.messages.single() as LlmMessage.User).text shouldBe "Ruf $REDACTION an"
        passed.redactions shouldBe 1
    }

    @Test
    fun `overlapping values are withheld as one piece, and a marker is never redacted again`() {
        sentText("Brief an Anna Schmidt Str. 5, Berlin") shouldBe "Brief an $REDACTION, Berlin"

        val withHeld = NeverSendRules(mapOf(SOURCE to AiVisibility.NEVER_SEND), setOf(FlaggedValue("held")))
        val request = LlmRequest(AiTask.CHAT, listOf(LlmMessage.User(listOf(ContentPart.Sourced("x", SOURCE)))))
        val passed = NeverSendFilter.apply(request, withHeld).shouldBeInstanceOf<FilterOutcome.Passed<LlmRequest>>()
        (passed.value.messages.single() as LlmMessage.User).text shouldBe REDACTION
        passed.redactions shouldBe 1
    }

    @Test
    fun `a short value only matches as a whole word`() {
        sentText("Kai schreibt aus Kaiserslautern, KAI.") shouldBe "$REDACTION schreibt aus Kaiserslautern, $REDACTION."
    }

    @Test
    fun `values in tool call arguments are redacted inside JSON strings and numbers, keeping the JSON valid`() {
        val arguments = """{"note":"Tel. 0170 1234567 \"privat\"","who":"Anna Schmidt","n":1701234567,"ok":true}"""
        val numberRules = NeverSendRules(emptyMap(), setOf(phone, name, FlaggedValue("170 1234567")))
        val request =
            LlmRequest(
                AiTask.CHAT,
                listOf(
                    LlmMessage.User("Notiz"),
                    LlmMessage.Assistant("", listOf(ToolCall("c", "save_note", arguments))),
                ),
            )

        val passed = NeverSendFilter.apply(request, numberRules).shouldBeInstanceOf<FilterOutcome.Passed<LlmRequest>>()

        val sent = (passed.value.messages[1] as LlmMessage.Assistant).toolCalls.single().arguments
        sent shouldBe """{"note":"Tel. $REDACTION \"privat\"","who":"$REDACTION","n":"$REDACTION","ok":true}"""
    }

    @Test
    fun `escaped characters in JSON strings do not hide a value`() {
        val escaped = NeverSendRules(emptyMap(), setOf(FlaggedValue("Musterstraße 5")))
        val call = ToolCall("c", "save", """{"street":"Musterstraße 5"}""")
        val request = LlmRequest(AiTask.CHAT, listOf(LlmMessage.User("x"), LlmMessage.Assistant("", listOf(call))))

        val passed = NeverSendFilter.apply(request, escaped).shouldBeInstanceOf<FilterOutcome.Passed<LlmRequest>>()

        (passed.value.messages[1] as LlmMessage.Assistant).toolCalls.single().arguments shouldBe
            """{"street":"$REDACTION"}"""
    }

    @Test
    fun `broken JSON is redacted as plain text`() {
        val call = ToolCall("c", "save", """{"note":"0170 1234567""")
        val request = LlmRequest(AiTask.CHAT, listOf(LlmMessage.User("x"), LlmMessage.Assistant("", listOf(call))))

        val passed = NeverSendFilter.apply(request, rules).shouldBeInstanceOf<FilterOutcome.Passed<LlmRequest>>()

        (passed.value.messages[1] as LlmMessage.Assistant).toolCalls.single().arguments shouldBe
            """{"note":"$REDACTION"""
    }

    @Test
    fun `tool descriptions and schemas are scanned too`() {
        val tool =
            ToolDefinition(
                "find_contact",
                "Finds Anna Schmidt",
                """{"type":"object","description":"e.g. 0170 1234567"}""",
            )
        val request = LlmRequest(AiTask.CHAT, listOf(LlmMessage.User("x")), tools = listOf(tool))

        val passed = NeverSendFilter.apply(request, rules).shouldBeInstanceOf<FilterOutcome.Passed<LlmRequest>>()

        passed.value.tools.single() shouldBe
            ToolDefinition("find_contact", "Finds $REDACTION", """{"type":"object","description":"e.g. $REDACTION"}""")
        passed.redactions shouldBe 2
    }

    @Test
    fun `a flagged value needs a letter or digit`() {
        shouldThrow<IllegalArgumentException> { FlaggedValue("​") }
        shouldThrow<IllegalArgumentException> { FlaggedValue(" - ") }
    }

    private companion object {
        val SOURCE = ContentSource(ContentSourceType.KNOWLEDGE_ENTRY, "entry")
    }
}
