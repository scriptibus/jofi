// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.AnthropicClientAsyncImpl
import com.anthropic.client.AnthropicClientImpl
import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientAsyncImpl
import com.openai.client.OpenAIClientImpl
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.anthropic.core.ClientOptions as AnthropicClientOptions
import com.anthropic.core.LogLevel as AnthropicLogLevel
import com.anthropic.core.http.HttpClient as AnthropicHttpClient
import com.openai.core.ClientOptions as OpenAiClientOptions
import com.openai.core.LogLevel as OpenAiLogLevel
import com.openai.core.http.HttpClient as OpenAiHttpClient

/** A Spring AI chat model for one call, with the options the prompt must carry. */
internal class ChatCall(
    val model: ChatModel,
    val options: ChatOptions,
)

/**
 * Builds the vendor SDK clients and Spring AI models for one call to one provider (ADR-0037). The
 * SDK clients always get Jofi's guarded transports ([openAiHttpClient], [anthropicHttpClient], from
 * `adapters/net`), so Spring AI never builds a client of its own. SDK retries are off (the caller
 * decides about retries from the sealed result), SDK logging is off whatever `OPENAI_LOG` or
 * `ANTHROPIC_LOG` say (T4), and nothing is read from the environment. Anthropic has no embeddings.
 */
class ProviderModels(
    private val openAiHttpClient: OpenAiHttpClient,
    private val anthropicHttpClient: AnthropicHttpClient,
    private val endpoints: ProviderEndpoints = ProviderEndpoints(),
) {
    internal fun chat(
        target: ResolvedModel,
        key: SecretValue?,
        request: LlmRequest,
    ): ChatCall =
        if (target.provider.kind == ProviderKind.ANTHROPIC) {
            anthropicChat(target, key, request)
        } else {
            openAiChat(target, key, request)
        }

    private fun anthropicChat(
        target: ResolvedModel,
        key: SecretValue?,
        request: LlmRequest,
    ): ChatCall {
        val options =
            AnthropicChatOptions
                .builder()
                .model(target.model.value)
                .maxTokens(request.maxOutputTokens ?: AnthropicChatOptions.DEFAULT_MAX_TOKENS)
                .toolCallbacks(PromptMapper.toolCallbacks(request.tools))
                .build()
        val client = anthropicOptions(target.provider, key)
        val model =
            AnthropicChatModel
                .builder()
                .anthropicClient(AnthropicClientImpl(client))
                .anthropicClientAsync(AnthropicClientAsyncImpl(client))
                .options(options)
                .build()
        return ChatCall(model, options)
    }

    private fun openAiChat(
        target: ResolvedModel,
        key: SecretValue?,
        request: LlmRequest,
    ): ChatCall {
        val options =
            OpenAiChatOptions
                .builder()
                .model(target.model.value)
                .maxTokens(request.maxOutputTokens)
                .toolCallbacks(PromptMapper.toolCallbacks(request.tools))
                .build()
        val client = openAiOptions(target.provider, key)
        val model =
            OpenAiChatModel
                .builder()
                .openAiClient(OpenAIClientImpl(client))
                .openAiClientAsync(OpenAIClientAsyncImpl(client))
                .options(options)
                .build()
        return ChatCall(model, options)
    }

    /** Null for Anthropic, which offers no embedding models. */
    internal fun embedding(
        target: ResolvedModel,
        key: SecretValue?,
    ): EmbeddingModel? {
        if (target.provider.kind == ProviderKind.ANTHROPIC) return null
        return OpenAiEmbeddingModel
            .builder()
            .openAiClient(openAi(target.provider, key))
            .options(OpenAiEmbeddingOptions.builder().model(target.model.value).build())
            .build()
    }

    internal fun openAi(
        provider: ProviderConfig,
        key: SecretValue?,
    ): OpenAIClient = OpenAIClientImpl(openAiOptions(provider, key))

    internal fun anthropic(
        provider: ProviderConfig,
        key: SecretValue?,
    ): AnthropicClient = AnthropicClientImpl(anthropicOptions(provider, key))

    private fun openAiOptions(
        provider: ProviderConfig,
        key: SecretValue?,
    ): OpenAiClientOptions =
        OpenAiClientOptions
            .builder()
            .httpClient(openAiHttpClient)
            .baseUrl(endpoints.of(provider).toString())
            // The SDK insists on a key; keyless local endpoints (Ollama, LM Studio) ignore this one.
            .apiKey(key?.reveal() ?: NO_KEY)
            .maxRetries(0)
            .logLevel(OpenAiLogLevel.OFF)
            .streamHandlerExecutor(STREAM_HANDLERS)
            .sleeper(SharedSleepers.openAi)
            .build()

    private fun anthropicOptions(
        provider: ProviderConfig,
        key: SecretValue?,
    ): AnthropicClientOptions =
        AnthropicClientOptions
            .builder()
            .httpClient(anthropicHttpClient)
            .baseUrl(endpoints.of(provider).toString())
            // What the SDK's default backend adds; Jofi's transport has no backend of its own.
            .putHeader(ANTHROPIC_KEY_HEADER, key?.reveal() ?: NO_KEY)
            .putHeader(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION)
            .maxRetries(0)
            .logLevel(AnthropicLogLevel.OFF)
            .streamHandlerExecutor(STREAM_HANDLERS)
            .sleeper(SharedSleepers.anthropic)
            .build()

    private companion object {
        const val NO_KEY = "no-key"
        const val ANTHROPIC_KEY_HEADER = "x-api-key"
        const val ANTHROPIC_VERSION_HEADER = "anthropic-version"

        /** The Messages API version the SDK's default backend sends. */
        const val ANTHROPIC_VERSION = "2023-06-01"

        /** Shared by every client, so short-lived per-call clients leave no thread pools behind. */
        val STREAM_HANDLERS: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    }
}
