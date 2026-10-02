// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.COMPLETIONS
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.FAKE_AI
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.FAKE_POSTING
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.PROVIDER
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.completion
import io.github.scriptibus.jofi.applications.adapter.jobs.PostingImportJobAdapter
import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MODEL_ASSIGNMENT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_PROVIDER_CONFIG
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.client.McpSyncClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import com.github.tomakehurst.wiremock.client.WireMock.get as wireMockGet

/**
 * What the import tool contract tests share: a fake AI provider (WireMock, through the real AI gateway) assigned to
 * the extraction task, postings played by a second WireMock server and fetched through the real SSRF guard, and the
 * worker job's handler called directly (the `app` profile runs no jobs). Subclasses import
 * [PostingUrlImportFlowTest.HttpTestConfig], which allowlists that server.
 */
@Suppress("VarCouldBeVal") // Spring injects the fields after construction.
open class McpImportContractSupport : McpToolContractSupport() {
    @Autowired
    protected lateinit var providers: ProviderConfigPort

    @Autowired
    protected lateinit var assignments: ModelAssignmentPort

    @Autowired
    protected lateinit var job: PostingImportJobAdapter

    @BeforeEach
    fun configureFakeProvider() {
        FAKE_AI.resetAll()
        FAKE_POSTING.resetAll()
        providers.save(PROVIDER)
        assignments.save(ModelAssignment(AiTask.EXTRACTION, PROVIDER.id, ModelName("fake-extraction")))
    }

    @AfterEach
    fun removeFakeProvider() {
        dsl.deleteFrom(AI_MODEL_ASSIGNMENT).where(AI_MODEL_ASSIGNMENT.PROVIDER_ID.eq(PROVIDER.id.value)).execute()
        dsl.deleteFrom(AI_PROVIDER_CONFIG).where(AI_PROVIDER_CONFIG.ID.eq(PROVIDER.id.value)).execute()
    }

    protected fun answers(content: String) {
        FAKE_AI.stubFor(post(COMPLETIONS).willReturn(completion(content)))
    }

    protected fun servesPosting(
        path: String,
        html: String,
        delayMillis: Int = 0,
    ) {
        FAKE_POSTING.stubFor(
            wireMockGet(urlEqualTo(path)).willReturn(
                aResponse()
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody(html)
                    .withFixedDelay(delayMillis),
            ),
        )
    }

    protected fun postingUrl(path: String): String = "http://127.0.0.1:${FAKE_POSTING.port()}$path"

    protected fun fetches(path: String) = FAKE_POSTING.findAll(getRequestedFor(urlEqualTo(path))).size

    protected fun McpSyncClient.status(id: String): JsonNode = call("get_import_status", mapOf("id" to id))

    /** Polls until the import is no longer pending; the worker's turn comes after the first pending reading. */
    protected fun McpSyncClient.untilDone(id: String): JsonNode {
        status(id)["status"].asString() shouldBe "PENDING"
        job.run(mapOf("import" to id)) shouldBe JobOutcome.Done
        return status(id)
    }
}
