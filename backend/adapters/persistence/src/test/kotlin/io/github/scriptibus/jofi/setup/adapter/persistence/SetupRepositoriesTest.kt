// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.CostEntry
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_COST_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SECRET
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The `setup` repositories the AI gateway reads and writes, against a real PostgreSQL. */
class SetupRepositoriesTest {
    private lateinit var dsl: DSLContext
    private lateinit var providers: ProviderConfigRepository
    private lateinit var assignments: ModelAssignmentRepository
    private lateinit var capabilities: ModelCapabilityRepository
    private lateinit var budgets: MonthlyBudgetRepository
    private lateinit var costs: CostEntryRepository

    private val keyId = SecretId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val cloud = ProviderConfig(ProviderId(UUID.randomUUID()), "OpenAI", ProviderKind.OPENAI, keyId)
    private val local =
        ProviderConfig(
            ProviderId(UUID.randomUUID()),
            "Ollama",
            ProviderKind.OPENAI_COMPATIBLE,
            null,
            URI("http://ollama:11434/v1"),
        )
    private val at = Instant.parse("2026-09-30T12:00:00.123456Z")

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        providers = ProviderConfigRepository(dsl)
        assignments = ModelAssignmentRepository(dsl)
        capabilities = ModelCapabilityRepository(dsl)
        budgets = MonthlyBudgetRepository(dsl)
        costs = CostEntryRepository(dsl)
        dsl
            .insertInto(SECRET)
            .set(SECRET.ID, keyId.value)
            .set(SECRET.CIPHERTEXT, byteArrayOf(1))
            .set(SECRET.CREATED_AT, at.atOffset(ZoneOffset.UTC))
            .set(SECRET.UPDATED_AT, at.atOffset(ZoneOffset.UTC))
            .execute()
    }

    @Test
    fun `provider configs round-trip, are replaced by id and cannot be deleted while assigned`() {
        providers.save(cloud) shouldBe SetupStoreResult.Success(Unit)
        providers.save(local) shouldBe SetupStoreResult.Success(Unit)
        providers.save(local.copy(displayName = "LM Studio")) shouldBe SetupStoreResult.Success(Unit)

        providers.findById(cloud.id) shouldBe SetupStoreResult.Success(cloud)
        (providers.findAll() as SetupStoreResult.Success).value.toSet() shouldBe
            setOf(cloud, local.copy(displayName = "LM Studio"))
        providers.findById(ProviderId(UUID.randomUUID())) shouldBe SetupStoreResult.NotFound

        assignments.save(ModelAssignment(AiTask.CHAT, local.id, ModelName("llama3.1"))) shouldBe
            SetupStoreResult.Success(Unit)
        val proof = ConfirmedProofs.of(ProviderId.DELETE_OPERATION, local.id.value.toString())
        providers.delete(local.id, proof) shouldBe SetupStoreResult.InUse
        assignments.delete(AiTask.CHAT) shouldBe SetupStoreResult.Success(Unit)
        providers.delete(cloud.id, proof) shouldBe SetupStoreResult.NotConfirmed
        providers.findById(cloud.id) shouldBe SetupStoreResult.Success(cloud)
        providers.delete(local.id, proof) shouldBe SetupStoreResult.Success(Unit)
        providers.delete(local.id, proof) shouldBe SetupStoreResult.NotFound
    }

    @Test
    fun `assignments are one per task and replaced on save`() {
        providers.save(cloud)
        providers.save(local)
        val chat = ModelAssignment(AiTask.CHAT, cloud.id, ModelName("gpt-4o-mini"))

        assignments.save(chat)
        assignments.save(chat.copy(provider = local.id, model = ModelName("llama3.1")))
        assignments.save(ModelAssignment(AiTask.EMBEDDING, cloud.id, ModelName("text-embedding-3-small")))

        assignments.findByTask(AiTask.CHAT) shouldBe
            SetupStoreResult.Success(ModelAssignment(AiTask.CHAT, local.id, ModelName("llama3.1")))
        (assignments.findAll() as SetupStoreResult.Success).value.size shouldBe 2
        assignments.findByTask(AiTask.EXTRACTION) shouldBe SetupStoreResult.NotFound
        assignments.delete(AiTask.EXTRACTION) shouldBe SetupStoreResult.NotFound
    }

    @Test
    fun `capability profiles round-trip with features and the context size`() {
        providers.save(local)
        val profile =
            ModelCapabilityProfile(
                local.id,
                ModelName("llama3.1"),
                ModelCapabilities(setOf(Capability.Streaming, Capability.ToolUse, Capability.ContextSize(128_000))),
                CapabilitySource.USER,
                at,
            )
        val embedder =
            profile.copy(
                model = ModelName("nomic"),
                capabilities = ModelCapabilities(setOf(Capability.Embedding)),
            )

        capabilities.save(profile) shouldBe SetupStoreResult.Success(Unit)
        capabilities.save(embedder)
        capabilities.save(profile.copy(source = CapabilitySource.DETECTED))

        capabilities.find(local.id, profile.model) shouldBe
            SetupStoreResult.Success(profile.copy(source = CapabilitySource.DETECTED))
        (capabilities.findByProvider(local.id) as SetupStoreResult.Success).value.map { it.model.value } shouldBe
            listOf("llama3.1", "nomic")
        capabilities.find(local.id, ModelName("unknown")) shouldBe SetupStoreResult.NotFound
    }

    @Test
    fun `the monthly budget is one optional row`() {
        budgets.find() shouldBe SetupStoreResult.NotFound

        budgets.save(MonthlyBudget(Money.usd(20_000_000)))
        budgets.save(MonthlyBudget(Money.usd(5_000_000)))

        budgets.find() shouldBe SetupStoreResult.Success(MonthlyBudget(Money.usd(5_000_000)))
        budgets.clear() shouldBe SetupStoreResult.Success(Unit)
        budgets.clear() shouldBe SetupStoreResult.Success(Unit)
        budgets.find() shouldBe SetupStoreResult.NotFound
    }

    @Test
    fun `cost entries are appended, found by time and summed without unknown costs`() {
        val priced = entry(Money.usd(1_500), at)
        val unknown = entry(null, at.plusSeconds(1))
        val nextMonth = entry(Money.usd(9_000), Instant.parse("2026-10-01T00:00:00Z"))
        listOf(priced, unknown, nextMonth).forEach { costs.append(it) shouldBe SetupStoreResult.Success(Unit) }
        val september = Instant.parse("2026-09-01T00:00:00Z")
        val october = Instant.parse("2026-10-01T00:00:00Z")

        costs.findBetween(september, october) shouldBe SetupStoreResult.Success(listOf(priced, unknown))
        costs.totalBetween(september, october) shouldBe SetupStoreResult.Success(Money.usd(1_500))
        costs.totalBetween(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z")) shouldBe
            SetupStoreResult.Success(Money.usd(0))
        dsl.fetchCount(AI_COST_ENTRY, AI_COST_ENTRY.COST_MICROS.isNull) shouldBe 1
    }

    @Test
    fun `the cost meter stays append-only`() {
        costs.append(entry(Money.usd(1), at))

        shouldThrow<DataAccessException> {
            dsl
                .update(
                    AI_COST_ENTRY,
                ).set(AI_COST_ENTRY.COST_MICROS, DSL.inline(0L))
                .execute()
        }
        shouldThrow<DataAccessException> { dsl.deleteFrom(AI_COST_ENTRY).execute() }
    }

    @Test
    fun `a failing database is a storage failure, not an exception`() {
        dsl.execute("DROP TABLE ai_cost_entry")

        costs.append(entry(Money.usd(1), at)) shouldBe SetupStoreResult.StorageFailure("append")
        costs.totalBetween(at, at) shouldBe SetupStoreResult.StorageFailure("totalBetween")
    }

    private fun entry(
        cost: Money?,
        occurredAt: Instant,
    ) = CostEntry(
        AiTask.CHAT,
        cloud.id,
        ProviderKind.OPENAI,
        ModelName("gpt-4o-mini"),
        TokenUsage(100, 20),
        cost,
        occurredAt,
    )
}
