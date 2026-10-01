// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application

import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.FilteredToolResult
import io.github.scriptibus.jofi.shared.domain.ai.FlaggedValue
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendFilter.REDACTION
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FilterToolResultUseCaseTest {
    @Test
    fun `flagged values are withheld from a tool result`() {
        val flags = NeverSendRules(emptyMap(), setOf(FlaggedValue("0170 1234567")))
        val useCase = FilterToolResultUseCase(visibility(AiVisibilityResult.Known(flags)))

        val result = useCase.execute("""{"notes":"Call 0170-1234567 first"}""")

        result shouldBe FilteredToolResult.Passed("""{"notes":"Call $REDACTION first"}""", 1)
    }

    @Test
    fun `nothing leaves when the flags cannot be read`() {
        val useCase = FilterToolResultUseCase(visibility(AiVisibilityResult.Unavailable("index not ready")))

        useCase.execute("""{"notes":"anything"}""") shouldBe FilteredToolResult.Refused
    }

    @Test
    fun `a passed result never prints its content`() {
        FilteredToolResult.Passed("""{"notes":"secret"}""", 0).toString() shouldBe "Passed(chars=18, redactions=0)"
    }

    private fun visibility(answer: AiVisibilityResult): AiVisibilityPort =
        object : AiVisibilityPort {
            override fun rulesFor(sources: Set<ContentSource>): AiVisibilityResult = answer
        }
}
