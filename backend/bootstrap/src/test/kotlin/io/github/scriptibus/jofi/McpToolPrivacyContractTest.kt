// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** The company and contact tools (#119): flagged values never leave, and no tool works without a session. */
class McpToolPrivacyContractTest : McpToolContractSupport() {
    private fun contactUpdate(
        id: String,
        phone: Map<String, String>,
    ): Map<String, Any?> {
        val fields =
            mapOf("name" to "Erika", "role" to "HR", "relationshipNotes" to null) +
                mapOf("channels" to listOf(phone + ("label" to null)))
        return mapOf("id" to id, "version" to 0, "companyId" to null, "contact" to fields)
    }

    private fun companyUpdate(id: String): Map<String, Any?> {
        val fields =
            mapOf("name" to "ACME 2", "website" to null, "industry" to null, "size" to null) +
                mapOf("locations" to emptyList<String>(), "careersPage" to null, "researchNotes" to FLAGGED_PHONE)
        return mapOf("id" to id, "version" to 0, "company" to fields)
    }

    @Test
    fun `flagged values are withheld from every result of the company and contact tools`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val phone = mapOf("kind" to "PHONE", "value" to FLAGGED_PHONE)
            val company =
                client.call("create_company", mapOf("name" to "ACME", "researchNotes" to "Call $FLAGGED_PHONE"))
            val companyId = company["id"].asString()
            val contact = client.call("create_contact", mapOf("name" to "Erika", "channels" to listOf(phone)))
            val contactId = contact["id"].asString()
            val contactUpdate = contactUpdate(contactId, phone)
            val companyUpdate = companyUpdate(companyId)

            val results =
                listOf(
                    company,
                    contact,
                    client.call("get_company", mapOf("id" to companyId)),
                    client.call("search_companies", mapOf("text" to "ACME")),
                    client.call("get_contact", mapOf("id" to contactId)),
                    client.call("search_contacts", mapOf("text" to "Erika")),
                    client.call("update_contact", contactUpdate),
                    client.call("update_company", companyUpdate),
                )

            results.forEach { it.toString() shouldNotContain "1234567" }
            client.call("get_contact", mapOf("id" to contactId)).toString() shouldContain "[withheld]"
        }
    }

    @Test
    fun `the application answered by set_application_contacts has flagged values withheld`() {
        val company = owner.create("/api/companies", """{"name":"ACME GmbH"}""")
        val body = """{"title":"Kotlin Engineer","companyId":"$company","portalNotes":"Call $FLAGGED_PHONE"}"""
        val application = owner.create("/api/applications", body)
        owner.mcpClient().use { client ->
            client.initialize()

            val answer =
                client.call(
                    "set_application_contacts",
                    mapOf("id" to application, "version" to 0, "contactIds" to emptyList<String>()),
                )

            answer["notes"].untrusted()["portalNotes"].asString() shouldBe "Call [withheld]"
            answer.toString() shouldNotContain "1234567"
        }
    }

    @Test
    fun `a contact update that sends back a withheld value is refused and the stored value stays`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val phone = mapOf("kind" to "PHONE", "value" to FLAGGED_PHONE)
            val created = client.call("create_contact", mapOf("name" to "Erika", "channels" to listOf(phone)))
            val id = created["id"].asString()
            client
                .call(
                    "get_contact",
                    mapOf("id" to id),
                )["contact"]
                .untrusted()["channels"][0]["value"]
                .asString() shouldBe
                "[withheld]"

            val shown = listOf(mapOf("kind" to "PHONE", "value" to "+49 [withheld]", "label" to null))
            val fields = mapOf("name" to "Erika", "role" to null, "channels" to shown, "relationshipNotes" to null)
            val back = mapOf("id" to id, "version" to 0, "companyId" to null, "contact" to fields)
            client.failure("update_contact", back, "invalid-arguments").problems() shouldBe
                listOf("contact.channels[0].value:withheld-value")

            changelog("contact", id).size shouldBe 1
            client.call("get_contact", mapOf("id" to id))["version"].asInt() shouldBe 0
        }
    }

    @Test
    fun `a company update that sends back a withheld value is refused and the stored value stays`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val created =
                client.call(
                    "create_company",
                    mapOf("name" to "ACME", "researchNotes" to "Call $FLAGGED_PHONE"),
                )
            val id = created["id"].asString()

            val fields =
                mapOf("name" to "ACME", "website" to null, "industry" to null, "size" to null) +
                    mapOf(
                        "locations" to emptyList<String>(),
                        "careersPage" to null,
                        "researchNotes" to "Call [withheld]",
                    )
            val back = mapOf("id" to id, "version" to 0, "company" to fields)
            client.failure("update_company", back, "invalid-arguments").problems() shouldBe
                listOf("company.researchNotes:withheld-value")

            changelog("company", id).size shouldBe 1
            client.call("get_company", mapOf("id" to id))["version"].asInt() shouldBe 0
        }
    }

    @Test
    fun `an error answer never repeats a flagged value from the arguments`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val email = mapOf("kind" to "EMAIL", "value" to FLAGGED_PHONE)

            val rejected =
                client.failure("create_contact", mapOf("name" to "E", "channels" to listOf(email)), "invalid-arguments")

            rejected.toString() shouldNotContain "1234567"
        }
    }

    @Test
    fun `without a session no company or contact tool can be called`() {
        val anonymous = Session().open()
        val tools =
            listOf(
                "search_companies",
                "get_company",
                "create_company",
                "update_company",
                "search_contacts",
                "get_contact",
                "create_contact",
                "update_contact",
                "set_application_contacts",
            )

        tools.forEach { tool ->
            val call = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":{}}}"""
            anonymous.send("POST", "/mcp", call).statusCode() shouldBe 401
        }
    }
}
