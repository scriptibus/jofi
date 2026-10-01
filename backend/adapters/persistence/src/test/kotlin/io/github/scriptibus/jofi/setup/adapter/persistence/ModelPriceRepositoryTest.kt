// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.setup.domain.CapabilityInput
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ModelPriceOverride
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MODEL_PRICE_OVERRIDE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_PROVIDER_CONFIG
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The user's model prices (`ai_model_price_override`, #142) on a real PostgreSQL migrated from zero: the
 * repository round-trip and the schema's named constraints, at exactly each limit the domain accepts.
 */
class ModelPriceRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var providers: ProviderConfigRepository
    private lateinit var prices: ModelPriceRepository

    private val local =
        ProviderConfig(
            ProviderId(UUID.randomUUID()),
            "Ollama",
            ProviderKind.OPENAI_COMPATIBLE,
            null,
            URI("http://ollama:11434/v1"),
        )
    private val other = local.copy(id = ProviderId(UUID.randomUUID()), displayName = "OpenRouter")
    private val at = Instant.parse("2026-10-02T08:00:00.123456Z")
    private val model = ModelName("llama3.1:8b")
    private val max = ModelPriceOverride.MAX_MICROS_PER_MILLION

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        providers = ProviderConfigRepository(dsl)
        prices = ModelPriceRepository(dsl)
        providers.save(local)
        providers.save(other)
    }

    private fun price(
        input: Long = 150_000,
        output: Long = 600_000,
        provider: ProviderConfig = local,
        name: ModelName = model,
    ) = ModelPriceOverride(provider.id, name, input, output, at)

    @Test
    fun `a price round-trips at microsecond precision and a second save replaces it`() {
        prices.save(price()) shouldBe SetupStoreResult.Success(Unit)
        prices.find(local.id, model) shouldBe SetupStoreResult.Success(price())

        prices.save(price(input = 0, output = 7)) shouldBe SetupStoreResult.Success(Unit)

        prices.find(local.id, model) shouldBe SetupStoreResult.Success(price(input = 0, output = 7))
        dsl.fetchCount(AI_MODEL_PRICE_OVERRIDE) shouldBe 1
    }

    @Test
    fun `prices are per provider and listed by model name`() {
        prices.save(price(name = ModelName("zeta")))
        prices.save(price(name = ModelName("alpha")))
        prices.save(price(provider = other, input = 9))

        (prices.findByProvider(local.id) as SetupStoreResult.Success).value.map { it.model.value } shouldBe
            listOf("alpha", "zeta")
        prices.find(other.id, model) shouldBe SetupStoreResult.Success(price(provider = other, input = 9))
        prices.find(local.id, ModelName("missing")) shouldBe SetupStoreResult.NotFound
    }

    @Test
    fun `clearing removes one price and succeeds when there is none`() {
        prices.save(price())
        prices.save(price(provider = other))

        prices.clear(local.id, model) shouldBe SetupStoreResult.Success(Unit)
        prices.clear(local.id, model) shouldBe SetupStoreResult.Success(Unit)

        prices.find(local.id, model) shouldBe SetupStoreResult.NotFound
        prices.find(other.id, model) shouldBe SetupStoreResult.Success(price(provider = other))
    }

    @Test
    fun `a price for a deleted provider is not stored and does not bring it back`() {
        val gone = ProviderId(UUID.randomUUID())

        prices.save(ModelPriceOverride(gone, model, 1, 1, at)) shouldBe SetupStoreResult.NotFound
        dsl.fetchCount(AI_MODEL_PRICE_OVERRIDE) shouldBe 0
    }

    @Test
    fun `prices are deleted with their provider`() {
        prices.save(price())

        dsl.deleteFrom(AI_PROVIDER_CONFIG).where(AI_PROVIDER_CONFIG.ID.eq(local.id.value)).execute()

        dsl.fetchCount(AI_MODEL_PRICE_OVERRIDE) shouldBe 0
    }

    @Test
    fun `accepts every value at exactly each domain limit`() {
        listOf(0L, max).forEach { limit ->
            prices.save(price(input = limit, output = limit, name = ModelName("limit-$limit"))) shouldBe
                SetupStoreResult.Success(Unit)
        }
        val longest = ModelName("m".repeat(CapabilityInput.MAX_MODEL_NAME))
        prices.save(price(name = longest)) shouldBe SetupStoreResult.Success(Unit)
        val unicode = ModelName("llama-3.1-ü-モデル")
        prices.save(price(name = unicode)) shouldBe SetupStoreResult.Success(Unit)

        prices.find(local.id, longest) shouldBe SetupStoreResult.Success(price(name = longest))
        prices.find(local.id, unicode) shouldBe SetupStoreResult.Success(price(name = unicode))
        prices.find(local.id, ModelName("limit-$max")) shouldBe
            SetupStoreResult.Success(price(input = max, output = max, name = ModelName("limit-$max")))
    }

    @Test
    fun `rejects, by constraint name, what the domain rejects`() {
        rejects("ai_model_price_override_input_valid") { insert(input = -1) }
        rejects("ai_model_price_override_input_valid") { insert(input = max + 1) }
        rejects("ai_model_price_override_output_valid") { insert(output = -1) }
        rejects("ai_model_price_override_output_valid") { insert(output = max + 1) }
        rejects("ai_model_price_override_model_valid") { insert(model = " \t") }
        rejects("ai_model_price_override_model_valid") { insert(model = "") }
        rejects(
            "ai_model_price_override_model_valid",
        ) { insert(model = "m".repeat(CapabilityInput.MAX_MODEL_NAME + 1)) }
        rejects("ai_model_price_override_provider_fk") { insert(provider = UUID.randomUUID()) }
        insert()
        rejects("ai_model_price_override_pk") { insert() }
    }

    private fun insert(
        provider: UUID = local.id.value,
        model: String = "llama3.1:8b",
        input: Long = 1,
        output: Long = 1,
    ) {
        dsl
            .insertInto(AI_MODEL_PRICE_OVERRIDE)
            .set(AI_MODEL_PRICE_OVERRIDE.PROVIDER_ID, provider)
            .set(AI_MODEL_PRICE_OVERRIDE.MODEL, model)
            .set(AI_MODEL_PRICE_OVERRIDE.INPUT_MICROS_PER_MILLION, input)
            .set(AI_MODEL_PRICE_OVERRIDE.OUTPUT_MICROS_PER_MILLION, output)
            .set(AI_MODEL_PRICE_OVERRIDE.UPDATED_AT, OffsetDateTime.parse("2026-10-02T08:00:00Z"))
            .execute()
    }

    private fun rejects(
        constraint: String,
        statement: () -> Unit,
    ) {
        shouldThrow<DataAccessException> { statement() }.message shouldContain constraint
    }
}
