// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class ToolArgumentsTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val arguments =
        ToolArguments(
            mapOf(
                "text" to "Kotlin",
                "id" to id.toString(),
                "flag" to true,
                "count" to 3,
                "big" to 3L,
                "at" to "2026-09-30T08:00:00Z",
                "unit" to "DAYS",
                "units" to listOf("DAYS", "HOURS", "DAYS"),
            ),
        )

    @Test
    fun `arguments are read in their JSON shapes`() {
        arguments.text("text") shouldBe "Kotlin"
        arguments.uuid("id") shouldBe id
        arguments.bool("flag") shouldBe true
        arguments.int("count") shouldBe 3
        arguments.int("big") shouldBe 3
        arguments.instant("at") shouldBe Instant.parse("2026-09-30T08:00:00Z")
        arguments.enum("unit", ChronoUnit::class.java) shouldBe ChronoUnit.DAYS
        arguments.enums("units", ChronoUnit::class.java) shouldBe setOf(ChronoUnit.DAYS, ChronoUnit.HOURS)
    }

    @Test
    fun `missing arguments are null or empty`() {
        val none = ToolArguments(emptyMap())

        none.text("text").shouldBeNull()
        none.uuid("id").shouldBeNull()
        none.int("count").shouldBeNull()
        none.texts("languages").shouldBeEmpty()
        none.enums("units", ChronoUnit::class.java).shouldBeEmpty()
    }

    @Test
    fun `an argument of the wrong shape names the argument, never its value`() {
        val wrong =
            ToolArguments(
                mapOf(
                    "id" to "not-a-uuid",
                    "count" to 1.5,
                    "huge" to Long.MAX_VALUE,
                    "at" to "yesterday",
                    "unit" to "FORTNIGHTS",
                    "units" to "DAYS",
                    "text" to 7,
                ),
            )

        listOf<() -> Any?>(
            { wrong.uuid("id") },
            { wrong.int("count") },
            { wrong.int("huge") },
            { wrong.instant("at") },
            { wrong.enum("unit", ChronoUnit::class.java) },
            { wrong.texts("units") },
            { wrong.text("text") },
            { wrong.bool("text") },
        ).forEach { read ->
            val error = shouldThrow<InvalidToolArgument> { read() }
            error.message shouldBe "Invalid tool argument: ${error.argument}"
        }
    }

    @Test
    fun `longs, uuid sets and objects are read in their JSON shapes`() {
        val values =
            ToolArguments(
                mapOf(
                    "version" to 7,
                    "wide" to 9_000_000_000L,
                    "ids" to listOf(id.toString(), id.toString()),
                    "items" to listOf(mapOf("kind" to "EMAIL")),
                ),
            )

        values.long("version") shouldBe 7L
        values.long("wide") shouldBe 9_000_000_000L
        values.long("missing").shouldBeNull()
        values.uuids("ids") shouldBe setOf(id)
        values.objects("items").single().text("kind") shouldBe "EMAIL"
        values.objects("missing").shouldBeEmpty()
    }

    @Test
    fun `a long, uuid set or object list of the wrong shape names the argument`() {
        val wrong =
            ToolArguments(mapOf("version" to "7", "ids" to listOf("nope"), "items" to listOf("x"), "other" to 1))

        listOf<() -> Any?>(
            { wrong.long("version") },
            { wrong.uuids("ids") },
            { wrong.objects("items") },
            { wrong.objects("other") },
        ).forEach { read -> shouldThrow<InvalidToolArgument> { read() } }
    }
}
