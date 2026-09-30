// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration

class EmbeddingRequestTest {
    @ParameterizedTest
    @EnumSource(AiTask::class)
    fun `carries its task, and only embedding tasks are accepted`(task: AiTask) {
        if (task.kind == AiTaskKind.EMBEDDING) {
            EmbeddingRequest.ofTexts(listOf("Kotlin developer"), task).task shouldBe task
        } else {
            shouldThrow<IllegalArgumentException> { EmbeddingRequest.ofTexts(listOf("Kotlin developer"), task) }
        }
    }

    @Test
    fun `needs non-blank texts`() {
        EmbeddingRequest.ofTexts(listOf("a", "b")).task shouldBe AiTask.EMBEDDING
        shouldThrow<IllegalArgumentException> { EmbeddingRequest.ofTexts(emptyList()) }
        shouldThrow<IllegalArgumentException> { EmbeddingRequest.ofTexts(listOf("a", " ")) }
    }

    @Test
    fun `embeddings are non-empty vectors of one size`() {
        val response =
            EmbeddingResponse(listOf(Embedding(listOf(0.1f, 0.2f)), Embedding(listOf(0.3f, 0.4f))), TokenUsage(4, 0))

        response.embeddings.map { it.dimensions } shouldBe listOf(2, 2)
        shouldThrow<IllegalArgumentException> { Embedding(emptyList()) }
        shouldThrow<IllegalArgumentException> {
            EmbeddingResponse(listOf(Embedding(listOf(0.1f)), Embedding(listOf(0.1f, 0.2f))), TokenUsage.NONE)
        }
    }

    @Test
    fun `every AI failure is a value that names no content`() {
        val results: List<AiResult<String>> =
            listOf(
                AiResult.Success("ok"),
                AiResult.NotConfigured(AiTask.CHAT),
                AiResult.CapabilityMissing(AiTask.CHAT),
                AiResult.BudgetExceeded(AiTask.SCANNER_PRE_SCORING),
                AiResult.PrivacyFilterFailed(AiTask.CHAT),
                AiResult.Withheld(AiTask.EMBEDDING),
                AiResult.AuthenticationFailed,
                AiResult.RateLimited(Duration.ofSeconds(30)),
                AiResult.ContextTooLong,
                AiResult.Unavailable,
                AiResult.Cancelled,
                AiResult.Rejected(400),
            )

        results.map(::describe) shouldBe EXPECTED_DESCRIPTIONS
    }

    private fun describe(result: AiResult<String>): String =
        when (result) {
            is AiResult.Success -> {
                result.value
            }

            is AiResult.NotConfigured -> {
                result.task.name
            }

            is AiResult.CapabilityMissing -> {
                result.task.name
            }

            is AiResult.BudgetExceeded -> {
                result.task.name
            }

            is AiResult.PrivacyFilterFailed -> {
                result.task.name
            }

            is AiResult.Withheld -> {
                result.task.name
            }

            is AiResult.RateLimited -> {
                result.retryAfter.toString()
            }

            AiResult.AuthenticationFailed, AiResult.ContextTooLong, AiResult.Unavailable, AiResult.Cancelled -> {
                result.toString()
            }

            is AiResult.Rejected -> {
                result.statusCode.toString()
            }
        }

    private companion object {
        val EXPECTED_DESCRIPTIONS =
            listOf(
                "ok",
                "CHAT",
                "CHAT",
                "SCANNER_PRE_SCORING",
                "CHAT",
                "EMBEDDING",
                "AuthenticationFailed",
                "PT30S",
                "ContextTooLong",
                "Unavailable",
                "Cancelled",
                "400",
            )
    }
}
