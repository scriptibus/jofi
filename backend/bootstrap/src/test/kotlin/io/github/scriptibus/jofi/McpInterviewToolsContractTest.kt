// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

/** The interview tools (#118) with the MCP SDK client against the running app, the database and the changelog. */
class McpInterviewToolsContractTest : McpInterviewContractSupport() {
    @Test
    fun `an interview is logged, listed, updated and each change is logged with the AI`() {
        val application = application()
        val contact = owner.create("/api/contacts", """{"name":"Erika"}""")
        owner.mcpClient().use { client ->
            client.initialize()

            val details = interview(application, "participantIds" to listOf(contact), "preparationNotes" to "Read")
            val logged = client.call("log_interview", details)

            val id = logged["id"].asString()
            logged["version"].asInt() shouldBe 0
            logged["localStart"].asString() shouldContain "2099-01-05T10:00"
            logged["participantIds"][0].asString() shouldBe contact
            logged["interview"].untrusted()["preparationNotes"].asString() shouldBe "Read"
            changelog("interview", id).map { it.second } shouldContainExactly listOf("AI")

            val listed = client.call("list_interviews", mapOf("applicationId" to application))
            listed["total"].asInt() shouldBe 1
            listed["interviews"][0]["id"].asString() shouldBe id
            // A list entry has no version: update_interview needs it, and get_interview is where it comes from.
            listed["interviews"][0].has("version") shouldBe false
            val read = client.call("get_interview", mapOf("applicationId" to application, "id" to id))
            read shouldBe logged

            val notes = mapOf("preparationNotes" to "Read", "notes" to "Went well")
            val change = mapOf("outcome" to "PASSED", "interview" to notes)
            val updated = client.call("update_interview", read.asUpdate() + change)

            updated["version"].asInt() shouldBe 1
            updated["outcome"].asString() shouldBe "PASSED"
            updated["interview"].untrusted()["notes"].asString() shouldBe "Went well"
            changelog("interview", id).map { it.second } shouldContainExactly listOf("AI", "AI")
        }
    }

    @Test
    fun `an interview of list_interviews goes back into update_interview unchanged, nulls included`() {
        val application = application()
        owner.mcpClient().use { client ->
            client.initialize()
            val bare = client.call("log_interview", interview(application))
            val full =
                client.call(
                    "log_interview",
                    interview(application, "notes" to "n", "preparationNotes" to "p", "outcome" to "PASSED"),
                )

            listOf(bare, full).forEach { logged ->
                val answer = client.call("update_interview", logged.asUpdate())

                answer shouldBe logged
                changelog("interview", logged["id"].asString()).size shouldBe 1
            }
        }
    }

    @Test
    fun `upcoming interviews are listed soonest first without notes, past and cancelled ones left out`() {
        val application = application(INJECTION)
        owner.mcpClient().use { client ->
            client.initialize()
            val later = client.call("log_interview", interview(application, "localStart" to "2099-03-01T10:00"))
            val sooner =
                client.call("log_interview", interview(application, "notes" to INJECTION, "type" to "HR"))
            client.call("log_interview", interview(application, "localStart" to "2020-01-01T10:00"))
            client.call("log_interview", interview(application, "outcome" to "CANCELLED"))

            val upcoming = client.call("list_upcoming_interviews", mapOf())

            upcoming["interviews"].values().map { it["id"].asString() } shouldContainExactly
                listOf(sooner["id"].asString(), later["id"].asString())
            upcoming["interviews"][0]["application"].untrusted()["title"].asString() shouldBe INJECTION
            upcoming["interviews"][0]["applicationId"].asString() shouldBe application
            upcoming.toString() shouldNotContain "\"notes\""
            upcoming["interviews"][0]["type"].asString() shouldBe "HR"
        }
    }

    @Test
    fun `validation errors name the arguments and store nothing`() {
        val application = application()
        owner.mcpClient().use { client ->
            client.initialize()
            val ok = interview(application)

            client.failure("log_interview", ok + ("timeZone" to "Mars/Base"), INVALID).problems() shouldContainExactly
                listOf("timeZone:invalid-time-zone")
            client
                .failure(
                    "log_interview",
                    ok + ("localStart" to "1999-01-01T10:00"),
                    INVALID,
                ).problems() shouldContainExactly
                listOf("localStart:out-of-range")
            client
                .failure(
                    "log_interview",
                    ok + ("participantIds" to listOf(MISSING)),
                    INVALID,
                ).problems() shouldContainExactly
                listOf("participantIds:not-found")
            val many = (1..21).map { UUID.randomUUID().toString() }
            client.failure("log_interview", ok + ("participantIds" to many), INVALID).problems() shouldContainExactly
                listOf("participantIds:too-many")
            client.failure("log_interview", ok + ("localStart" to "next week"), INVALID).problems() shouldContainExactly
                listOf("localStart:invalid")
            client.failure("log_interview", ok + ("applicationId" to MISSING), "not-found")
            client.call("list_interviews", mapOf("applicationId" to application))["total"].asInt() shouldBe 0
        }
    }

    @Test
    fun `update_interview answers a stale version, an unknown interview and an unknown application`() {
        val application = application()
        owner.mcpClient().use { client ->
            client.initialize()
            val logged = client.call("log_interview", interview(application))
            val id = logged["id"].asString()

            client.failure("update_interview", logged.asUpdate() + ("version" to 7), "version-conflict")
            client.failure("update_interview", logged.asUpdate() + ("id" to MISSING), "not-found")
            client.failure("update_interview", logged.asUpdate() + ("applicationId" to MISSING), "not-found")
            client.failure("update_interview", logged.asUpdate() + ("timeZone" to "Mars/Base"), INVALID)
            client.failure("list_interviews", mapOf("applicationId" to MISSING), "not-found")

            changelog("interview", id).size shouldBe 1
            client
                .call("get_interview", mapOf("applicationId" to application, "id" to id))["version"]
                .asInt() shouldBe 0
        }
    }

    @Test
    fun `arguments that break the schema are refused before any tool runs`() {
        val application = application()
        owner.mcpClient().use { client ->
            client.initialize()
            val ok = interview(application)

            client.refused("log_interview", ok - "applicationId")
            client.refused("log_interview", ok - "type")
            client.refused("log_interview", ok - "localStart")
            client.refused("log_interview", ok - "timeZone")
            client.refused("log_interview", ok + ("type" to "CHAT"))
            client.refused("log_interview", ok + ("unknown" to 1))
            client.refused("log_interview", ok + ("interview" to mapOf("notes" to "x".repeat(100_001))))
            client.refused("log_interview", ok + ("timeZone" to "x".repeat(65)))
            client.refused("log_interview", ok + ("outcome" to "MAYBE"))
            client.refused("log_interview", ok + ("participantIds" to (1..41).map { MISSING }))
            client.refused("update_interview", ok + ("id" to MISSING))
            client.refused("update_interview", ok + mapOf("id" to MISSING, "version" to -1))
            client.refused("list_interviews", mapOf())
            client.refused("list_interviews", mapOf("applicationId" to application, "size" to 51))
            client.refused("list_interviews", mapOf("applicationId" to application, "size" to 0))
            client.refused("list_interviews", mapOf("applicationId" to application, "page" to -1))
            client.refused("list_interviews", mapOf("applicationId" to application, "direction" to "SIDEWAYS"))
            client.refused("list_interviews", mapOf("applicationId" to application, "unknown" to 1))
            client.refused("get_interview", mapOf("applicationId" to application))
            client.refused("get_interview", mapOf("id" to MISSING))
            client.refused("list_upcoming_interviews", mapOf("size" to 5))
            client.call("list_interviews", mapOf("applicationId" to application))["total"].asInt() shouldBe 0
        }
    }

    @Test
    fun `instruction-like notes come back only inside the untrusted mark`() {
        val application = application()
        owner.mcpClient().use { client ->
            client.initialize()
            val logged =
                client.call(
                    "log_interview",
                    interview(application, "notes" to INJECTION, "preparationNotes" to INJECTION),
                )

            val results =
                listOf(
                    logged,
                    client.call(
                        "get_interview",
                        mapOf("applicationId" to application, "id" to logged["id"].asString()),
                    ),
                    client.call("update_interview", logged.asUpdate()),
                )

            results.forEach { result ->
                result["interview"].untrusted()["notes"].asString() shouldBe INJECTION
                // Both notes appear once each, inside the mark, never as plain fields.
                result.toString().split(INJECTION).size shouldBe 3
            }
            val entry = client.call("list_interviews", mapOf("applicationId" to application))["interviews"][0]
            entry["interview"].untrusted()["notesExcerpt"].asString() shouldBe INJECTION
            entry["interview"].untrusted()["preparationNotesExcerpt"].asString() shouldBe INJECTION
            entry.toString().split(INJECTION).size shouldBe 3
        }
    }

    @Test
    fun `flagged values are withheld from results and errors, and a withheld value sent back is refused`() {
        val application = application()
        owner.mcpClient().use { client ->
            client.initialize()
            val logged = client.call("log_interview", interview(application, "notes" to "Call $FLAGGED_PHONE"))
            val id = logged["id"].asString()
            logged["interview"].untrusted()["notes"].asString() shouldBe "Call [withheld]"

            client.failure("update_interview", logged.asUpdate(), INVALID).problems() shouldContainExactly
                listOf("interview.notes:withheld-value")
            val rejected = client.failure("log_interview", interview(application, "timeZone" to FLAGGED_PHONE), INVALID)

            rejected.toString() shouldNotContain "1234567"
            client.call("list_interviews", mapOf("applicationId" to application)).toString() shouldNotContain "1234567"
            client.call("list_upcoming_interviews", mapOf()).toString() shouldNotContain "1234567"
            client
                .call("get_interview", mapOf("applicationId" to application, "id" to id))
                .toString() shouldNotContain "1234567"
            changelog("interview", id).size shouldBe 1
        }
    }

    @Test
    fun `without a session no interview tool can be called`() {
        val anonymous = Session().open()

        listOf("log_interview", "update_interview", "list_interviews", "get_interview", "list_upcoming_interviews")
            .forEach { tool ->
                val call = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":{}}}"""
                anonymous.send("POST", "/mcp", call).statusCode() shouldBe 401
            }
    }
}
