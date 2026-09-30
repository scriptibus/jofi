// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.application.port.ModelCatalogPort
import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * [ModelCatalogPort] over the providers' model listings (`GET /models` of the OpenAI and
 * OpenAI-compatible APIs, Gemini and Mistral included, and of Anthropic) and the [CapabilityTable].
 * Anthropic also reports each model's input limit, which then replaces the table's context size.
 */
class ModelCatalogAdapter(
    private val models: ProviderModels,
    secrets: SecretStorePort,
    private val clock: Clock,
) : ModelCatalogPort {
    private val keys = ApiKeys(secrets)

    override fun detect(provider: ProviderConfig): AiResult<List<ModelCapabilityProfile>> {
        val result =
            try {
                when (val key = keys.of(provider)) {
                    is ApiKeys.Lookup.Failed -> key.result
                    is ApiKeys.Lookup.Found -> list(provider, key.key)
                }
            } catch (failure: Exception) {
                // Listing has no task of its own; CHAT only labels a CapabilityMissing answer.
                ProviderFailures.map(failure, AiTask.CHAT)
            } catch (broken: LinkageError) {
                ProviderFailures.brokenClasspath(broken)
            }
        log.info("Model listing of {} ended: {}", provider.kind, result.javaClass.simpleName)
        return result
    }

    override fun knownCapabilities(
        kind: ProviderKind,
        model: ModelName,
    ): ModelCapabilities = CapabilityTable.of(kind, model)

    private fun list(
        provider: ProviderConfig,
        key: SecretValue?,
    ): AiResult<List<ModelCapabilityProfile>> {
        val listed =
            if (provider.kind ==
                ProviderKind.ANTHROPIC
            ) {
                anthropicModels(provider, key)
            } else {
                openAiModels(provider, key)
            }
        val now = clock.instant()
        return AiResult.Success(
            listed.distinctBy { it.name }.map { model ->
                ModelCapabilityProfile(
                    provider.id,
                    model.name,
                    capabilities(provider.kind, model),
                    CapabilitySource.DETECTED,
                    now,
                )
            },
        )
    }

    private fun openAiModels(
        provider: ProviderConfig,
        key: SecretValue?,
    ): List<ListedModel> =
        models
            .openAi(provider, key)
            .models()
            .list()
            .autoPager()
            .asSequence()
            .take(MAX_MODELS)
            .map { ListedModel(ModelName(it.id().removePrefix(GEMINI_PREFIX)), null) }
            .toList()

    private fun anthropicModels(
        provider: ProviderConfig,
        key: SecretValue?,
    ): List<ListedModel> =
        models
            .anthropic(provider, key)
            .models()
            .list()
            .autoPager()
            .asSequence()
            .take(MAX_MODELS)
            .map { ListedModel(ModelName(it.id()), it.maxInputTokens().orElse(null)) }
            .toList()

    private fun capabilities(
        kind: ProviderKind,
        model: ListedModel,
    ): ModelCapabilities {
        val known = CapabilityTable.of(kind, model.name)
        val reported = model.maxInputTokens?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return known
        return ModelCapabilities(
            known.supported
                .filterNot {
                    it is Capability.ContextSize
                }.toSet() + Capability.ContextSize(reported),
        )
    }

    private class ListedModel(
        val name: ModelName,
        val maxInputTokens: Long?,
    )

    private companion object {
        /** A provider lists a few hundred models at most; the cap bounds a misbehaving endpoint's paging. */
        const val MAX_MODELS = 1_000

        /** Gemini lists `models/gemini-...` but expects the bare name in requests. */
        const val GEMINI_PREFIX = "models/"
        val log: Logger = LoggerFactory.getLogger(ModelCatalogAdapter::class.java)
    }
}
