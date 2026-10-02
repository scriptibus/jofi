// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.client.McpSyncClient
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The bounded task lists (#236, ADR-0056) with the MCP SDK client against the running app: every task is reachable
 * exactly once by paging, an answer stays small however long the notes are, and `get_task` has the whole text.
 */
class McpTaskListContractTest : McpToolContractSupport() {
    private companion object {
        const val COUNT = 120
        const val NOTE_LENGTH = 10_000
        const val PAGE_SIZE = 50
        const val EXCERPT_LENGTH = 300

        /** 50 entries with a 300-character excerpt, the untrusted mark and the keys: far below the 1.2 MB of notes. */
        const val MAX_PAGE_BYTES = 90_000
        const val ZONE = "UTC"
    }

    private fun longNote(index: Int) = "note-$index ".padEnd(NOTE_LENGTH, 'x')

    private fun createTasks(client: McpSyncClient): List<String> =
        (1..COUNT).map { index ->
            val arguments =
                mapOf("title" to "Task $index", "timeZone" to ZONE, "bucket" to "SOMEDAY", "notes" to longNote(index))
            client.call("create_task", arguments)["id"].asString()
        }

    private fun suggestWithNotes(index: Int): String {
        val id = UUID.randomUUID().toString()
        dsl.execute(
            "INSERT INTO task (id, title, notes, bucket_span, bucket_starts_on, origin, suggestion_rule, " +
                "suggestion_key, state, version, created_at, updated_at) VALUES (?::uuid, ?, ?, 'DAY', ?::date, " +
                "'SUGGESTED', 'follow-up', ?, 'SUGGESTED', 0, now(), now())",
            id,
            "Suggestion $index",
            longNote(index),
            LocalDate.now(ZoneOffset.UTC).toString(),
            "application:$id",
        )
        return id
    }

    private fun bytes(answer: JsonNode) = answer.toString().toByteArray().size

    /** Every page of a list with `size` entries, until `hasMore` is false. */
    private fun McpSyncClient.pages(
        tool: String,
        arguments: Map<String, Any?>,
    ): List<JsonNode> {
        val pages = mutableListOf<JsonNode>()
        do {
            val page = call(tool, arguments + mapOf("page" to pages.size, "size" to PAGE_SIZE))
            pages.add(page)
        } while (page["hasMore"].asBoolean())
        return pages
    }

    @Test
    fun `120 tasks with long notes are reached exactly once over pages of 50, each answer small`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val created = createTasks(client)

            val pages = client.pages("list_tasks", mapOf("timeZone" to ZONE))

            val entries = pages.flatMap { page -> page["groups"].values().flatMap { it["tasks"].values() } }
            entries.map { it["id"].asString() }.sorted() shouldContainExactly created.sorted()
            pages.size shouldBe 3
            pages.forEach {
                it["total"].asInt() shouldBe COUNT
                it["size"].asInt() shouldBe PAGE_SIZE
                (bytes(it) < MAX_PAGE_BYTES) shouldBe true
            }
            pages.map { it["hasMore"].asBoolean() } shouldContainExactly listOf(true, true, false)
            entries.forEach { entry ->
                val words = entry["task"].untrusted()
                words["notesExcerpt"].asString().length shouldBe EXCERPT_LENGTH
                words["notesTruncated"].asBoolean() shouldBe true
                words.has("notes") shouldBe false
            }
        }
    }

    @Test
    fun `a page past the end is empty, and every group is still there`() {
        owner.mcpClient().use { client ->
            client.initialize()
            client.call("create_task", mapOf("title" to "One", "timeZone" to ZONE, "bucket" to "SOMEDAY"))

            val page = client.call("list_tasks", mapOf("timeZone" to ZONE, "page" to 7, "size" to PAGE_SIZE))

            page["groups"].size() shouldBe 7
            page["groups"].values().all { it["tasks"].size() == 0 } shouldBe true
            page["total"].asInt() shouldBe 1
            page["hasMore"].asBoolean() shouldBe false
        }
    }

    @Test
    fun `get_task has the whole notes, the version and the untrusted mark, and a missing task is not found`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val id = createTasks(client).first()

            val full = client.call("get_task", mapOf("id" to id))

            full["id"].asString() shouldBe id
            full["version"].asInt() shouldBe 0
            full["task"].untrusted()["notes"].asString() shouldBe longNote(1)
            client.failure("get_task", mapOf("id" to MISSING), "not-found")
        }
    }

    @Test
    fun `120 suggestions are reached exactly once over pages of 50, each answer small`() {
        val suggested = (1..COUNT).map(::suggestWithNotes)
        owner.mcpClient().use { client ->
            client.initialize()

            val pages = client.pages("list_task_suggestions", mapOf())

            val entries = pages.flatMap { it["tasks"].values() }
            entries.map { it["id"].asString() }.sorted() shouldContainExactly suggested.sorted()
            pages.forEach {
                it["total"].asInt() shouldBe COUNT
                (bytes(it) < MAX_PAGE_BYTES) shouldBe true
            }
            entries.forEach { it["task"].untrusted()["notesTruncated"].asBoolean() shouldBe true }
            val first = entries.first()["id"].asString()
            client
                .call("get_task", mapOf("id" to first))["task"]
                .untrusted()["notes"]
                .asString()
                .length shouldBe
                NOTE_LENGTH
        }
    }

    @Test
    fun `flagged values are withheld from list excerpts and from get_task`() {
        owner.mcpClient().use { client ->
            client.initialize()
            val notes = "Call $FLAGGED_PHONE soon"
            val id =
                client
                    .call(
                        "create_task",
                        mapOf("title" to "T", "timeZone" to ZONE, "bucket" to "SOMEDAY", "notes" to notes),
                    )["id"]
                    .asString()

            val listed = client.call("list_tasks", mapOf("timeZone" to ZONE))
            val read = client.call("get_task", mapOf("id" to id))

            listed.toString() shouldNotContain "1234567"
            read.toString() shouldNotContain "1234567"
            read["task"].untrusted()["notes"].asString() shouldBe "Call [withheld] soon"
        }
    }

    @Test
    fun `an excerpt never ends inside a flagged value, in lists of tasks and of suggestions`() {
        // The phone number starts just before the cut at 300 characters: its first digits would be in the excerpt.
        val notes = "x".repeat(EXCERPT_LENGTH - 5) + " $FLAGGED_PHONE and more"
        suggestWithNotesText(notes)
        owner.mcpClient().use { client ->
            client.initialize()
            val arguments = mapOf("title" to "T", "timeZone" to ZONE, "bucket" to "SOMEDAY", "notes" to notes)
            client.call("create_task", arguments)

            val listed = client.call("list_tasks", mapOf("timeZone" to ZONE)).toString()
            val suggested = client.call("list_task_suggestions", mapOf()).toString()

            listOf(listed, suggested).forEach {
                it shouldNotContain "0170"
                it shouldNotContain "1234"
            }
        }
    }

    private fun suggestWithNotesText(notes: String) {
        val id = UUID.randomUUID().toString()
        dsl.execute(
            "INSERT INTO task (id, title, notes, bucket_span, bucket_starts_on, origin, suggestion_rule, " +
                "suggestion_key, state, version, created_at, updated_at) VALUES (?::uuid, 'S', ?, 'DAY', ?::date, " +
                "'SUGGESTED', 'follow-up', ?, 'SUGGESTED', 0, now(), now())",
            id,
            notes,
            LocalDate.now(ZoneOffset.UTC).toString(),
            "application:$id",
        )
    }

    @Test
    fun `without a session get_task cannot be called`() {
        val call = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_task","arguments":{}}}"""

        Session().open().send("POST", "/mcp", call).statusCode() shouldBe 401
    }
}
