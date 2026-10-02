// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `create_company`, `create_contact` and `create_task` refuse a literal `[withheld]` marker (#241). */
class McpCreateWithheldContractTest : McpToolContractSupport() {
    private companion object {
        const val MARKER = "[withheld]"
        const val INVALID = "invalid-arguments"
    }

    private fun rows() =
        listOf(
            "select * from company order by 1",
            "select * from contact order by 1",
            "select * from contact_channel order by 1, 2",
            "select * from task order by 1",
            "select * from changelog_entry order by occurred_at, id",
        ).map { dsl.fetch(it).formatCSV() }

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
