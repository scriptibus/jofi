// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

import io.github.scriptibus.jofi.shared.domain.ai.NeverSendFilter.REDACTION
import io.github.scriptibus.jofi.shared.domain.text.TextExcerpt
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** Redact first, then cut (ADR-0056): the excerpt never shows half a marker, and a marker is never nested. */
class RedactedExcerptTest {
    private val phone = "0170 1234567"
    private val rules = NeverSendRules(emptyMap(), setOf(FlaggedValue(phone)))

    private fun excerptOf(
        text: String,
        rules: NeverSendRules = this.rules,
    ) = TextExcerpt.of(NeverSendFilter.redactText(text, rules))

    @Test
    fun `an excerpt never ends inside the marker, whichever way the cut falls`() {
        for (before in TextExcerpt.MAX_LENGTH - REDACTION.length - 2..TextExcerpt.MAX_LENGTH + 2) {
            val text = "x".repeat(before) + " $phone and more"

            val excerpt = excerptOf(text)

            excerpt.text shouldNotContain "0170"
            // Every "[" that is left starts a whole marker.
            excerpt.text.count { it == '[' } shouldBe Regex(Regex.escape(REDACTION)).findAll(excerpt.text).count()
            (excerpt.text.codePointCount(0, excerpt.text.length) <= TextExcerpt.MAX_LENGTH) shouldBe true
        }
    }

    @Test
    fun `a marker that fits is kept whole, one that does not fit is left out`() {
        val fits = "x".repeat(TextExcerpt.MAX_LENGTH - REDACTION.length - 1) + " $phone and more"
        val straddles = "x".repeat(TextExcerpt.MAX_LENGTH - 5) + " $phone and more"

        excerptOf(fits) shouldBe
            TextExcerpt("x".repeat(TextExcerpt.MAX_LENGTH - REDACTION.length - 1) + " $REDACTION", true)
        excerptOf(straddles) shouldBe TextExcerpt("x".repeat(TextExcerpt.MAX_LENGTH - 5) + " ", true)
    }

    @Test
    fun `a text cut at the marker's end keeps it, and the flag says the text was cut`() {
        val text = "x".repeat(TextExcerpt.MAX_LENGTH - REDACTION.length) + REDACTION + "tail"

        excerptOf(text) shouldBe TextExcerpt("x".repeat(TextExcerpt.MAX_LENGTH - REDACTION.length) + REDACTION, true)
    }

    @Test
    fun `scanning redacted text again changes nothing, even for a value that is part of the marker`() {
        for (flagged in listOf("held", "with", "[with", "withheld]", "[withheld]", "hel")) {
            val flags = NeverSendRules(emptyMap(), setOf(FlaggedValue(flagged), FlaggedValue(phone)))
            val once = NeverSendFilter.redactText("Call $phone now", flags)

            val twice = NeverSendFilter.redactText(once, flags)
            val asResult = NeverSendFilter.applyToToolResult("\"$once\"", flags).value

            twice shouldBe once
            asResult shouldBe "\"$once\""
            twice shouldNotContain "[with[withheld]"
        }
    }

    @Test
    fun `a value that matches inside an existing marker merges with it, one marker results`() {
        val flags = NeverSendRules(emptyMap(), setOf(FlaggedValue("held")))

        NeverSendFilter.redactText("Call $REDACTION now", flags) shouldBe "Call $REDACTION now"
        NeverSendFilter.redactText("Call held and $REDACTION", flags) shouldBe "Call $REDACTION and $REDACTION"
    }
}
