// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TextExcerptTest {
    private val emoji = "😀"

    @Test
    fun `a text of exactly the maximum length is kept whole`() {
        val text = "a".repeat(TextExcerpt.MAX_LENGTH)

        TextExcerpt.of(text) shouldBe TextExcerpt(text, false)
    }

    @Test
    fun `a text one code point longer is cut and flagged`() {
        val text = "a".repeat(TextExcerpt.MAX_LENGTH + 1)

        TextExcerpt.of(text) shouldBe TextExcerpt("a".repeat(TextExcerpt.MAX_LENGTH), true)
    }

    @Test
    fun `a cut never splits a surrogate pair`() {
        val text = "ab" + emoji + "cd"

        TextExcerpt.of(text, 3) shouldBe TextExcerpt("ab$emoji", true)
        TextExcerpt.of(text, 2) shouldBe TextExcerpt("ab", true)
    }

    @Test
    fun `a text of astral characters is measured in code points, not chars`() {
        val whole = emoji.repeat(TextExcerpt.MAX_LENGTH)
        TextExcerpt.of(whole) shouldBe TextExcerpt(whole, false)

        val cut = TextExcerpt.of(emoji.repeat(TextExcerpt.MAX_LENGTH + 5))
        cut.truncated shouldBe true
        cut.text shouldBe whole
    }

    @Test
    fun `an empty text and no text give an empty excerpt and none`() {
        TextExcerpt.of("") shouldBe TextExcerpt("", false)
        TextExcerpt.ofOrNull(null) shouldBe null
    }
}
