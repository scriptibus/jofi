// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_COST_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MODEL_ASSIGNMENT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MONTHLY_BUDGET
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_PROVIDER_CONFIG
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SECRET
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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

    @Test
    fun `a secret referenced by a provider and a provider with assigned tasks cannot be deleted`() {
        val key = insertSecret()
        val provider = insertProvider(kind = "ANTHROPIC", apiKey = key)
        insertAssignment(AiTask.CHAT.name, provider)

        rejects { dsl.deleteFrom(SECRET).execute() }
        rejects { dsl.deleteFrom(AI_PROVIDER_CONFIG).execute() }
    }

    @ParameterizedTest
    @EnumSource(AiTask::class)
    fun `stores an assignment and a cost entry for every AI task`(task: AiTask) {
        val provider = insertProvider(kind = "OPENAI_COMPATIBLE", apiKey = null, baseUrl = "http://ollama:11434")

        insertAssignment(task.name, provider)
        insertCost(task.name, provider)

        dsl.fetchCount(AI_MODEL_ASSIGNMENT) shouldBe 1
        dsl.fetchCount(AI_COST_ENTRY) shouldBe 1
    }

    @Test
    fun `rejects unknown tasks and capabilities and a second assignment per task`() {
        val provider = insertProvider(kind = "OPENAI_COMPATIBLE", apiKey = null, baseUrl = "http://ollama:11434")
        insertAssignment("CHAT", provider)

        rejects { insertAssignment("CHAT", provider) }
        rejects { insertAssignment("WEATHER", provider) }
        rejects { insertAssignment("CLASSIFICATION", provider, capabilities = arrayOf("TELEPATHY")) }
        rejects { insertAssignment("CLASSIFICATION", provider, capabilities = arrayOf(null)) }
        rejects { insertAssignment("CLASSIFICATION", provider, contextWindow = 0) }
    }

    @Test
    fun `cost entries reject negative values and bad currencies but outlive their provider`() {
        val deletedProvider = UUID.randomUUID()

        insertCost("CHAT", deletedProvider)
        rejects { insertCost("CHAT", deletedProvider, inputTokens = -1) }
        rejects { insertCost("CHAT", deletedProvider, costMicros = -1) }
        rejects { insertCost("CHAT", deletedProvider, currency = "usd") }
        dsl.fetchCount(AI_COST_ENTRY) shouldBe 1
    }

    @Test
    fun `holds at most one positive monthly budget`() {
        insertBudget(capMicros = 20_000_000)

        rejects { insertBudget(capMicros = 30_000_000) }
        dsl.deleteFrom(AI_MONTHLY_BUDGET).execute()
        rejects { insertBudget(capMicros = 0) }
    }

    @Test
    fun `rejects an empty secret`() {
        rejects { insertSecret(ByteArray(0)) }
    }

    private fun rejects(statement: () -> Unit) {
        shouldThrow<DataAccessException> { statement() }
    }

    private fun insertSecret(ciphertext: ByteArray = byteArrayOf(1, 2, 3)): UUID {
        val id = UUID.randomUUID()
        dsl
            .insertInto(SECRET)
            .set(SECRET.ID, id)
            .set(SECRET.CIPHERTEXT, ciphertext)
            .set(SECRET.CREATED_AT, NOW)
            .set(SECRET.UPDATED_AT, NOW)
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
        capabilities: Array<String?> = arrayOf("TOOL_USE", "STREAMING"),
        contextWindow: Int? = 128_000,
    ) {
        dsl
            .insertInto(AI_MODEL_ASSIGNMENT)
            .set(AI_MODEL_ASSIGNMENT.TASK, task)
            .set(AI_MODEL_ASSIGNMENT.PROVIDER_ID, provider)
            .set(AI_MODEL_ASSIGNMENT.MODEL, "llama3.1:8b")
            .set(AI_MODEL_ASSIGNMENT.CAPABILITIES, capabilities)
            .set(AI_MODEL_ASSIGNMENT.CONTEXT_WINDOW_TOKENS, contextWindow)
            .execute()
    }

    private fun insertCost(
        task: String,
        provider: UUID,
        inputTokens: Long = 1_200,
        costMicros: Long = 3_600,
        currency: String = "USD",
    ) {
        dsl
            .insertInto(AI_COST_ENTRY)
            .set(AI_COST_ENTRY.TASK, task)
            .set(AI_COST_ENTRY.PROVIDER_ID, provider)
            .set(AI_COST_ENTRY.MODEL, "claude-haiku")
            .set(AI_COST_ENTRY.INPUT_TOKENS, inputTokens)
            .set(AI_COST_ENTRY.OUTPUT_TOKENS, 300L)
            .set(AI_COST_ENTRY.COST_MICROS, costMicros)
            .set(AI_COST_ENTRY.CURRENCY, currency)
            .set(AI_COST_ENTRY.OCCURRED_AT, NOW)
            .execute()
    }

    private fun insertBudget(capMicros: Long) {
        dsl
            .insertInto(AI_MONTHLY_BUDGET)
            .set(AI_MONTHLY_BUDGET.CAP_MICROS, capMicros)
            .set(AI_MONTHLY_BUDGET.CURRENCY, "EUR")
            .execute()
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-09-30T08:00:00Z")
    }
}
