// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.ANSWER
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.COMPLETIONS
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.FAKE_AI
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.INJECTION
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.POSTING
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.PROVIDER
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.completion
import io.github.scriptibus.jofi.applications.adapter.jobs.PostingImportJobAdapter
import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MODEL_ASSIGNMENT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_PROVIDER_CONFIG
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.POSTING_IMPORT
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.client.McpSyncClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode

/**
 * The import tools (#118) with the MCP SDK client against the running app: a fake AI provider (WireMock, through the
 * real AI gateway), the worker job's handler called directly (the `app` profile runs no jobs), the database and the
 * changelog. There is no URL import through MCP (#242).
 */
@Suppress("VarCouldBeVal") // Spring injects the fields after construction.
class McpImportToolsContractTest : McpToolContractSupport() {
    @Autowired
    private lateinit var providers: ProviderConfigPort

    @Autowired
    private lateinit var assignments: ModelAssignmentPort

    @Autowired
    private lateinit var job: PostingImportJobAdapter

    @BeforeEach
    fun configureFakeProvider() {
        FAKE_AI.resetAll()
        providers.save(PROVIDER)
        assignments.save(ModelAssignment(AiTask.EXTRACTION, PROVIDER.id, ModelName("fake-extraction")))
    }

    @AfterEach
    fun removeFakeProvider() {
        dsl.deleteFrom(AI_MODEL_ASSIGNMENT).where(AI_MODEL_ASSIGNMENT.PROVIDER_ID.eq(PROVIDER.id.value)).execute()
        dsl.deleteFrom(AI_PROVIDER_CONFIG).where(AI_PROVIDER_CONFIG.ID.eq(PROVIDER.id.value)).execute()
    }

    private fun answers(content: String) {
        FAKE_AI.stubFor(post(COMPLETIONS).willReturn(completion(content)))
    }

    private fun McpSyncClient.status(id: String): JsonNode = call("get_import_status", mapOf("id" to id))

    /** Polls until the import is no longer pending; the worker's turn comes after the first pending reading. */
    private fun McpSyncClient.untilDone(id: String): JsonNode {
        status(id)["status"].asString() shouldBe "PENDING"
        job.run(mapOf("import" to id)) shouldBe JobOutcome.Done
        return status(id)
    }

    @Test
    fun `a pasted posting is imported by the AI, polled to its application, each step logged with the AI`() {
        answers(ANSWER)
        owner.mcpClient().use { client ->
            client.initialize()

            val started = client.call("start_text_import", mapOf("text" to POSTING))

            val id = started["id"].asString()
            started["status"].asString() shouldBe "PENDING"
            started["applicationId"].isNull shouldBe true
            changelog("posting_import", id).map { it.second } shouldContainExactly listOf("AI")
            val done = client.untilDone(id)
            done["status"].asString() shouldBe "SUCCEEDED"
            changelog("posting_import", id).map { it.second }.distinct() shouldBe listOf("AI")
            val application = client.call("get_application", mapOf("id" to done["applicationId"].asString()))
            changelog("application", done["applicationId"].asString()).map { it.second }.distinct() shouldBe
                listOf("AI")
            application["readOnly"]["status"].asString() shouldBe "DISCOVERED"
            application["posting"].untrusted()["title"].asString() shouldBe "Senior Kotlin Developer"
            application["readOnly"]["texts"].untrusted()["sources"][0]["kind"].asString() shouldBe "MANUAL_CHAT"
        }
    }

    @Test
    fun `instructions inside a posting are data, they start nothing and appear in no result`() {
        answers(ANSWER)
        owner.mcpClient().use { client ->
            client.initialize()

            val started = client.call("start_text_import", mapOf("text" to INJECTION))
            val done = client.untilDone(started["id"].asString())

            started.toString() shouldNotContain "Ignore previous"
            done.toString() shouldNotContain "Ignore previous"
            dsl.fetchCount(APPLICATION) shouldBe 1
            client
                .call("get_application", mapOf("id" to done["applicationId"].asString()))["readOnly"]["status"]
                .asString() shouldBe "DISCOVERED"
            val sent = FAKE_AI.findAll(postRequestedFor(urlEqualTo(COMPLETIONS))).single().bodyAsString
            sent.contains("Ignore previous instructions") shouldBe true
        }
    }

    @Test
    fun `the same text submitted over and over stores one pending import, not one row per call`() {
        answers(ANSWER)
        owner.mcpClient().use { client ->
            client.initialize()
            val first = client.call("start_text_import", mapOf("text" to POSTING))
            client.untilDone(first["id"].asString())

            val repeats = (1..5).map { client.call("start_text_import", mapOf("text" to POSTING))["id"].asString() }

            repeats.toSet().size shouldBe 1
            dsl.fetchCount(POSTING_IMPORT) shouldBe 2
            dsl.fetchCount(APPLICATION) shouldBe 1
        }
    }

    @Test
    fun `a failing AI ends in FAILED with the reason, and nothing is created`() {
        FAKE_AI.stubFor(post(COMPLETIONS).willReturn(aResponse().withStatus(503).withBody("""{"error":{}}""")))
        owner.mcpClient().use { client ->
            client.initialize()
            val started = client.call("start_text_import", mapOf("text" to POSTING))

            val failed = client.untilDone(started["id"].asString())

            failed["status"].asString() shouldBe "FAILED"
            failed["failure"].asString() shouldBe "AI_UNAVAILABLE"
            failed["applicationId"].isNull shouldBe true
            dsl.fetchCount(APPLICATION) shouldBe 0
        }
    }

    @Test
    fun `without a model for the extraction the import is refused and nothing is stored`() {
        dsl.deleteFrom(AI_MODEL_ASSIGNMENT).execute()
        owner.mcpClient().use { client ->
            client.initialize()

            client.failure("start_text_import", mapOf("text" to POSTING), "ai-not-configured")

            dsl.fetchCount(POSTING_IMPORT) shouldBe 0
        }
    }

    @Test
    fun `invalid text and arguments that break the schema are refused, storing nothing`() {
        owner.mcpClient().use { client ->
            client.initialize()

            client.failure("start_text_import", mapOf("text" to " \n "), "invalid-arguments").problems() shouldBe
                listOf("text:required")
            client.failure("start_text_import", mapOf("text" to "a\u0000b"), "invalid-arguments").problems() shouldBe
                listOf("text:invalid-character")
            client
                .failure("start_text_import", mapOf("text" to "A posting [withheld]"), "invalid-arguments")
                .problems() shouldBe listOf("text:withheld-value")
            client.refused("start_text_import", mapOf())
            client.refused("start_text_import", mapOf("text" to "x".repeat(100_001)))
            client.refused("start_text_import", mapOf("text" to "A posting", "unknown" to 1))
            client.failure("get_import_status", mapOf("id" to "not-a-uuid"), "invalid-arguments").problems() shouldBe
                listOf("id:invalid")
            client.failure("get_import_status", mapOf("id" to MISSING), "not-found")
            dsl.fetchCount(POSTING_IMPORT) shouldBe 0
        }
    }

    @Test
    fun `flagged values never leave, neither in results and errors nor to the AI`() {
        answers(ANSWER)
        owner.mcpClient().use { client ->
            client.initialize()

            val started = client.call("start_text_import", mapOf("text" to "$POSTING\nCall $FLAGGED_PHONE"))
            val done = client.untilDone(started["id"].asString())
            listOf(started, done).forEach { it.toString() shouldNotContain "1234567" }
            FAKE_AI.findAll(postRequestedFor(urlEqualTo(COMPLETIONS))).single().bodyAsString shouldNotContain "1234567"
        }
    }

    @Test
    fun `without a session no import tool can be called`() {
        val anonymous = Session().open()

        listOf("start_text_import", "get_import_status").forEach { tool ->
            val call = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":{}}}"""
            anonymous.send("POST", "/mcp", call).statusCode() shouldBe 401
        }
    }
}
