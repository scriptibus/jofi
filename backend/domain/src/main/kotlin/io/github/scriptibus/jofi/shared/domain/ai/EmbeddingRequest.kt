// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/**
 * Embed each of [inputs] for [task] (semantic search over knowledge, postings, documents). An input
 * taken from a stored item is a [ContentPart.Sourced] part, so the AI gateway can refuse to embed an
 * item flagged "never send to AI" (ADR-0043).
 */
data class EmbeddingRequest(
    val inputs: List<ContentPart>,
    val task: AiTask = AiTask.EMBEDDING,
) {
    init {
        require(task.kind == AiTaskKind.EMBEDDING) { "Task $task is not an embedding task" }
        require(inputs.isNotEmpty()) { "An embedding request needs at least one text" }
        require(inputs.none { it.text.isBlank() }) { "Texts to embed must not be blank" }
    }

    /** The texts to embed, in request order. */
    val texts: List<String> get() = inputs.map { it.text }

    /** Sizes only: the texts hold personal data (threat model T4). */
    override fun toString(): String =
        "EmbeddingRequest(task=$task, texts=${inputs.size}, chars=${inputs.sumOf { it.text.length }})"

    companion object {
        /** A request for texts without a stored source (postings, search queries). */
        fun ofTexts(
            texts: List<String>,
            task: AiTask = AiTask.EMBEDDING,
        ): EmbeddingRequest = EmbeddingRequest(texts.map(ContentPart::Plain), task)
    }
}

/** One vector per input text, in request order, plus the tokens the call consumed. */
data class EmbeddingResponse(
    val embeddings: List<Embedding>,
    val usage: TokenUsage,
) {
    init {
        require(embeddings.map { it.dimensions }.distinct().size <= 1) { "All embeddings must have the same size" }
    }
}

/** A single embedding vector. */
data class Embedding(
    val vector: List<Float>,
) {
    init {
        require(vector.isNotEmpty()) { "An embedding must not be empty" }
    }

    val dimensions: Int get() = vector.size
}
