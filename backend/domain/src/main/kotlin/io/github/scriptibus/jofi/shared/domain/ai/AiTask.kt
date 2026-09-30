// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/**
 * Every job Jofi gives to an AI model (spec §3.2). Each AI port request carries its task, so
 * per-task routing, capability checks, cost metering and the "never send to AI" filter can hook in
 * without changing callers. The task lives in the shared kernel because every context names the
 * task it runs; the `setup` context maps tasks to models (ADR-0032).
 *
 * Names are stored in the database: renaming or removing one needs a migration.
 *
 * [toolsAllowed] is false for every task that reads untrusted input (postings, pages, uploads):
 * such pipelines never get tools (threat model T2), and [LlmRequest] enforces it.
 */
enum class AiTask(
    val kind: AiTaskKind,
    val toolsAllowed: Boolean = false,
) {
    SCANNER_PRE_SCORING(AiTaskKind.TEXT_GENERATION),
    CLASSIFICATION(AiTaskKind.TEXT_GENERATION),
    LANGUAGE_TONE_DETECTION(AiTaskKind.TEXT_GENERATION),
    EXTRACTION(AiTaskKind.TEXT_GENERATION),
    KNOWLEDGE_INTERVIEW(AiTaskKind.TEXT_GENERATION, toolsAllowed = true),
    DOCUMENT_GENERATION(AiTaskKind.TEXT_GENERATION),
    INTERVIEW_TRAINING(AiTaskKind.TEXT_GENERATION),
    CHAT(AiTaskKind.TEXT_GENERATION, toolsAllowed = true),
    EMBEDDING(AiTaskKind.EMBEDDING),
    SPEECH_TO_TEXT(AiTaskKind.SPEECH_TO_TEXT),
    TEXT_TO_SPEECH(AiTaskKind.TEXT_TO_SPEECH),
}

/** Which kind of model call a task needs, and therefore which port serves it. */
enum class AiTaskKind {
    /** [LlmRequest] through `LlmPort`. */
    TEXT_GENERATION,

    /** [EmbeddingRequest] through `EmbeddingPort`. */
    EMBEDDING,

    /** Streaming voice ports, added with the voice work (M5). */
    SPEECH_TO_TEXT,

    /** Streaming voice ports, added with the voice work (M5). */
    TEXT_TO_SPEECH,
}
