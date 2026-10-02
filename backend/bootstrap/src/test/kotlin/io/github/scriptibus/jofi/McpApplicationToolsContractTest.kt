// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.client.McpSyncClient
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/** `create_application` and `update_application` (#118) with the MCP SDK client against the running app. */
class McpApplicationToolsContractTest : McpApplicationContractSupport() {
    @Test
    fun `an application is created, read back and logged with the AI as actor`() {
        val company = company()
        owner.mcpClient().use { client ->
            client.initialize()

            val created = client.call("create_application", everyField(company))

            val id = created["id"].asString()
            created["version"].asInt() shouldBe 0
            created["readOnly"]["status"].asString() shouldBe "DISCOVERED"
            created["companyId"].asString() shouldBe company
            created["posting"].untrusted()["title"].asString() shouldBe "Kotlin Engineer"
            created["notes"].untrusted()["payEstimateBasis"].asString() shouldBe "levels.fyi"
            created["languageAndTone"].untrusted()["applicationLanguage"].asString() shouldBe "de-CH"
            created["payBand"]["estimateConfidence"].asString() shouldBe "LOW"
            created["offer"]["salary"]["amount"].asDouble() shouldBe 80000.0
            changelog("application", id).map { it.second } shouldContainExactly listOf("AI")
            client.call("get_application", mapOf("id" to id)) shouldBe created
            dsl.fetchCount(APPLICATION) shouldBe 1
        }
    }

    @Test
    fun `an answer of get_application goes back into update_application unchanged, nulls included`() {
        val company = company()
        owner.mcpClient().use { client ->
            client.initialize()
            val full = client.call("create_application", everyField(company))
            val sparse = client.call("create_application", bare(company, "B"))

            listOf(full, sparse).forEach { application ->
                val answer = client.call("update_application", application.asUpdate())

                answer shouldBe application
                changelog("application", application["id"].asString()).size shouldBe 1
            }
        }
    }

    @Test
    fun `validation errors name the arguments and store nothing`() {
        val company = company()
        owner.mcpClient().use { client ->
            client.initialize()

            val bad =
                mapOf(
                    "companyId" to company,
                    "posting" to mapOf("title" to " "),
                    "remoteSharePercent" to 101,
                    "payBand" to mapOf("min" to 5, "currency" to "euro", "period" to "YEAR", "source" to "POSTING"),
                )
            client.failure("create_application", bad, INVALID).problems() shouldContainExactly
                listOf("posting.title:required", "remoteSharePercent:out-of-range", "payBand.currency:invalid-currency")
            val missingCompany = mapOf("companyId" to MISSING, "posting" to mapOf("title" to "T"))
            client.failure("create_application", missingCompany, INVALID).problems() shouldContainExactly
                listOf("companyId:not-found")
            val badDate = mapOf("companyId" to company, "posting" to mapOf("title" to "T"), "deadline" to "5 Oct")
            client.failure("create_application", badDate, INVALID).problems() shouldContainExactly
                listOf("deadline:invalid")
            dsl.fetchCount(APPLICATION) shouldBe 0
        }
    }

    @Test
    fun `update_application answers a stale version and an unknown application, changing nothing`() {
        val company = company()
        owner.mcpClient().use { client ->
            client.initialize()
            val created = client.call("create_application", bare(company))
            val id = created["id"].asString()
            val update = created.asUpdate()

            client.failure("update_application", update + ("version" to 7), "version-conflict")
            client.failure("update_application", update + ("id" to MISSING), "not-found")
            val empty = mapOf("title" to "", "location" to null)
            client.failure("update_application", update + ("posting" to empty), INVALID)

            changelog("application", id).size shouldBe 1
            client.call("get_application", mapOf("id" to id))["version"].asInt() shouldBe 0
        }
    }

    @Test
    fun `arguments that break the schema are refused before any tool runs`() {
        val company = company()
        owner.mcpClient().use { client ->
            client.initialize()
            val ok = mapOf("companyId" to company, "posting" to mapOf("title" to "T"))

            client.refused("create_application", mapOf("posting" to mapOf("title" to "T")))
            client.refused("create_application", mapOf("companyId" to company))
            client.refused("create_application", mapOf("companyId" to company, "posting" to mapOf("location" to "x")))
            client.refused("create_application", ok + ("unknown" to 1))
            client.refused("create_application", ok + ("title" to "T"))
            client.refused("create_application", ok + ("posting" to mapOf("title" to "x".repeat(601))))
            client.refused("create_application", ok + ("notes" to mapOf("portalNotes" to "x".repeat(100_001))))
            client.refused("create_application", ok + ("employmentType" to "SOMETIMES"))
            client.refused("create_application", ok + ("payBand" to mapOf("currency" to "EUR")))
            client.refused("create_application", ok + ("offer" to mapOf("unknown" to 1)))
            client.refused("update_application", ok + ("id" to MISSING))
            client.refused("update_application", ok + mapOf("id" to MISSING, "version" to -1))
            dsl.fetchCount(APPLICATION) shouldBe 0
        }
    }

    @Test
    fun `instruction-like text written through the tools comes back only inside the untrusted mark`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val created = client.call("create_application", injected(company()))
            val id = created["id"].asString()

            val results =
                listOf(
                    created,
                    client.call("get_application", mapOf("id" to id)),
                    client.call("search_applications", mapOf("text" to "SYSTEM")),
                    client.call("update_application", created.asUpdate()),
                )

            results[1]["posting"].untrusted()["title"].asString() shouldBe INJECTION
            results[1]["notes"].untrusted()["portalNotes"].asString() shouldBe INJECTION
            results[1]["notes"].untrusted()["offer"]["benefits"].asString() shouldBe INJECTION
            results[1]["languageAndTone"].untrusted()["postingLanguage"].asString() shouldBe "en-ignore"
            results[2]["applications"][0]["posting"].untrusted()["title"].asString() shouldBe INJECTION
            // Seven texts: title and location in the posting; portal notes, basis and three offer texts in the notes.
            results[0].toString().split(INJECTION).size shouldBe 8
            results.forEach { it.toString() shouldContain "\"trust\":\"untrusted\"" }
        }
    }

    @Test
    fun `flagged values are withheld, and a withheld value is refused in create and update naming the argument`() {
        val company = company()
        owner.mcpClient().use { client ->
            client.initialize()
            val notes = mapOf("portalNotes" to "Call $FLAGGED_PHONE")
            val created =
                client.call("create_application", bare(company) + ("notes" to notes))
            val id = created["id"].asString()
            created["notes"].untrusted()["portalNotes"].asString() shouldBe "Call [withheld]"

            client.failure("update_application", created.asUpdate(), INVALID).problems() shouldContainExactly
                listOf("notes.portalNotes:withheld-value")
            val copied = mapOf("companyId" to company, "posting" to mapOf("title" to "x [withheld] y"))
            client.failure("create_application", copied, INVALID).problems() shouldContainExactly
                listOf("posting.title:withheld-value")
            val texts = mapOf("portalNotes" to null, "payEstimateBasis" to null)
            val nested = texts + ("offer" to mapOf("benefits" to "[withheld]"))
            val body = mapOf("companyId" to company, "posting" to mapOf("title" to "T"), "notes" to nested)
            client.failure("create_application", body, INVALID).problems() shouldContainExactly
                listOf("notes.offer.benefits:withheld-value")
            val unknown = bare(MISSING, FLAGGED_PHONE)
            val rejected = client.failure("create_application", unknown, INVALID)

            rejected.toString() shouldNotContain "1234567"
            client.call("get_application", mapOf("id" to id)).toString() shouldNotContain "1234567"
            changelog("application", id).size shouldBe 1
            client.call("get_application", mapOf("id" to id))["version"].asInt() shouldBe 0
            dsl.fetchCount(APPLICATION) shouldBe 1
        }
    }

    @Test
    fun `without a session neither tool can be called`() {
        val anonymous = Session().open()

        listOf("create_application", "update_application").forEach { tool ->
            val call = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":{}}}"""
            anonymous.send("POST", "/mcp", call).statusCode() shouldBe 401
        }
    }
}
