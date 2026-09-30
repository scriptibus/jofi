// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.application.port.ModelCatalogPort
import io.github.scriptibus.jofi.setup.application.port.MonthlyBudgetPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.BillingMonth
import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.CostEntry
import io.github.scriptibus.jofi.setup.domain.CostGroup
import io.github.scriptibus.jofi.setup.domain.CostTotals
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ModelKey
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * An in-memory setup store behind every setup port, with a transaction that restores all of it when
 * the use case's result is not committed, so tests see what a rollback leaves.
 */
class SetupFixtures {
    val providers = linkedMapOf<ProviderId, ProviderConfig>()
    val assignments = linkedMapOf<AiTask, ModelAssignment>()
    val profiles = linkedMapOf<Pair<ProviderId, ModelName>, ModelCapabilityProfile>()
    val secrets = linkedMapOf<SecretId, SecretValue>()
    val entries = mutableListOf<ChangelogEntry>()
    val costs = mutableListOf<CostEntry>()
    var budget: MonthlyBudget? = null
    var failingCostReads = false

    /** What a read still sees of providers another transaction deleted meanwhile (the update/delete race). */
    val staleReads = linkedMapOf<ProviderId, ProviderConfig>()
    var failingChangelog = false
    var failingWrites = false
    var detected: AiResult<List<ModelCapabilityProfile>> = AiResult.Success(emptyList())
    var known: Map<ModelName, ModelCapabilities> = emptyMap()

    private fun <T> read(value: T?): SetupStoreResult<T> =
        if (value == null) SetupStoreResult.NotFound else SetupStoreResult.Success(value)

    private fun write(block: () -> Unit): SetupStoreResult<Unit> {
        if (failingWrites) return SetupStoreResult.StorageFailure("write")
        block()
        return SetupStoreResult.Success(Unit)
    }

    val providerPort =
        object : ProviderConfigPort {
            override fun findAll() = SetupStoreResult.Success(providers.values.toList())

            override fun findById(id: ProviderId) = read(providers[id] ?: staleReads[id])

            override fun save(config: ProviderConfig) = write { providers[config.id] = config }

            override fun update(config: ProviderConfig) =
                if (config.id in providers) write { providers[config.id] = config } else SetupStoreResult.NotFound

            override fun delete(
                id: ProviderId,
                proof: ConfirmationResult.Confirmed,
            ): SetupStoreResult<Unit> =
                when {
                    !proof.covers(ProviderId.DELETE_OPERATION, id.value.toString()) -> SetupStoreResult.NotConfirmed
                    assignments.values.any { it.provider == id } -> SetupStoreResult.InUse
                    providers.remove(id) == null -> SetupStoreResult.NotFound
                    else -> write { profiles.keys.removeIf { it.first == id } }
                }
        }

    val assignmentPort =
        object : ModelAssignmentPort {
            override fun findAll() = SetupStoreResult.Success(assignments.values.toList())

            override fun findByTask(task: AiTask) = read(assignments[task])

            override fun save(assignment: ModelAssignment) = write { assignments[assignment.task] = assignment }

            override fun delete(task: AiTask) = write { assignments.remove(task) }
        }

    val profilePort =
        object : ModelCapabilityPort {
            override fun find(
                provider: ProviderId,
                model: ModelName,
            ) = read(profiles[provider to model])

            override fun findByProvider(provider: ProviderId) =
                SetupStoreResult.Success(profiles.values.filter { it.provider == provider }.sortedBy { it.model.value })

            override fun save(profile: ModelCapabilityProfile) =
                write {
                    profiles[profile.provider to profile.model] =
                        profile
                }
        }

    val budgetPort =
        object : MonthlyBudgetPort {
            override fun find() = read(budget)

            override fun save(budget: MonthlyBudget) = write { this@SetupFixtures.budget = budget }

            override fun clear() = write { budget = null }
        }

    /** Sums like the repository does, over the entries in [from, until). */
    val costPort =
        object : CostEntryPort {
            override fun append(entry: CostEntry) = write { costs += entry }

            override fun findBetween(
                from: Instant,
                until: Instant,
            ) = costRead { costs.filter { it.occurredAt >= from && it.occurredAt < until } }

            override fun totalBetween(
                from: Instant,
                until: Instant,
            ) = costRead { totalsOf(costs.filter { it.occurredAt >= from && it.occurredAt < until }).knownCost }

            override fun summarizeBetween(
                from: Instant,
                until: Instant,
            ) = costRead {
                costs
                    .filter { it.occurredAt >= from && it.occurredAt < until }
                    .groupBy { it.task to ModelKey(it.providerKind, it.model) }
                    .map { (key, entries) -> CostGroup(key.first, key.second, totalsOf(entries)) }
            }

            override fun totalsByMonth(
                first: BillingMonth,
                last: BillingMonth,
            ) = costRead {
                costs
                    .filter { it.occurredAt >= first.start && it.occurredAt < last.end }
                    .groupBy { BillingMonth.of(it.occurredAt) }
                    .mapValues { (_, entries) -> totalsOf(entries) }
            }
        }

    private fun <T> costRead(value: () -> T): SetupStoreResult<T> =
        if (failingCostReads) SetupStoreResult.StorageFailure("costs") else SetupStoreResult.Success(value())

    private fun totalsOf(entries: List<CostEntry>): CostTotals =
        CostTotals(
            calls = entries.size.toLong(),
            usage = entries.map { it.usage }.fold(TokenUsage(0, 0), TokenUsage::plus),
            knownCost = entries.mapNotNull { it.estimatedCost }.fold(Money.usd(0), Money::plus),
            unknownCostCalls = entries.count { it.estimatedCost == null }.toLong(),
        )

    /** A metered call of [task] at [at] with [micros] as its cost (null: unknown). */
    fun cost(
        at: Instant,
        micros: Long?,
        task: AiTask = AiTask.CHAT,
        kind: ProviderKind = ProviderKind.OPENAI,
        model: String = "gpt-5-mini",
    ) {
        costs +=
            CostEntry(
                task = task,
                provider = ProviderId(UUID(0, 1)),
                providerKind = kind,
                model = ModelName(model),
                usage = TokenUsage(100, 10),
                estimatedCost = micros?.let(Money::usd),
                occurredAt = at,
            )
    }

    val catalog =
        object : ModelCatalogPort {
            override fun detect(provider: ProviderConfig) = detected

            override fun knownCapabilities(
                kind: ProviderKind,
                model: ModelName,
            ) = known[model] ?: ModelCapabilities.NONE
        }

    val secretPort =
        object : SecretStorePort {
            override fun put(
                id: SecretId,
                value: SecretValue,
            ): SecretResult<Unit> {
                secrets[id] = value
                return SecretResult.Success(Unit)
            }

            override fun get(id: SecretId) = secrets[id]?.let { SecretResult.Success(it) } ?: SecretResult.NotFound

            override fun delete(id: SecretId) =
                if (secrets.remove(id) ==
                    null
                ) {
                    SecretResult.NotFound
                } else {
                    SecretResult.Success(Unit)
                }
        }

    val changelog =
        object : ChangelogPort {
            override fun append(entry: ChangelogEntry): ChangelogResult<Unit> {
                if (failingChangelog) return ChangelogResult.StorageFailure("append")
                entries += entry
                return ChangelogResult.Success(Unit)
            }

            override fun listByEntity(
                entity: EntityRef,
                limit: ChangelogLimit,
            ) = ChangelogResult.Success(entries.filter { it.entity == entity })

            override fun listRecent(limit: ChangelogLimit) = ChangelogResult.Success(entries.toList())
        }

    val transactions =
        object : TransactionPort {
            override fun <T> inTransaction(
                commitIf: (T) -> Boolean,
                work: () -> T,
            ): T {
                val snapshot = Snapshot()
                val result = work()
                if (!commitIf(result)) snapshot.restore()
                return result
            }
        }

    val confirmation = ConfirmActionUseCase(TokenStore(), CLOCK, Duration.ofMinutes(5))

    fun provider(
        kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
        name: String = "Ollama",
    ): ProviderConfig {
        val key = SecretId(UUID.randomUUID())
        val config =
            if (kind.needsBaseUri) {
                ProviderConfig(ProviderId(UUID.randomUUID()), name, kind, null, URI("http://localhost:11434/v1"))
            } else {
                secrets[key] = SecretValue("sk-stored")
                ProviderConfig(ProviderId(UUID.randomUUID()), name, kind, key)
            }
        providers[config.id] = config
        return config
    }

    private inner class Snapshot {
        private val providersBefore = providers.toMap()
        private val assignmentsBefore = assignments.toMap()
        private val profilesBefore = profiles.toMap()
        private val secretsBefore = secrets.toMap()
        private val entriesBefore = entries.toList()
        private val budgetBefore = budget

        fun restore() {
            providers.clear()
            providers.putAll(providersBefore)
            assignments.clear()
            assignments.putAll(assignmentsBefore)
            profiles.clear()
            profiles.putAll(profilesBefore)
            secrets.clear()
            secrets.putAll(secretsBefore)
            entries.clear()
            entries.addAll(entriesBefore)
            budget = budgetBefore
        }
    }

    private class TokenStore : ConfirmationStorePort {
        private val pending = mutableMapOf<String, PendingConfirmation>()

        override fun issue(
            pending: PendingConfirmation,
            now: Instant,
        ): ConfirmationToken {
            val token = "token-${this.pending.size + 1}-${now.toEpochMilli()}-${System.nanoTime()}"
            this.pending[token] = pending
            return ConfirmationToken(token)
        }

        override fun redeem(token: ConfirmationToken): PendingConfirmation? = pending.remove(token.value)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-09-30T12:00:00.123456Z")
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00.123456789Z"), ZoneOffset.UTC)
        val TOOLS_AND_STREAMING =
            ModelCapabilities(setOf(Capability.ToolUse, Capability.Streaming, Capability.ContextSize(128_000)))
    }
}
