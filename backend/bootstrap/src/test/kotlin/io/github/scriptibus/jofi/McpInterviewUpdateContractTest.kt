// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.client.McpSyncClient
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.Locale

/** `update_interview` (#118): the replace-style rule, replacing and clearing participants, and a long list. */
class McpInterviewUpdateContractTest : McpInterviewContractSupport() {
    @Test
    fun `an update that leaves a property out is refused by the schema, and nothing changes`() {
        val application = application()
        val contact = owner.create("/api/contacts", """{"name":"Erika"}""")
        owner.mcpClient().use { client ->
            client.initialize()
            val details = interview(application, "participantIds" to listOf(contact), "preparationNotes" to "PREP")
            val logged =
                client.call(
                    "log_interview",
                    details + ("interview" to mapOf("preparationNotes" to "PREP", "notes" to "NOTES")),
                )
            val full = logged.asUpdate() + ("localStart" to "2099-01-06T10:00")

            refuseEachOmission(client, full)
            client.refused("update_interview", full + ("readOnly" to mapOf("startsAt" to "2099-01-01T00:00:00Z")))

            assertUntouched(client, application, logged["id"].asString())
        }
    }

    @Test
    fun `what list_upcoming_interviews gives is not enough to update an interview`() {
        val application = application()
        owner.mcpClient().use { client ->
            client.initialize()
            val logged = client.call("log_interview", interview(application, "notes" to "NOTES"))
            val entry = client.call("list_upcoming_interviews", mapOf())["interviews"][0]

            val sent =
                mapOf(
                    "applicationId" to entry["applicationId"].asString(),
                    "id" to entry["id"].asString(),
                    "version" to 0,
                ) +
                    mapOf(
                        "type" to entry["type"].asString(),
                        "localStart" to "2099-01-06T10:00",
                        "timeZone" to entry["timeZone"].asString(),
                    )
            client.refused("update_interview", sent)

            val read = client.call("list_interviews", mapOf("applicationId" to application))["interviews"][0]
            read["interview"].untrusted()["notes"].asString() shouldBe "NOTES"
            changelog("interview", logged["id"].asString()).size shouldBe 1
        }
    }

    /** Leaving out any of the required properties, nested ones included, is refused. */
    private fun refuseEachOmission(
        client: McpSyncClient,
        full: Map<String, Any?>,
    ) {
        val required = listOf("applicationId", "id", "localStart", "participantIds", "outcome", "interview")
        (required + listOf("timeZone", "type", "version")).forEach { key ->
            client.refused(
                "update_interview",
                full - key,
            )
        }
        val notes = full["interview"] as Map<*, *>
        listOf("preparationNotes", "notes").forEach { key ->
            client.refused("update_interview", full + ("interview" to (notes - key)))
        }
    }

    private fun assertUntouched(
        client: McpSyncClient,
        application: String,
        id: String,
    ) {
        changelog("interview", id).size shouldBe 1
        val read: JsonNode = client.call("list_interviews", mapOf("applicationId" to application))["interviews"][0]
        read["version"].asInt() shouldBe 0
        read["participantIds"].size() shouldBe 1
        read["interview"].untrusted()["notes"].asString() shouldBe "NOTES"
        read["interview"].untrusted()["preparationNotes"].asString() shouldBe "PREP"
    }

    @Test
    fun `participants are replaced and cleared by an explicit value, and the notes stay`() {
        val application = application()
        val first = owner.create("/api/contacts", """{"name":"Erika"}""")
        val second = owner.create("/api/contacts", """{"name":"Max"}""")
        owner.mcpClient().use { client ->
            client.initialize()
            val logged =
                client.call(
                    "log_interview",
                    interview(
                        application,
                        "participantIds" to listOf(first),
                        "notes" to "N",
                    ),
                )

            fun change(
                from: JsonNode,
                participants: List<String>?,
            ) = client.call("update_interview", from.asUpdate() + ("participantIds" to participants))
            val replaced = change(logged, listOf(second))
            val emptied = change(replaced, emptyList())
            val relinked = change(emptied, listOf(first))
            val cleared = change(relinked, null)

            replaced["participantIds"].values().map { it.asString() } shouldContainExactly listOf(second)
            emptied["participantIds"].size() shouldBe 0
            relinked["participantIds"].values().map { it.asString() } shouldContainExactly listOf(first)
            cleared["participantIds"].size() shouldBe 0
            cleared["interview"].untrusted()["notes"].asString() shouldBe "N"
            changelog("interview", logged["id"].asString()).map { it.second } shouldBe List(5) { "AI" }
        }
    }

    @Test
    fun `list_interviews with more interviews than it returns answers the earliest ones and the total`() {
        val application = application()
        owner.mcpClient().use { client ->
            client.initialize()
            val starts = (0 until 52).map { String.format(Locale.ROOT, "2099-02-01T%02d:%02d", it / 4, (it % 4) * 15) }
            starts.reversed().forEach { start ->
                client.call("log_interview", interview(application, "localStart" to start))
            }

            val listed = client.call("list_interviews", mapOf("applicationId" to application))

            listed["total"].asInt() shouldBe 52
            listed["interviews"].size() shouldBe 50
            listed["interviews"][0]["localStart"].asString() shouldBe "${starts[0]}:00"
            listed["interviews"][49]["localStart"].asString() shouldBe "${starts[49]}:00"
        }
    }
}
