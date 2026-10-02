// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.client.McpSyncClient
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * The rule for replace-style updates (#241) for `update_company`, `update_contact` and `set_application_contacts`,
 * and the `[withheld]` refusal of `create_company`, `create_contact` and `create_task`, with the MCP SDK client.
 */
class McpUpdateRulesContractTest : McpToolContractSupport() {
    private companion object {
        const val MARKER = "[withheld]"
        const val INVALID = "invalid-arguments"
    }

    private val rowQueries =
        listOf(
            "select * from company order by 1",
            "select * from contact order by 1",
            "select * from contact_channel order by 1, 2",
            "select * from application order by 1",
            "select * from application_contact order by 1, 2",
            "select * from task order by 1",
            "select * from changelog_entry order by occurred_at, id",
        )

    private fun rows() = rowQueries.map { dsl.fetch(it).formatCSV() }

    /** What a client sends back: the answer without `readOnly`, the content of each untrusted wrapper in place. */
    private fun JsonNode.asUpdate(): Map<String, Any?> =
        propertyNames()
            .filter { it != "readOnly" }
            .associateWith { name -> this[name].let { if (it.has("trust")) it["content"] else it }.toPlain() }

    private fun JsonNode.toPlain(): Any? =
        when {
            isNull -> null
            isIntegralNumber -> asLong()
            isNumber -> asDouble()
            isBoolean -> asBoolean()
            isObject -> propertyNames().associateWith { this[it].toPlain() }
            isArray -> values().map { it.toPlain() }
            else -> asString()
        }

    private fun JsonNode.asMap(): Map<String, Any?> = propertyNames().associateWith { this[it].toPlain() }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.inner(key: String) = this[key] as Map<String, Any?>

    private fun McpSyncClient.fullCompany(): String {
        val fields =
            mapOf(
                "name" to "ACME GmbH",
                "website" to "https://acme.example",
                "industry" to "Software",
                "size" to "SMALL",
                "locations" to listOf("Berlin", "Remote"),
                "careersPage" to "https://acme.example/jobs",
                "researchNotes" to "Met at a fair",
            )
        return call("create_company", fields)["id"].asString()
    }

    private fun McpSyncClient.fullContact(company: String): String {
        val channels =
            listOf(
                mapOf("kind" to "EMAIL", "value" to "erika@acme.example", "label" to "work"),
                mapOf("kind" to "PHONE", "value" to "0711 123"),
            )
        val fields =
            mapOf(
                "name" to "Erika Mustermann",
                "role" to "Recruiter",
                "companyId" to company,
                "channels" to channels,
                "relationshipNotes" to "Nice",
            )
        return call("create_contact", fields)["id"].asString()
    }

    @Test
    fun `a fully populated company goes back through update_company unchanged, no row and no changelog entry`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val id = client.fullCompany()
            val before = rows()
            val read = client.call("get_company", mapOf("id" to id))
            read["readOnly"]["applicationCount"].asInt() shouldBe 0

            val answer = client.call("update_company", read.asUpdate())

            answer shouldBe read
            rows() shouldBe before
            changelog("company", id).size shouldBe 1
        }
    }

    @Test
    fun `a fully populated contact goes back through update_contact unchanged, no row and no changelog entry`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val id = client.fullContact(client.fullCompany())
            val before = rows()
            val read = client.call("get_contact", mapOf("id" to id))

            val answer = client.call("update_contact", read.asUpdate())

            answer shouldBe read
            rows() shouldBe before
            changelog("contact", id).size shouldBe 1
        }
    }

    @Test
    fun `update_company leaving out any property is refused by the schema and stores nothing`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val id = client.fullCompany()
            val before = rows()
            val full = client.call("get_company", mapOf("id" to id)).asUpdate()
            val company = full.inner("company")

            full.keys.forEach { key -> client.refused("update_company", full - key) }
            company.keys.forEach { key -> client.refused("update_company", full + ("company" to (company - key))) }

            rows() shouldBe before
        }
    }

    @Test
    fun `update_contact leaving out any property is refused by the schema and stores nothing`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val id = client.fullContact(client.fullCompany())
            val before = rows()
            val full = client.call("get_contact", mapOf("id" to id)).asUpdate()
            val contact = full.inner("contact")
            val channels = contact["channels"] as List<*>

            full.keys.forEach { key -> client.refused("update_contact", full - key) }
            contact.keys.forEach { key -> client.refused("update_contact", full + ("contact" to (contact - key))) }
            listOf("kind", "value", "label").forEach { key ->
                val broken = listOf((channels[0] as Map<*, *>) - key, channels[1])
                client.refused("update_contact", full + ("contact" to (contact + ("channels" to broken))))
            }

            rows() shouldBe before
        }
    }

    @Test
    fun `the whole get result with its read-only part, or a wrapper instead of its content, is refused`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val companyId = client.fullCompany()
            val contactId = client.fullContact(companyId)
            val before = rows()
            val company = client.call("get_company", mapOf("id" to companyId))
            val contact = client.call("get_contact", mapOf("id" to contactId))

            client.refused("update_company", company.asMap())
            client.refused("update_contact", contact.asMap())
            client.refused("update_company", company.asUpdate() + ("readOnly" to company["readOnly"].toPlain()))
            client.refused("update_contact", contact.asUpdate() + ("readOnly" to contact["readOnly"].toPlain()))
            client.refused("update_company", company.asUpdate() + ("company" to company["company"].toPlain()))
            client.refused("update_contact", contact.asUpdate() + ("contact" to contact["contact"].toPlain()))

            rows() shouldBe before
        }
    }

    @Test
    fun `an explicit null or empty list clears a company and a contact, each with one entry by the AI`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val companyId = client.fullCompany()
            val contactId = client.fullContact(companyId)
            val company = client.call("get_company", mapOf("id" to companyId)).asUpdate()
            val contact = client.call("get_contact", mapOf("id" to contactId)).asUpdate()
            val cleared =
                mapOf("name" to "ACME GmbH", "website" to null, "industry" to null, "size" to null) +
                    mapOf("locations" to emptyList<String>(), "careersPage" to null, "researchNotes" to null)
            val removed =
                mapOf("name" to "Erika", "role" to null, "channels" to emptyList<Any>(), "relationshipNotes" to null)

            val companyAnswer = client.call("update_company", company + ("company" to cleared))
            val contactAnswer = client.call("update_contact", contact + ("companyId" to null) + ("contact" to removed))

            companyAnswer["version"].asInt() shouldBe 1
            companyAnswer["company"].untrusted()["website"].isNull shouldBe true
            companyAnswer["company"].untrusted()["locations"].size() shouldBe 0
            contactAnswer["version"].asInt() shouldBe 1
            contactAnswer["companyId"].isNull shouldBe true
            contactAnswer["contact"].untrusted()["channels"].size() shouldBe 0
            changelog("company", companyId).map { it.second } shouldContainExactly listOf("AI", "AI")
            changelog("contact", contactId).map { it.second } shouldContainExactly listOf("AI", "AI")
        }
    }

    @Test
    fun `update tools name a withheld value by its path in the nested object`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val companyId = client.fullCompany()
            val contactId = client.fullContact(companyId)
            val before = rows()
            val company = client.call("get_company", mapOf("id" to companyId)).asUpdate()
            val contact = client.call("get_contact", mapOf("id" to contactId)).asUpdate()

            val notes = company.inner("company") + ("researchNotes" to "Call $MARKER")
            client.failure("update_company", company + ("company" to notes), INVALID).problems() shouldBe
                listOf("company.researchNotes:withheld-value")
            val named = contact.inner("contact") + ("role" to MARKER)
            client.failure("update_contact", contact + ("contact" to named), INVALID).problems() shouldBe
                listOf("contact.role:withheld-value")

            rows() shouldBe before
        }
    }

    @Test
    fun `update problems are named by the nested path the client sent`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val companyId = client.fullCompany()
            val contactId = client.fullContact(companyId)
            val company = client.call("get_company", mapOf("id" to companyId)).asUpdate()
            val contact = client.call("get_contact", mapOf("id" to contactId)).asUpdate()
            val badChannel = mapOf("kind" to "EMAIL", "value" to "nope", "label" to null)

            val blank = company.inner("company") + ("name" to " ")
            client.failure("update_company", company + ("company" to blank), INVALID).problems() shouldBe
                listOf("company.name:required")
            val bad = contact.inner("contact") + ("channels" to listOf(badChannel))
            client.failure("update_contact", contact + ("contact" to bad), INVALID).problems() shouldBe
                listOf("contact.channels[0].value:invalid-email")
        }
    }

    @Test
    fun `set_application_contacts needs contactIds, which is never assumed, and only an empty list unlinks all`() {
        val company = owner.create("/api/companies", """{"name":"ACME GmbH"}""")
        val application = owner.create("/api/applications", """{"title":"Kotlin Engineer","companyId":"$company"}""")
        val contact = owner.create("/api/contacts", """{"name":"Erika Mustermann"}""")
        owner.mcpClient().use { client ->
            client.initialize()
            client.call(
                "set_application_contacts",
                mapOf("id" to application, "version" to 0, "contactIds" to listOf(contact)),
            )
            val before = rows()
            val read = client.call("get_application", mapOf("id" to application))
            read["readOnly"]["contactIds"][0].asString() shouldBe contact

            client.refused("set_application_contacts", mapOf("id" to application, "version" to 1))
            client.refused(
                "set_application_contacts",
                mapOf("id" to application, "version" to 1, "contactIds" to null),
            )
            client.refused("set_application_contacts", read.asMap())
            val sendable = mapOf("id" to application, "version" to 1)
            client.refused("set_application_contacts", sendable + ("readOnly" to read["readOnly"].toPlain()))

            rows() shouldBe before
            val none = sendable + ("contactIds" to emptyList<String>())
            client.call("set_application_contacts", none)["readOnly"]["contactIds"].size() shouldBe 0
        }
    }

    @Test
    fun `create_company refuses the withheld marker in every free-text argument and stores nothing`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val before = rows()
            val texts = listOf("name", "industry", "researchNotes")

            texts.forEach { key ->
                client
                    .failure("create_company", mapOf("name" to "ACME", key to "x $MARKER"), INVALID)
                    .problems() shouldBe listOf("$key:withheld-value")
            }
            client
                .failure("create_company", mapOf("name" to "ACME", "locations" to listOf("Berlin", MARKER)), INVALID)
                .problems() shouldBe listOf("locations[1]:withheld-value")
            listOf("website", "careersPage").forEach { key ->
                client
                    .failure("create_company", mapOf("name" to "ACME", key to "https://$MARKER.example"), INVALID)
                    .problems() shouldBe listOf("$key:withheld-value")
            }

            rows() shouldBe before
        }
    }

    @Test
    fun `create_contact refuses the withheld marker in every free-text argument and stores nothing`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val before = rows()
            val channel = mapOf("kind" to "EMAIL", "value" to "erika@acme.example", "label" to "work")

            listOf("name", "role", "relationshipNotes").forEach { key ->
                client
                    .failure("create_contact", mapOf("name" to "E", key to "x $MARKER"), INVALID)
                    .problems() shouldBe listOf("$key:withheld-value")
            }
            listOf("value", "label").forEach { key ->
                val marked = mapOf("name" to "E", "channels" to listOf(channel, channel + (key to MARKER)))
                client.failure("create_contact", marked, INVALID).problems() shouldBe
                    listOf("channels[1].$key:withheld-value")
            }

            rows() shouldBe before
        }
    }

    @Test
    fun `create_task refuses the withheld marker in every free-text argument and stores nothing`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val before = rows()
            val task = mapOf("title" to "Call back", "timeZone" to "Europe/Berlin", "bucket" to "TODAY")

            listOf("title", "notes", "timeZone").forEach { key ->
                client.failure("create_task", task + (key to "x $MARKER"), INVALID).problems() shouldBe
                    listOf("$key:withheld-value")
            }
            val exact = task - "bucket" + ("localDue" to "2026-10-05T10:00$MARKER")
            client.failure("create_task", exact, INVALID).problems() shouldBe listOf("localDue:withheld-value")

            rows() shouldBe before
        }
    }
}
