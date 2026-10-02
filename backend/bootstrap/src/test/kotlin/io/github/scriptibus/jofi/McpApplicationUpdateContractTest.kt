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

/** `update_application` (#118): the replace-style rule, round trips and what an update changes. */
class McpApplicationUpdateContractTest : McpApplicationContractSupport() {
    @Test
    fun `a fully populated application that was applied to and rejected goes back unchanged, no row changes`() {
        val company = company()
        val contact = owner.create("/api/contacts", """{"name":"Erika"}""")
        owner.mcpClient().use { client ->
            client.initialize()
            val id = populate(client, company, contact)
            val before = applicationRows()
            val read = client.call("get_application", mapOf("id" to id))
            read["readOnly"]["status"].asString() shouldBe "REJECTED"
            read["readOnly"]["texts"].untrusted()["sources"].size() shouldBe 2
            read["readOnly"]["texts"].untrusted()["declineReason"].asString() shouldBe "Too little pay"
            read["readOnly"]["contactIds"][0].asString() shouldBe contact

            val answer = client.call("update_application", read.asUpdate())

            answer shouldBe read
            applicationRows() shouldBe before
        }
    }

    private fun populate(
        client: McpSyncClient,
        company: String,
        contact: String,
    ): String {
        val id = client.call("create_application", everyField(company))["id"].asString()
        listOf("https://jobs.example/1", "https://jobs.example/2").forEach { url ->
            val source = """{"kind":"URL","originalUrl":"$url","description":"Text $url"}"""
            owner.send("POST", "/api/applications/$id/sources", source)
        }

        fun version() = client.call("get_application", mapOf("id" to id))["version"].asInt()
        val link = mapOf("id" to id, "version" to version(), "contactIds" to listOf(contact))
        client.call("set_application_contacts", link)
        owner.send("PUT", "/api/applications/$id/status", """{"status":"APPLIED","basedOnVersion":${version()}}""")
        val reason = """"declineCategory":"SALARY","reason":"Too little pay""""
        val rejected = """{"status":"REJECTED",$reason,"basedOnVersion":${version()}}"""
        owner.send("PUT", "/api/applications/$id/status", rejected).statusCode() shouldBe 200
        return id
    }

    @Test
    fun `update_application replaces the details, bumps the version and records what changed, as the AI`() {
        val company = company()
        owner.mcpClient().use { client ->
            client.initialize()
            val created = client.call("create_application", everyField(company))
            val id = created["id"].asString()
            val newPosting = mapOf("title" to "Staff Engineer", "location" to null)
            val noOffer = (created.asUpdate()["notes"] as Map<*, *>) + ("offer" to null)

            val change = mapOf("posting" to newPosting, "offer" to null, "notes" to noOffer)
            val edited = client.call("update_application", created.asUpdate() + change)

            edited["version"].asInt() shouldBe 1
            edited["posting"].untrusted()["title"].asString() shouldBe "Staff Engineer"
            edited["offer"].isNull shouldBe true
            edited["readOnly"]["status"].asString() shouldBe "DISCOVERED"
            changelog("application", id).map { it.second } shouldBe listOf("AI", "AI")
            val sql = "select string_agg(field_changes::text, ' ') from changelog_entry where entity_id = '$id'"
            val fields = dsl.fetchValue(sql)
            fields.toString() shouldContain "title"
            fields.toString() shouldContain "location"
        }
    }

    @Test
    fun `clearing the offer in one place only, or sending a basis without an estimated band, is refused`() {
        val company = company()
        owner.mcpClient().use { client ->
            client.initialize()
            val created = client.call("create_application", everyField(company))
            val before = applicationRows()
            val full = created.asUpdate()
            val notes = full["notes"] as Map<*, *>

            val detailsOnly =
                client.failure(
                    "update_application",
                    full + ("notes" to (notes + ("offer" to null))),
                    INVALID,
                )
            val textsOnly = client.failure("update_application", full + ("offer" to null), INVALID)
            val band = (full["payBand"] as Map<*, *>) + ("source" to "POSTING")
            val basis = client.failure("update_application", full + ("payBand" to band), INVALID)
            val noBand =
                client.failure(
                    "create_application",
                    bare(company) + ("notes" to mapOf("payEstimateBasis" to "x")),
                    INVALID,
                )

            detailsOnly.problems() shouldContainExactly listOf("offer:inconsistent", "notes.offer:inconsistent")
            textsOnly.problems() shouldContainExactly detailsOnly.problems()
            basis.problems() shouldContainExactly listOf("notes.payEstimateBasis:not-applicable")
            noBand.problems() shouldContainExactly listOf("notes.payEstimateBasis:not-applicable")
            applicationRows() shouldBe before
        }
    }

    @Test
    fun `an update that leaves a property out is refused by the schema, and nothing is stored`() {
        val company = company()
        owner.mcpClient().use { client ->
            client.initialize()
            val created = client.call("create_application", everyField(company))
            val id = created["id"].asString()
            val before = applicationRows()
            val full = created.asUpdate()

            val properties = full.keys.filter { it != "id" && it != "version" }
            properties.forEach { key -> client.refused("update_application", full - key) }
            val notes = full["notes"] as Map<*, *>
            val offerTexts = notes["offer"] as Map<*, *>
            listOf("portalNotes", "payEstimateBasis", "offer").forEach { key ->
                client.refused("update_application", full + ("notes" to (notes - key)))
            }
            listOf("bonus", "benefits", "noticePeriod").forEach { key ->
                client.refused("update_application", full + ("notes" to (notes + ("offer" to (offerTexts - key)))))
            }
            val offer = full["offer"] as Map<*, *>
            listOf("salary", "remoteSharePercent", "vacationDays", "startDate", "answerBy").forEach { key ->
                client.refused("update_application", full + ("offer" to (offer - key)))
            }
            client.refused("update_application", full + ("posting" to mapOf("title" to "T")))
            client.refused("update_application", full + ("languageAndTone" to mapOf("tone" to null)))
            client.refused("update_application", full + ("readOnly" to mapOf("status" to "OFFER")))

            applicationRows() shouldBe before
            changelog("application", id).size shouldBe 1
        }
    }
}
