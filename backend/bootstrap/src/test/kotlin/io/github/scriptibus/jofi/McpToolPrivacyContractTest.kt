// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** The company and contact tools (#119): flagged values never leave, and no tool works without a session. */
class McpToolPrivacyContractTest : McpToolContractSupport() {
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
            val contactUpdate =
                mapOf("id" to contactId, "version" to 0, "name" to "Erika", "channels" to listOf(phone), "role" to "HR")
            val companyUpdate =
                mapOf("id" to companyId, "version" to 0, "name" to "ACME 2", "researchNotes" to FLAGGED_PHONE)

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
