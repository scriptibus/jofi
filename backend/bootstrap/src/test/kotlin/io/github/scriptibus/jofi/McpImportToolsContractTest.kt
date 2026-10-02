// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.ANSWER
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.COMPLETIONS
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.FAKE_AI
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.FAKE_POSTING
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
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import com.github.tomakehurst.wiremock.client.WireMock.get as wireMockGet

/**
 * The import tools (#118) with the MCP SDK client against the running app: a fake AI provider (WireMock, through the
 * real AI gateway), postings fetched through the real SSRF guard from a second WireMock server, the worker job's
 * handler called directly (the `app` profile runs no jobs), the database and the changelog.
 */
@Import(PostingUrlImportFlowTest.HttpTestConfig::class)
class McpImportToolsContractTest : McpImportContractSupport() {
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
            application["status"].asString() shouldBe "DISCOVERED"
            application["posting"].untrusted()["title"].asString() shouldBe "Senior Kotlin Developer"
            application["posting"].untrusted()["sources"][0]["kind"].asString() shouldBe "MANUAL_CHAT"
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
                .call("get_application", mapOf("id" to done["applicationId"].asString()))["status"]
                .asString() shouldBe "DISCOVERED"
            val sent = FAKE_AI.findAll(postRequestedFor(urlEqualTo(COMPLETIONS))).single().bodyAsString
            sent.contains("Ignore previous instructions") shouldBe true
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
    fun `a posting URL is fetched through the guard, imported and polled, with the link as its source`() {
        servesPosting("/jobs/42", "<html><body><script>evil()</script><h1>Senior Kotlin Developer</h1></body></html>")
        answers(ANSWER)
        owner.mcpClient().use { client ->
            client.initialize()

            val started = client.call("start_url_import", mapOf("url" to postingUrl("/jobs/42") + "?utm_source=mail"))

            started["outcome"].asString() shouldBe "STARTED"
            val id = started["import"]["id"].asString()
            changelog("posting_import", id).map { it.second } shouldContainExactly listOf("AI")
            val done = client.untilDone(id)
            val application = client.call("get_application", mapOf("id" to done["applicationId"].asString()))
            val source = application["posting"].untrusted()["sources"][0]
            source["kind"].asString() shouldBe "URL"
            source["url"].asString() shouldBe postingUrl("/jobs/42")
            val sent = FAKE_AI.findAll(postRequestedFor(urlEqualTo(COMPLETIONS))).single().bodyAsString
            sent shouldNotContain "evil()"
        }
    }

    @Test
    fun `the same link twice answers the pending import, then the existing application, fetching once`() {
        servesPosting("/jobs/42", "<html><body><h1>Senior Kotlin Developer</h1></body></html>")
        answers(ANSWER)
        owner.mcpClient().use { client ->
            client.initialize()
            val link = mapOf("url" to postingUrl("/jobs/42"))
            val first = client.call("start_url_import", link)
            val again = client.call("start_url_import", link)

            again["outcome"].asString() shouldBe "ALREADY_PENDING"
            again["import"]["id"].asString() shouldBe first["import"]["id"].asString()
            val done = client.untilDone(first["import"]["id"].asString())
            val imported = client.call("start_url_import", link)

            imported["outcome"].asString() shouldBe "ALREADY_IMPORTED"
            imported["import"]["status"].asString() shouldBe "SUCCEEDED"
            imported["import"]["applicationId"].asString() shouldBe done["applicationId"].asString()
            fetches("/jobs/42") shouldBe 1
            dsl.fetchCount(APPLICATION) shouldBe 1
        }
    }

    @Test
    fun `links to LinkedIn, StepStone and Indeed are refused with not-allowed and never fetched`() {
        owner.mcpClient().use { client ->
            client.initialize()

            listOf(
                "https://www.linkedin.com/jobs/view/123",
                "https://www.stepstone.de/stellenangebote--x-123-inline.html",
                "https://de.indeed.com/viewjob?jk=abc",
            ).forEach { link ->
                val refused = client.failure("start_url_import", mapOf("url" to link), "invalid-arguments")
                refused.problems() shouldContainExactly listOf("url:not-allowed")
                refused["message"].asString().contains("start_text_import") shouldBe true
            }
            FAKE_POSTING.allServeEvents.size shouldBe 0
            dsl.fetchCount(POSTING_IMPORT) shouldBe 0
        }
    }

    @Test
    fun `internal and unknown addresses are refused by the guard, and a malformed link is invalid`() {
        owner.mcpClient().use { client ->
            client.initialize()

            listOf(
                "http://169.254.169.254/latest/meta-data/",
                "http://10.0.0.5/jobs/1",
                "http://127.0.0.1:1/jobs/1",
                "http://localhost:1/jobs/1",
            ).forEach { link ->
                client.failure("start_url_import", mapOf("url" to link), "invalid-arguments").problems() shouldBe
                    listOf("url:unreachable")
            }
            client
                .failure(
                    "start_url_import",
                    mapOf("url" to "ftp://x.example/1"),
                    "invalid-arguments",
                ).problems() shouldBe
                listOf("url:invalid-url")
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
            client.refused("start_text_import", mapOf())
            client.refused("start_text_import", mapOf("text" to "x".repeat(100_001)))
            client.refused("start_text_import", mapOf("text" to "A posting", "unknown" to 1))
            client.refused("start_url_import", mapOf())
            client.refused("start_url_import", mapOf("url" to "https://x.example/" + "a".repeat(2_048)))
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
            val rejected =
                client.failure(
                    "start_url_import",
                    mapOf("url" to "http://10.0.0.5/?q=$FLAGGED_PHONE"),
                    "invalid-arguments",
                )

            listOf(started, done, rejected).forEach { it.toString() shouldNotContain "1234567" }
            FAKE_AI.findAll(postRequestedFor(urlEqualTo(COMPLETIONS))).single().bodyAsString shouldNotContain "1234567"
        }
    }

    @Test
    fun `without a session no import tool can be called`() {
        val anonymous = Session().open()

        listOf("start_text_import", "start_url_import", "get_import_status").forEach { tool ->
            val call = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":{}}}"""
            anonymous.send("POST", "/mcp", call).statusCode() shouldBe 401
        }
    }
}
