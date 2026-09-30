// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.setup.domain.CapabilityName
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_COST_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MODEL_ASSIGNMENT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MODEL_CAPABILITY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MONTHLY_BUDGET
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_PROVIDER_CONFIG
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SECRET
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The `setup` and `secret` tables on a real PostgreSQL migrated from zero: their constraints
 * mirror the domain invariants, so a repository bug cannot store what the domain forbids.
 */
class SetupSchemaTest {
    private lateinit var dsl: DSLContext

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
    }

    @ParameterizedTest
    @EnumSource(ProviderKind::class)
    fun `stores every provider kind the domain knows`(kind: ProviderKind) {
        val key = if (kind.needsApiKey) insertSecret() else null
        val baseUrl = if (kind.needsBaseUri) "http://localhost:11434/v1" else null

        insertProvider(kind = kind.name, apiKey = key, baseUrl = baseUrl)

        dsl.fetchCount(AI_PROVIDER_CONFIG) shouldBe 1
    }

    @Test
    fun `an OpenAI-compatible endpoint may have a key`() {
        insertProvider(kind = "OPENAI_COMPATIBLE", apiKey = insertSecret(), baseUrl = "https://openrouter.ai/api/v1")

        dsl.fetchCount(AI_PROVIDER_CONFIG) shouldBe 1
    }

    @Test
    fun `rejects provider rows that break the kind rules`() {
        val key = insertSecret()

        rejects { insertProvider(kind = "ANTHROPIC", apiKey = null) }
        rejects { insertProvider(kind = "OPENAI", apiKey = key, baseUrl = "https://example.org/v1") }
        rejects { insertProvider(kind = "OPENAI_COMPATIBLE", apiKey = null, baseUrl = null) }
        rejects { insertProvider(kind = "OPENAI_COMPATIBLE", apiKey = null, baseUrl = "file:///etc/passwd") }
        rejects { insertProvider(kind = "UNKNOWN", apiKey = key) }
        rejects { insertProvider(kind = "MISTRAL", apiKey = key, displayName = " ") }
        dsl.fetchCount(AI_PROVIDER_CONFIG) shouldBe 0
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://user:xxxx@proxy.example.org/v1",
            "https://token@proxy.example.org/v1",
            "https://proxy.example.org/v1?key=xxxx",
            "https://proxy.example.org/v1#key",
        ],
    )
    fun `rejects credentials, queries and fragments in the base URL`(baseUrl: String) {
        rejects { insertProvider(kind = "OPENAI_COMPATIBLE", apiKey = null, baseUrl = baseUrl) }
    }

    @Test
    fun `two providers cannot share one key`() {
        val key = insertSecret()
        insertProvider(kind = "ANTHROPIC", apiKey = key)

        rejects { insertProvider(kind = "OPENAI", apiKey = key) }
    }

    @Test
    fun `a secret referenced by a provider and a provider with assigned tasks cannot be deleted`() {
        val key = insertSecret()
        val provider = insertProvider(kind = "ANTHROPIC", apiKey = key)
        insertAssignment(AiTask.CHAT.name, provider)

        rejects { dsl.deleteFrom(SECRET).execute() }
        rejects { dsl.deleteFrom(AI_PROVIDER_CONFIG).execute() }
    }

    @Test
    fun `rejects an empty secret and an update time before the creation time`() {
        rejects { insertSecret(ByteArray(0)) }
        rejects { insertSecret(updatedAt = NOW.minusSeconds(1)) }
    }

    @ParameterizedTest
    @EnumSource(AiTask::class)
    fun `stores an assignment and a cost entry for every AI task`(task: AiTask) {
        val provider = localProvider()

        insertAssignment(task.name, provider)
        insertCost(task.name, provider)

        dsl.fetchCount(AI_MODEL_ASSIGNMENT) shouldBe 1
        dsl.fetchCount(AI_COST_ENTRY) shouldBe 1
    }

    @Test
    fun `rejects unknown tasks and a second assignment per task`() {
        val provider = localProvider()
        insertAssignment("CHAT", provider)

        rejects { insertAssignment("CHAT", provider) }
        rejects { insertAssignment("WEATHER", provider) }
    }

    @Test
    fun `round-trips every capability name the domain knows`() {
        val provider = localProvider()
        val all = CapabilityName.entries.map { it.name }.toTypedArray<String?>()

        insertCapabilities(provider, capabilities = all)

        val stored =
            dsl
                .select(AI_MODEL_CAPABILITY.CAPABILITIES)
                .from(AI_MODEL_CAPABILITY)
                .fetchSingle()
                .value1()
        stored.map { CapabilityName.valueOf(requireNotNull(it)) } shouldBe CapabilityName.entries
    }

    @ParameterizedTest
    @EnumSource(CapabilitySource::class)
    fun `stores every capability source the domain knows`(source: CapabilitySource) {
        insertCapabilities(localProvider(), source = source.name)

        dsl.fetchCount(AI_MODEL_CAPABILITY) shouldBe 1
    }

    @Test
    fun `rejects unknown capabilities, bad context sizes and a second profile per provider and model`() {
        val provider = localProvider()
        insertCapabilities(provider)

        rejects { insertCapabilities(provider) }
        rejects { insertCapabilities(provider, model = "other", capabilities = arrayOf("TELEPATHY")) }
        rejects { insertCapabilities(provider, model = "other", capabilities = arrayOf(null)) }
        rejects { insertCapabilities(provider, model = "other", contextWindow = 0) }
        rejects { insertCapabilities(provider, model = "other", source = "GUESSED") }
    }

    @Test
    fun `capability profiles are deleted with their provider`() {
        val provider = localProvider()
        insertCapabilities(provider)

        dsl.deleteFrom(AI_PROVIDER_CONFIG).execute()

        dsl.fetchCount(AI_MODEL_CAPABILITY) shouldBe 0
    }

    @Test
    fun `cost entries reject negative values, other currencies and unknown kinds but outlive their provider`() {
        val deletedProvider = UUID.randomUUID()

        insertCost("CHAT", deletedProvider)
        rejects { insertCost("CHAT", deletedProvider, inputTokens = -1) }
        rejects { insertCost("CHAT", deletedProvider, outputTokens = -1) }
        rejects { insertCost("CHAT", deletedProvider, costMicros = -1) }
        rejects { insertCost("CHAT", deletedProvider, currency = "EUR") }
        rejects { insertCost("CHAT", deletedProvider, providerKind = "UNKNOWN") }
        dsl.fetchCount(AI_COST_ENTRY) shouldBe 1
    }

    @Test
    fun `the cost meter is append-only`() {
        insertCost("CHAT", UUID.randomUUID())

        shouldThrow<DataAccessException> { dsl.update(AI_COST_ENTRY).set(AI_COST_ENTRY.COST_MICROS, 0L).execute() }
            .message shouldContain "append-only"
        shouldThrow<DataAccessException> { dsl.deleteFrom(AI_COST_ENTRY).execute() }
            .message shouldContain "append-only"
    }

    @Test
    fun `holds at most one positive monthly budget in USD`() {
        insertBudget(capMicros = 20_000_000)

        rejects { insertBudget(capMicros = 30_000_000) }
        dsl.truncate(AI_MONTHLY_BUDGET).execute()
        rejects { insertBudget(capMicros = 0) }
        rejects { insertBudget(capMicros = 20_000_000, currency = "EUR") }
    }

    private fun rejects(statement: () -> Unit) {
        shouldThrow<DataAccessException> { statement() }
    }

    private fun localProvider(): UUID =
        insertProvider(kind = "OPENAI_COMPATIBLE", apiKey = null, baseUrl = "http://ollama:11434")

    private fun insertSecret(
        ciphertext: ByteArray = byteArrayOf(1, 2, 3),
        updatedAt: OffsetDateTime = NOW,
    ): UUID {
        val id = UUID.randomUUID()
        dsl
            .insertInto(SECRET)
            .set(SECRET.ID, id)
            .set(SECRET.CIPHERTEXT, ciphertext)
            .set(SECRET.CREATED_AT, NOW)
            .set(SECRET.UPDATED_AT, updatedAt)
            .execute()
        return id
    }

    private fun insertProvider(
        kind: String,
        apiKey: UUID?,
        baseUrl: String? = null,
        displayName: String = "My provider",
    ): UUID {
        val id = UUID.randomUUID()
        dsl
            .insertInto(AI_PROVIDER_CONFIG)
            .set(AI_PROVIDER_CONFIG.ID, id)
            .set(AI_PROVIDER_CONFIG.DISPLAY_NAME, displayName)
            .set(AI_PROVIDER_CONFIG.KIND, kind)
            .set(AI_PROVIDER_CONFIG.API_KEY_SECRET_ID, apiKey)
            .set(AI_PROVIDER_CONFIG.BASE_URL, baseUrl)
            .execute()
        return id
    }

    private fun insertAssignment(
        task: String,
        provider: UUID,
    ) {
        dsl
            .insertInto(AI_MODEL_ASSIGNMENT)
            .set(AI_MODEL_ASSIGNMENT.TASK, task)
            .set(AI_MODEL_ASSIGNMENT.PROVIDER_ID, provider)
            .set(AI_MODEL_ASSIGNMENT.MODEL, "llama3.1:8b")
            .execute()
    }

    private fun insertCapabilities(
        provider: UUID,
        model: String = "llama3.1:8b",
        capabilities: Array<String?> = arrayOf("TOOL_USE", "STREAMING"),
        contextWindow: Int? = 128_000,
        source: String = "DETECTED",
    ) {
        dsl
            .insertInto(AI_MODEL_CAPABILITY)
            .set(AI_MODEL_CAPABILITY.PROVIDER_ID, provider)
            .set(AI_MODEL_CAPABILITY.MODEL, model)
            .set(AI_MODEL_CAPABILITY.CAPABILITIES, capabilities)
            .set(AI_MODEL_CAPABILITY.CONTEXT_WINDOW_TOKENS, contextWindow)
            .set(AI_MODEL_CAPABILITY.SOURCE, source)
            .set(AI_MODEL_CAPABILITY.UPDATED_AT, NOW)
            .execute()
    }

    @Suppress("LongParameterList") // one optional override per constraint under test
    private fun insertCost(
        task: String,
        provider: UUID,
        inputTokens: Long = 1_200,
        outputTokens: Long = 300,
        costMicros: Long = 3_600,
        currency: String = "USD",
        providerKind: String = "ANTHROPIC",
    ) {
        dsl
            .insertInto(AI_COST_ENTRY)
            .set(AI_COST_ENTRY.TASK, task)
            .set(AI_COST_ENTRY.PROVIDER_ID, provider)
            .set(AI_COST_ENTRY.PROVIDER_KIND, providerKind)
            .set(AI_COST_ENTRY.MODEL, "claude-haiku")
            .set(AI_COST_ENTRY.INPUT_TOKENS, inputTokens)
            .set(AI_COST_ENTRY.OUTPUT_TOKENS, outputTokens)
            .set(AI_COST_ENTRY.COST_MICROS, costMicros)
            .set(AI_COST_ENTRY.CURRENCY, currency)
            .set(AI_COST_ENTRY.OCCURRED_AT, NOW)
            .execute()
    }

    private fun insertBudget(
        capMicros: Long,
        currency: String = "USD",
    ) {
        dsl
            .insertInto(AI_MONTHLY_BUDGET)
            .set(AI_MONTHLY_BUDGET.CAP_MICROS, capMicros)
            .set(AI_MONTHLY_BUDGET.CURRENCY, currency)
            .execute()
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-09-30T08:00:00Z")
    }
}
