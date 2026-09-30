// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.application.port.AiProviderPort
import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.Embedding
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingRequest
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingResponse
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.LlmResponse
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest as SpringEmbeddingRequest

/**
 * [AiProviderPort] with Spring AI (ADR-0011, ADR-0032, ADR-0037): sends each request to exactly
 * the given provider and model, with the key read through [secrets]. No routing, filtering or
 * metering (the gateway, #20, does that). Every failure is an [AiResult]; no exception and no
 * Spring AI or SDK type leaves this class. Logs name the provider kind, the model and the result
 * kind only, never prompts, answers or keys (T4).
 */
class SpringAiProviderAdapter(
    private val models: ProviderModels,
    secrets: SecretStorePort,
) : AiProviderPort {
    private val keys = ApiKeys(secrets)

    override fun complete(
        target: ResolvedModel,
        request: LlmRequest,
    ): AiResult<LlmResponse> =
        call(target, request.task) { key ->
            val chat = models.chat(target, key, request)
            val response = chat.model.call(Prompt(PromptMapper.messages(request.messages), chat.options))
            AiResult.Success(ResponseAccumulator.of(response))
        }

    override fun stream(
        target: ResolvedModel,
        request: LlmRequest,
        isCancelled: () -> Boolean,
        onTextDelta: (String) -> Unit,
    ): AiResult<LlmResponse> =
        call(target, request.task) { key ->
            val chat = models.chat(target, key, request)
            val fragments = chat.model.stream(Prompt(PromptMapper.messages(request.messages), chat.options))
            StreamCollector.collect(fragments, isCancelled, onTextDelta)
        }

    override fun embed(
        target: ResolvedModel,
        request: EmbeddingRequest,
    ): AiResult<EmbeddingResponse> =
        call(target, request.task) { key ->
            models.embedding(target, key)?.let { embed(it, request) } ?: AiResult.CapabilityMissing(request.task)
        }

    private fun embed(
        model: EmbeddingModel,
        request: EmbeddingRequest,
    ): AiResult<EmbeddingResponse> {
        val response = model.call(SpringEmbeddingRequest(request.texts, null))
        val vectors = response.results.sortedBy { it.index }.map { Embedding(it.output.toList()) }
        if (vectors.size != request.texts.size) return AiResult.Rejected(null)
        val inputTokens =
            response.metadata.usage.promptTokens
                .toLong()
        return AiResult.Success(EmbeddingResponse(vectors, TokenUsage(inputTokens, 0)))
    }

    private fun <T> call(
        target: ResolvedModel,
        task: AiTask,
        block: (SecretValue?) -> AiResult<T>,
    ): AiResult<T> {
        val result =
            invoke(task) {
                when (val key = keys.of(target.provider)) {
                    is ApiKeys.Lookup.Found -> block(key.key)
                    is ApiKeys.Lookup.Failed -> key.result
                }
            }
        if (result !is AiResult.Success) {
            log.info("AI call to {} ({}) ended: {}", target.provider.kind, target.model.value, result.kind())
        }
        return result
    }

    private fun <T> invoke(
        task: AiTask,
        block: () -> AiResult<T>,
    ): AiResult<T> =
        try {
            block()
        } catch (failure: Exception) {
            ProviderFailures.map(failure, task)
        } catch (broken: LinkageError) {
            ProviderFailures.brokenClasspath(broken)
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(SpringAiProviderAdapter::class.java)

        fun AiResult<*>.kind(): String = javaClass.simpleName
    }
}
