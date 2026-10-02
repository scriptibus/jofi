// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.client.McpSyncClient
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.Locale
import java.util.UUID

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
    fun `an excerpt never ends inside a flagged value`() {
        val application = application()
        // The phone number starts just before the cut at 300 characters: its first digits would be in the excerpt.
        val notes = "x".repeat(EXCERPT_LENGTH - 5) + " $FLAGGED_PHONE and more"
        owner.mcpClient().use { client ->
            client.initialize()
            client.call("log_interview", interview(application, "notes" to notes, "preparationNotes" to notes))

            val listed = client.call("list_interviews", mapOf("applicationId" to application)).toString()

            listed shouldNotContain "0170"
            listed shouldNotContain "1234"
        }
    }

    @Test
    fun `a flagged value in both notes never reaches the model, from get, log, list or an update built from a read`() {
        val application = application()
        val straddling = "x".repeat(EXCERPT_LENGTH - 5) + " $FLAGGED_PHONE and more"
        val both = arrayOf("notes" to "Call $FLAGGED_PHONE", "preparationNotes" to "Or $FLAGGED_PHONE")
        owner.mcpClient().use { client ->
            client.initialize()
            val logged = client.call("log_interview", interview(application, *both))
            val id = logged["id"].asString()
            client.call(
                "log_interview",
                interview(application, "notes" to straddling, "preparationNotes" to straddling),
            )

            val read = client.call("get_interview", mapOf("applicationId" to application, "id" to id))
            val newest = client.call("list_interviews", mapOf("applicationId" to application))
            val oldest =
                client.call(
                    "list_interviews",
                    mapOf("applicationId" to application, "direction" to "ASCENDING"),
                )

            listOf(logged, read, newest, oldest).forEach {
                it.toString() shouldNotContain "0170"
                it.toString() shouldNotContain "1234"
            }
            read["interview"].untrusted()["notes"].asString() shouldBe "Call [withheld]"
            read["interview"].untrusted()["preparationNotes"].asString() shouldBe "Or [withheld]"
            assertWithheldReadRefused(client, read, id)
        }
    }

    /** Written back, the marker would replace the real number: refused, nothing stored. */
    private fun assertWithheldReadRefused(
        client: McpSyncClient,
        read: JsonNode,
        id: String,
    ) {
        client.failure("update_interview", read.asUpdate(), INVALID).problems() shouldContainExactly
            listOf("interview.preparationNotes:withheld-value")
        changelog("interview", id).size shouldBe 1
        dsl.fetchCount(
            INTERVIEW,
            INTERVIEW.ID.eq(UUID.fromString(id)).and(INTERVIEW.NOTES.contains(FLAGGED_PHONE)),
        ) shouldBe
            1
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
