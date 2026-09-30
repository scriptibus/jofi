// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.application.port.AiProviderPort
import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.application.port.MonthlyBudgetPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.CostEntry
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingRequest
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingResponse
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.LlmResponse
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import java.time.Instant

/** The setup stores the gateway reads, in memory; [failing] makes every store call a storage failure. */
class InMemorySetup(
    var failing: Boolean = false,
) {
    val providers = mutableMapOf<ProviderId, ProviderConfig>()
    val assignments = mutableMapOf<AiTask, ModelAssignment>()
    val profiles = mutableMapOf<Pair<ProviderId, ModelName>, ModelCapabilityProfile>()
    var budget: MonthlyBudget? = null
    val costs = mutableListOf<CostEntry>()

    private fun <T> read(value: T?): SetupStoreResult<T> =
        when {
            failing -> SetupStoreResult.StorageFailure("read")
            value == null -> SetupStoreResult.NotFound
            else -> SetupStoreResult.Success(value)
        }

    private fun write(block: () -> Unit): SetupStoreResult<Unit> {
        if (failing) return SetupStoreResult.StorageFailure("write")
        block()
        return SetupStoreResult.Success(Unit)
    }

    val providerPort =
        object : ProviderConfigPort {
            override fun findAll() = read(providers.values.toList())

            override fun findById(id: ProviderId) = read(providers[id])

            override fun save(config: ProviderConfig) = write { providers[config.id] = config }

            override fun delete(
                id: ProviderId,
                proof: ConfirmationResult.Confirmed,
            ) = write { providers.remove(id) }
        }

    val assignmentPort =
        object : ModelAssignmentPort {
            override fun findAll() = read(assignments.values.toList())

            override fun findByTask(task: AiTask) = read(assignments[task])

            override fun save(assignment: ModelAssignment) = write { assignments[assignment.task] = assignment }

            override fun delete(task: AiTask) = write { assignments.remove(task) }
        }

    val capabilityPort =
        object : ModelCapabilityPort {
            override fun find(
                provider: ProviderId,
                model: ModelName,
            ) = read(profiles[provider to model])

            override fun findByProvider(provider: ProviderId) = read(profiles.values.filter { it.provider == provider })

            override fun save(profile: ModelCapabilityProfile) =
                write {
                    profiles[profile.provider to profile.model] =
                        profile
                }
        }

    val budgetPort =
        object : MonthlyBudgetPort {
            override fun find() = read(budget)

            override fun save(budget: MonthlyBudget) = write { this@InMemorySetup.budget = budget }

            override fun clear() = write { budget = null }
        }

    val costPort =
        object : CostEntryPort {
            override fun append(entry: CostEntry) = write { costs += entry }

            override fun findBetween(
                from: Instant,
                until: Instant,
            ) = read(costs.filter { it.occurredAt >= from && it.occurredAt < until })

            override fun totalBetween(
                from: Instant,
                until: Instant,
            ): SetupStoreResult<Money> =
                read(
                    costs
                        .filter { it.occurredAt >= from && it.occurredAt < until }
                        .mapNotNull { it.estimatedCost }
                        .fold(Money.usd(0), Money::plus),
                )
        }
}

/** A "never send to AI" source answering with fixed [rules], or [unavailable], counting its calls. */
class FakeVisibility(
    var rules: NeverSendRules = NeverSendRules.NONE,
    var unavailable: Boolean = false,
    var throwing: Boolean = false,
) : AiVisibilityPort {
    val asked = mutableListOf<Set<ContentSource>>()

    override fun rulesFor(sources: Set<ContentSource>): AiVisibilityResult {
        asked += sources
        if (throwing) error("index broken")
        if (unavailable) return AiVisibilityResult.Unavailable("test")
        return AiVisibilityResult.Known(
            NeverSendRules(rules.verdicts.filterKeys { it in sources }, rules.flaggedValues),
        )
    }
}

/** A provider that records every request it receives (the spy of the privacy tests) and answers [answer]. */
class SpyProvider(
    var answer: AiResult<LlmResponse> = AiResult.Success(LlmResponse("ok", emptyList(), STOP, TokenUsage(100, 20))),
    var embedding: AiResult<EmbeddingResponse> = AiResult.Unavailable,
    var streamUsage: List<TokenUsage> = emptyList(),
) : AiProviderPort {
    val llmRequests = mutableListOf<Pair<ResolvedModel, LlmRequest>>()
    val embeddingRequests = mutableListOf<Pair<ResolvedModel, EmbeddingRequest>>()
    val calls: Int get() = llmRequests.size + embeddingRequests.size

    override fun complete(
        target: ResolvedModel,
        request: LlmRequest,
    ): AiResult<LlmResponse> {
        llmRequests += target to request
        return answer
    }

    override fun stream(
        target: ResolvedModel,
        request: LlmRequest,
        isCancelled: () -> Boolean,
        onUsage: (TokenUsage) -> Unit,
        onTextDelta: (String) -> Unit,
    ): AiResult<LlmResponse> {
        llmRequests += target to request
        onTextDelta("o")
        streamUsage.forEach(onUsage)
        return answer
    }

    override fun embed(
        target: ResolvedModel,
        request: EmbeddingRequest,
    ): AiResult<EmbeddingResponse> {
        embeddingRequests += target to request
        return embedding
    }

    private companion object {
        val STOP = io.github.scriptibus.jofi.shared.domain.ai.FinishReason.STOP
    }
}
