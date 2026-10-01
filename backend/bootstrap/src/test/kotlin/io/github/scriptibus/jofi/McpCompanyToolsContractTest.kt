// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The company tools (#119) with the MCP SDK client against the running app. */
class McpCompanyToolsContractTest : McpToolContractSupport() {
    @Test
    fun `the company tools are listed, the reading ones as read only`() {
        owner.mcpClient().use { client ->
            client.initialize()

            val tools = client.listTools().tools().associateBy { it.name() }

            listOf("search_companies", "get_company").forEach {
                tools.getValue(it).annotations().readOnlyHint() shouldBe true
            }
            listOf("create_company", "update_company").forEach {
                tools.getValue(it).annotations().readOnlyHint() shouldBe false
            }
        }
    }

    @Test
    fun `a company is created, found, read and updated, each change logged with the AI as actor`() {
        owner.mcpClient().use { client ->
            client.initialize()

            val created = client.call("create_company", mapOf("name" to "ACME GmbH", "locations" to listOf("Berlin")))
            val id = created["id"].asString()
            created["version"].asInt() shouldBe 0
            created["company"].untrusted()["name"].asString() shouldBe "ACME GmbH"
            changelog("company", id) shouldContainExactly listOf("Created company" to "AI")

            val search = client.call("search_companies", mapOf("text" to "ACME"))
            search["total"].asInt() shouldBe 1
            search["companies"][0]["id"].asString() shouldBe id
            search["companies"][0]["company"].untrusted()["name"].asString() shouldBe "ACME GmbH"
            val read = client.call("get_company", mapOf("id" to id))
            read["company"].untrusted()["locations"][0].asString() shouldBe "Berlin"

            val updated =
                client.call(
                    "update_company",
                    mapOf("id" to id, "version" to 0, "name" to "ACME SE", "researchNotes" to "Met at a fair"),
                )
            updated["version"].asInt() shouldBe 1
            updated["researchNotes"].asString() shouldBe "Met at a fair"
            updated["company"].untrusted()["locations"].size() shouldBe 0
            changelog("company", id).map { it.second } shouldContainExactly listOf("AI", "AI")
            changelog("company", id).last().first shouldContain "Edited company"
        }
    }

    @Test
    fun `company tools answer validation errors, stale versions and missing companies as tool errors`() {
        val company = owner.create("/api/companies", """{"name":"ACME GmbH"}""")
        owner.mcpClient().use { client ->
            client.initialize()

            client
                .failure("create_company", mapOf("name" to " ", "website" to "ftp://x"), "invalid-arguments")
                .problems() shouldContainExactlyInAnyOrder listOf("name:required", "website:invalid-url")
            client.refused("create_company", mapOf("name" to 5))
            client.failure("update_company", mapOf("id" to company, "version" to 7, "name" to "X"), "version-conflict")
            client.failure("update_company", mapOf("id" to MISSING, "version" to 0, "name" to "X"), "not-found")
            client.refused("update_company", mapOf("id" to company, "name" to "X"))
            client.failure("get_company", mapOf("id" to MISSING), "not-found")
            client.refused("get_company", mapOf("id" to "nope"))
            client.refused("search_companies", mapOf("size" to 0))
            changelog("company", company).size shouldBe 1
        }
    }
}
