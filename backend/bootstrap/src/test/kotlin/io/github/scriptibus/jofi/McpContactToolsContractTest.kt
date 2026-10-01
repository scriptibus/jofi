// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The contact tools and the application's contact links (#119) with the MCP SDK client against the running app. */
class McpContactToolsContractTest : McpToolContractSupport() {
    @Test
    fun `the contact tools are listed, the reading ones as read only`() {
        owner.mcpClient().use { client ->
            client.initialize()

            val tools = client.listTools().tools().associateBy { it.name() }

            listOf("search_contacts", "get_contact").forEach {
                tools.getValue(it).annotations().readOnlyHint() shouldBe true
            }
            listOf("create_contact", "update_contact", "set_application_contacts").forEach {
                tools.getValue(it).annotations().readOnlyHint() shouldBe false
            }
        }
    }

    @Test
    fun `a contact is created, found, read and updated with its channels, each change logged with the AI`() {
        val company = owner.create("/api/companies", """{"name":"ACME GmbH"}""")
        owner.mcpClient().use { client ->
            client.initialize()
            val email = mapOf("kind" to "EMAIL", "value" to "erika@acme.example")

            val input =
                mapOf(
                    "name" to "Erika Mustermann",
                    "role" to "Recruiter",
                    "companyId" to company,
                    "channels" to listOf(email),
                )
            val created = client.call("create_contact", input)
            val id = created["id"].asString()
            created["companyId"].asString() shouldBe company
            created["contact"].untrusted()["channels"][0]["value"].asString() shouldBe "erika@acme.example"
            changelog("contact", id) shouldContainExactly
                listOf("Created contact; fields: name, role, company, channels" to "AI")

            val search = client.call("search_contacts", mapOf("text" to "Erika", "companyId" to company))
            search["total"].asInt() shouldBe 1
            search["contacts"][0]["contact"].untrusted()["role"].asString() shouldBe "Recruiter"
            client.call("get_contact", mapOf("id" to id))["version"].asInt() shouldBe 0

            val update = mapOf("id" to id, "version" to 0, "name" to "Erika Musterfrau", "relationshipNotes" to "Nice")
            val updated = client.call("update_contact", update)
            updated["version"].asInt() shouldBe 1
            updated["relationshipNotes"].asString() shouldBe "Nice"
            updated["contact"].untrusted()["channels"].size() shouldBe 0
            changelog("contact", id).map { it.second } shouldContainExactly listOf("AI", "AI")
        }
    }

    @Test
    fun `contact tools answer validation errors, stale versions and missing entities as tool errors`() {
        val contact = owner.create("/api/contacts", """{"name":"Erika Mustermann"}""")
        owner.mcpClient().use { client ->
            client.initialize()
            val badChannel = mapOf("kind" to "EMAIL", "value" to "not-an-email")

            client
                .failure("create_contact", mapOf("name" to "E", "channels" to listOf(badChannel)), "invalid-arguments")
                .problems() shouldContainExactly listOf("channels[0].value:invalid-email")
            client
                .failure("create_contact", mapOf("name" to "E", "companyId" to MISSING), "invalid-arguments")
                .problems() shouldContainExactly listOf("companyId:not-found")
            client.refused("create_contact", mapOf("name" to "E", "channels" to listOf(mapOf("kind" to "FAX"))))
            client.failure("update_contact", mapOf("id" to contact, "version" to 3, "name" to "E"), "version-conflict")
            client.failure("update_contact", mapOf("id" to MISSING, "version" to 0, "name" to "E"), "not-found")
            client.failure("get_contact", mapOf("id" to MISSING), "not-found")
            client.refused("get_contact", mapOf())
            client.refused("search_contacts", mapOf("page" to -1))
            changelog("contact", contact).size shouldBe 1
        }
    }

    @Test
    fun `contacts are linked to and unlinked from an application, logged with the AI as actor`() {
        val company = owner.create("/api/companies", """{"name":"ACME GmbH"}""")
        val application = owner.create("/api/applications", """{"title":"Kotlin Engineer","companyId":"$company"}""")
        val contact = owner.create("/api/contacts", """{"name":"Erika Mustermann"}""")
        owner.mcpClient().use { client ->
            client.initialize()

            val link = mapOf("id" to application, "version" to 0, "contactIds" to listOf(contact))
            val linked = client.call("set_application_contacts", link)
            linked["contactIds"][0].asString() shouldBe contact
            linked["version"].asInt() shouldBe 1
            client.call("search_applications", mapOf("contactId" to contact))["total"].asInt() shouldBe 1
            changelog("application", application).last() shouldBe ("Changed linked contacts" to "AI")

            val unlink = mapOf("id" to application, "version" to 1, "contactIds" to emptyList<String>())
            client.call("set_application_contacts", unlink)["contactIds"].size() shouldBe 0
            val links = changelog("application", application).filter { it.first == "Changed linked contacts" }
            links.map { it.second } shouldContainExactly listOf("AI", "AI")
        }
    }

    @Test
    fun `linking answers missing entities, stale versions and bad arguments as tool errors, linking nothing`() {
        val company = owner.create("/api/companies", """{"name":"ACME GmbH"}""")
        val application = owner.create("/api/applications", """{"title":"Kotlin Engineer","companyId":"$company"}""")
        owner.mcpClient().use { client ->
            client.initialize()
            val none = emptyList<String>()

            client
                .failure(
                    "set_application_contacts",
                    mapOf("id" to application, "version" to 0, "contactIds" to listOf(MISSING)),
                    "invalid-arguments",
                ).problems() shouldContainExactly listOf("contactIds:not-found")
            val stale = mapOf("id" to application, "version" to 9, "contactIds" to none)
            client.failure("set_application_contacts", stale, "version-conflict")
            val missing = mapOf("id" to MISSING, "version" to 0, "contactIds" to none)
            client.failure("set_application_contacts", missing, "not-found")
            val bad = mapOf("id" to application, "version" to 0, "contactIds" to listOf("nope"))
            client.refused("set_application_contacts", bad)
            client.call("get_application", mapOf("id" to application))["contactIds"].size() shouldBe 0
        }
    }
}
