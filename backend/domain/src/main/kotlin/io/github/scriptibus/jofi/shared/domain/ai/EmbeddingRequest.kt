// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/** Embed each of [texts] for [task] (semantic search over knowledge, postings, documents). */
data class EmbeddingRequest(
    val texts: List<String>,
    val task: AiTask = AiTask.EMBEDDING,
) {
    init {
        require(task.kind == AiTaskKind.EMBEDDING) { "Task $task is not an embedding task" }
        require(texts.isNotEmpty()) { "An embedding request needs at least one text" }
        require(texts.none { it.isBlank() }) { "Texts to embed must not be blank" }
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
