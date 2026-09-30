// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.domain.CapabilityName
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupField
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Duration

class SetupDtosTest {
    @Test
    fun `the API enums are complete copies of the domain's`() {
        ProviderKind.entries.map { AiProviderType.from(it).toDomain() } shouldBe ProviderKind.entries
        AiProviderType.entries.size shouldBe ProviderKind.entries.size
        AiTask.entries.map { AiTaskType.from(it).toDomain() } shouldBe AiTask.entries
        AiTaskType.entries.size shouldBe AiTask.entries.size
        CapabilityName.entries.map { ModelFeature.from(it).toDomain() } shouldBe CapabilityName.entries
        ModelFeature.entries.size shouldBe CapabilityName.entries.size
    }

    @Test
    fun `requests never print the key`() {
        CreateProviderRequest(AiProviderType.OPENAI, "OpenAI", null, "sk-secret").toString() shouldNotContain
            "sk-secret"
        UpdateProviderRequest("OpenAI", null, "sk-secret").toString() shouldNotContain "sk-secret"
    }

    @Test
    fun `every field has a request name and every provider failure a problem type`() {
        SetupField.entries.map(SetupProblems::apiName) shouldBe
            listOf("displayName", "baseUrl", "apiKey", "model", "contextWindowTokens")
        val types =
            listOf(
                AiResult.RateLimited(Duration.ofSeconds(1)),
                AiResult.Unavailable,
                AiResult.Rejected(400),
                AiResult.ContextTooLong,
            ).map {
                SetupProblems
                    .of(SetupResult.ProviderFailed(it))
                    .body.type
                    .toString()
            }
        types shouldBe
            listOf(
                SetupProblems.RATE_LIMITED,
                SetupProblems.UNREACHABLE,
                SetupProblems.REJECTED,
                SetupProblems.REJECTED,
            )
        SetupProblems.of(SetupResult.Forbidden).statusCode.value() shouldBe 403
    }
}
