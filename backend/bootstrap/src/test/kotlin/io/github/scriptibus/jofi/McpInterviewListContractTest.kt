// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.client.McpSyncClient
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.Locale

/**
 * The bounded interview lists (#236, ADR-0056) with the MCP SDK client against the running app: every interview is
 * reachable exactly once in either direction, an answer stays small however long the notes are, `get_interview` has
 * the whole text.
 */
class McpInterviewListContractTest : McpInterviewContractSupport() {
    private companion object {
        const val COUNT = 120
        const val NOTE_LENGTH = 50_000
        const val PAGE_SIZE = 50
        const val EXCERPT_LENGTH = 300

        /** 50 entries with two 300-character excerpts: far below the 5.0 MB of two 50,000-character notes each. */
        const val MAX_PAGE_BYTES = 120_000
    }

    private fun start(index: Int) = String.format(Locale.ROOT, "2099-03-%02dT%02d:00", 1 + index / 24, index % 24)

    private fun note(index: Int) = "note-$index ".padEnd(NOTE_LENGTH, 'x')

    private fun logAll(
        client: McpSyncClient,
        application: String,
    ): List<String> =
        (0 until COUNT).map { index ->
            client
                .call(
                    "log_interview",
                    interview(
                        application,
                        "localStart" to start(index),
                        "notes" to note(index),
                        "preparationNotes" to note(index),
                    ),
                )["id"]
                .asString()
        }

    private fun McpSyncClient.pages(
        application: String,
        direction: String,
    ): List<JsonNode> {
        val pages = mutableListOf<JsonNode>()
        do {
            val arguments =
                mapOf(
                    "applicationId" to application,
                    "direction" to direction,
                    "page" to pages.size,
                    "size" to PAGE_SIZE,
                )
            pages.add(call("list_interviews", arguments))
        } while (pages.last()["hasMore"].asBoolean())
        return pages
    }

    @Test
    fun `120 interviews with huge notes are reached exactly once, oldest or newest first, each answer small`() {
        val application = application()
        owner.mcpClient().use { client ->
            client.initialize()
            val ids = logAll(client, application)

            val newest = client.pages(application, "DESCENDING")
            val oldest = client.pages(application, "ASCENDING")

            newest.flatMap { page -> page["interviews"].values().map { it["id"].asString() } } shouldContainExactly
                ids.reversed()
            oldest.flatMap { page -> page["interviews"].values().map { it["id"].asString() } } shouldContainExactly ids
            (newest + oldest).forEach { page ->
                page["total"].asInt() shouldBe COUNT
                (page.toString().toByteArray().size < MAX_PAGE_BYTES) shouldBe true
                page["interviews"].values().forEach {
                    val words = it["interview"].untrusted()
                    words["notesExcerpt"].asString().length shouldBe EXCERPT_LENGTH
                    words["notesTruncated"].asBoolean() shouldBe true
                    words["preparationNotesTruncated"].asBoolean() shouldBe true
                    words.has("notes") shouldBe false
                }
            }
            newest.map { it["hasMore"].asBoolean() } shouldContainExactly listOf(true, true, false)
        }
    }

    @Test
    fun `the default is newest first, so the latest interview is on the first page`() {
        val application = application()
        owner.mcpClient().use { client ->
            client.initialize()
            val ids =
                (0 until 3).map { index ->
                    client.call("log_interview", interview(application, "localStart" to start(index)))["id"].asString()
                }

            val listed = client.call("list_interviews", mapOf("applicationId" to application))

            listed["interviews"].values().map { it["id"].asString() } shouldContainExactly ids.reversed()
        }
    }

    @Test
    fun `get_interview has the whole notes, the participants and the version`() {
        val application = application()
        val contact = owner.create("/api/contacts", """{"name":"Erika"}""")
        owner.mcpClient().use { client ->
            client.initialize()
            val id =
                client
                    .call(
                        "log_interview",
                        interview(application, "participantIds" to listOf(contact), "notes" to note(1)),
                    )["id"]
                    .asString()

            val read = client.call("get_interview", mapOf("applicationId" to application, "id" to id))

            read["interview"].untrusted()["notes"].asString() shouldBe note(1)
            read["participantIds"][0].asString() shouldBe contact
            read["version"].asInt() shouldBe 0
            client.failure("get_interview", mapOf("applicationId" to application, "id" to MISSING), "not-found")
            client.failure("get_interview", mapOf("applicationId" to MISSING, "id" to id), "not-found")
        }
    }
}
