// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UserTextTest {
    @Test
    fun `text is normalized to NFC and blank optional text is absent`() {
        "Jörg".normalizedText() shouldBe "Jörg"
        " Jörg ".trimmedOrNull() shouldBe "Jörg"
        " \t ".trimmedOrNull() shouldBe null
        null.trimmedOrNull() shouldBe null
    }

    @Test
    fun `stored text is trimmed, not blank, without U+0000 and within its limit`() {
        textProblem("Berlin", 6) shouldBe null
        textProblem("Berlin", 5) shouldBe TextProblem.TOO_LONG
        textProblem(" Berlin", 10) shouldBe TextProblem.BLANK_OR_UNTRIMMED
        textProblem("", 10) shouldBe TextProblem.BLANK_OR_UNTRIMMED
        textProblem("Ber\u0000lin", 10) shouldBe TextProblem.UNSTORABLE_CHARACTER
        "a\u0000".hasUnstorableCharacter() shouldBe true
    }
}
