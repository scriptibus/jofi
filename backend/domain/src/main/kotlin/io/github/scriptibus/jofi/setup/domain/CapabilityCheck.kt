// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.AiTask

/** The model assigned to [task] lacks [missing]. A warning, not an error: the user may still choose it. */
data class CapabilityWarning(
    val task: AiTask,
    val missing: Capability,
)

/**
 * Domain service: which capabilities each [AiTask] needs and which of them an assigned model lacks
 * (spec §3.2: "Jofi warns when a task is assigned to a model lacking a needed capability").
 *
 * Tools only for the tasks that may use them ([AiTask.toolsAllowed]); streaming for the interactive
 * and voice tasks; long context where whole documents, CVs or conversations go in.
 */
object CapabilityCheck {
    /** Enough for a posting plus a short profile summary. */
    const val BASIC_CONTEXT_TOKENS = 8_192

    /** Enough for a CV, a full posting and knowledge excerpts, or a long conversation. */
    const val LONG_CONTEXT_TOKENS = 32_768

    private val basicContext = Capability.ContextSize(BASIC_CONTEXT_TOKENS)
    private val longContext = Capability.ContextSize(LONG_CONTEXT_TOKENS)

    private val requirements: Map<AiTask, Set<Capability>> =
        mapOf(
            AiTask.SCANNER_PRE_SCORING to setOf(basicContext),
            AiTask.CLASSIFICATION to setOf(basicContext),
            AiTask.LANGUAGE_TONE_DETECTION to setOf(basicContext),
            AiTask.EXTRACTION to setOf(longContext),
            AiTask.KNOWLEDGE_INTERVIEW to setOf(Capability.ToolUse, Capability.Streaming, longContext),
            AiTask.DOCUMENT_GENERATION to setOf(longContext),
            AiTask.INTERVIEW_TRAINING to setOf(Capability.Streaming, longContext),
            AiTask.CHAT to setOf(Capability.ToolUse, Capability.Streaming, longContext),
            AiTask.EMBEDDING to setOf(Capability.Embedding),
            AiTask.SPEECH_TO_TEXT to setOf(Capability.SpeechToText, Capability.Streaming),
            AiTask.TEXT_TO_SPEECH to setOf(Capability.TextToSpeech, Capability.Streaming),
        )

    /** The capabilities [task] needs. */
    fun requiredFor(task: AiTask): Set<Capability> = requirements.getValue(task)

    /** One warning per required capability the assigned model does not meet; empty when it fits. */
    fun warningsFor(assignment: ModelAssignment): List<CapabilityWarning> =
        requiredFor(assignment.task)
            .filterNot(assignment.capabilities::meets)
            .map { CapabilityWarning(assignment.task, it) }
}
