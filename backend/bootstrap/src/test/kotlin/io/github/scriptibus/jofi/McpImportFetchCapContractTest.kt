// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The cap on concurrent URL import fetches (#224) through the MCP tool, set to two: of three slow links started at
 * once two are fetched and the third is turned away at once with `import-busy`; afterwards the permits are back.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["jofi.import.max-concurrent-fetches=2"],
)
@Import(
    PostgresTestConfiguration::class,
    McpToolContractSupport.FlaggedPhoneNumber::class,
    PostingUrlImportFlowTest.HttpTestConfig::class,
)
class McpImportFetchCapContractTest : McpImportContractSupport() {
    @Test
    fun `when the fetch cap is used up the third link answers import-busy and fetches nothing`() {
        (0 until 3).forEach { n -> servesPosting("/jobs/cap$n", "<html><body><h1>Job $n</h1></body></html>", 3_000) }
        val clients = (0 until 3).map { owner.mcpClient().also { client -> client.initialize() } }
        val barrier = CyclicBarrier(3)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val answers =
                clients
                    .mapIndexed { n, client ->
                        pool.submit<Pair<Boolean, JsonNode>> {
                            barrier.await()
                            client.outcome("start_url_import", mapOf("url" to postingUrl("/jobs/cap$n")))
                        }
                    }.map { it.get(60, TimeUnit.SECONDS) }

            answers.map { it.first } shouldContainExactlyInAnyOrder listOf(false, false, true)
            val busy = answers.indexOfFirst { it.first }
            answers[busy].second["code"].asString() shouldBe "import-busy"
            fetches("/jobs/cap$busy") shouldBe 0
            servesPosting("/jobs/cap$busy", "<html><body><h1>Job</h1></body></html>")
            val retry = clients[0].call("start_url_import", mapOf("url" to postingUrl("/jobs/cap$busy")))
            retry["outcome"].asString() shouldBe "STARTED"
        } finally {
            pool.shutdownNow()
            clients.forEach { it.close() }
        }
    }
}
