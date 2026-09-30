// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.setup.domain.CapabilityCheck.BASIC_CONTEXT_TOKENS
import io.github.scriptibus.jofi.setup.domain.CapabilityCheck.LONG_CONTEXT_TOKENS
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID

class CapabilityCheckTest {
    @ParameterizedTest
    @MethodSource("requirementsPerTask")
    fun `every task has documented required capabilities`(
        task: AiTask,
        expected: Set<Capability>,
    ) {
        CapabilityCheck.requiredFor(task) shouldBe expected
    }

    @ParameterizedTest
    @EnumSource(AiTask::class)
    fun `a model that meets every requirement gets no warning`(task: AiTask) {
        CapabilityCheck.warningsFor(assignment(task, EVERYTHING)).shouldBeEmpty()
    }

    @ParameterizedTest
    @EnumSource(AiTask::class)
    fun `a model without capabilities gets one warning per requirement`(task: AiTask) {
        CapabilityCheck
            .warningsFor(
                assignment(task, ModelCapabilities.NONE),
            ).map { it.missing } shouldContainExactlyInAnyOrder
            CapabilityCheck.requiredFor(task)
    }

    @Test
    fun `a small local model assigned to chat warns about tool use and context size`() {
        val smallModel = ModelCapabilities(setOf(Capability.Streaming, Capability.ContextSize(BASIC_CONTEXT_TOKENS)))

        CapabilityCheck.warningsFor(assignment(AiTask.CHAT, smallModel)) shouldContainExactlyInAnyOrder
            listOf(
                CapabilityWarning(AiTask.CHAT, Capability.ToolUse),
                CapabilityWarning(AiTask.CHAT, Capability.ContextSize(LONG_CONTEXT_TOKENS)),
            )
        CapabilityCheck.warningsFor(assignment(AiTask.CLASSIFICATION, smallModel)).shouldBeEmpty()
    }

    @Test
    fun `context size is met by any window at least as large`() {
        val model = ModelCapabilities(setOf(Capability.ContextSize(LONG_CONTEXT_TOKENS)))

        model.meets(Capability.ContextSize(LONG_CONTEXT_TOKENS)) shouldBe true
        model.meets(Capability.ContextSize(BASIC_CONTEXT_TOKENS)) shouldBe true
        model.meets(Capability.ContextSize(LONG_CONTEXT_TOKENS + 1)) shouldBe false
        model.meets(Capability.ToolUse) shouldBe false
        ModelCapabilities.NONE.meets(Capability.ContextSize(1)) shouldBe false
        model.contextSize shouldBe Capability.ContextSize(LONG_CONTEXT_TOKENS)
    }

    @Test
    fun `a model has at most one positive context size`() {
        shouldThrow<IllegalArgumentException> { Capability.ContextSize(0) }
        shouldThrow<IllegalArgumentException> {
            ModelCapabilities(
                setOf(Capability.ContextSize(BASIC_CONTEXT_TOKENS), Capability.ContextSize(LONG_CONTEXT_TOKENS)),
            )
        }
    }

    @Test
    fun `a model name must not be blank`() {
        ModelName("claude-haiku").value shouldBe "claude-haiku"
        shouldThrow<IllegalArgumentException> { ModelName(" ") }
    }

    companion object {
        private val PROVIDER = ProviderId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        private val LONG = Capability.ContextSize(LONG_CONTEXT_TOKENS)
        private val BASIC = Capability.ContextSize(BASIC_CONTEXT_TOKENS)
        private val EVERYTHING =
            ModelCapabilities(
                setOf(
                    Capability.ToolUse,
                    Capability.Streaming,
                    Capability.SpeechToText,
                    Capability.TextToSpeech,
                    Capability.Embedding,
                    Capability.ContextSize(LONG_CONTEXT_TOKENS * 4),
                ),
            )

        private fun assignment(
            task: AiTask,
            capabilities: ModelCapabilities,
        ) = ModelAssignment(task, PROVIDER, ModelName("some-model"), capabilities)

        @JvmStatic
        fun requirementsPerTask(): List<Arguments> =
            listOf(
                Arguments.of(AiTask.SCANNER_PRE_SCORING, setOf(BASIC)),
                Arguments.of(AiTask.CLASSIFICATION, setOf(BASIC)),
                Arguments.of(AiTask.LANGUAGE_TONE_DETECTION, setOf(BASIC)),
                Arguments.of(AiTask.EXTRACTION, setOf(LONG)),
                Arguments.of(AiTask.KNOWLEDGE_INTERVIEW, setOf(Capability.ToolUse, Capability.Streaming, LONG)),
                Arguments.of(AiTask.DOCUMENT_GENERATION, setOf(LONG)),
                Arguments.of(AiTask.INTERVIEW_TRAINING, setOf(Capability.Streaming, LONG)),
                Arguments.of(AiTask.CHAT, setOf(Capability.ToolUse, Capability.Streaming, LONG)),
                Arguments.of(AiTask.EMBEDDING, setOf(Capability.Embedding)),
                Arguments.of(AiTask.SPEECH_TO_TEXT, setOf(Capability.SpeechToText, Capability.Streaming)),
                Arguments.of(AiTask.TEXT_TO_SPEECH, setOf(Capability.TextToSpeech, Capability.Streaming)),
            )
    }
}
