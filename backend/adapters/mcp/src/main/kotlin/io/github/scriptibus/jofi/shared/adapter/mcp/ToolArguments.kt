// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.github.scriptibus.jofi.shared.domain.ai.NeverSendFilter
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * The arguments of one tool call as the client sent them (decoded JSON). Each getter returns null (or an
 * empty collection) for a missing argument and throws [InvalidToolArgument] for one of the wrong shape,
 * which the server answers as an `invalid-arguments` error. Domain validation stays in the domain's `*Input`.
 */
@Suppress("TooManyFunctions") // One getter per JSON shape a tool argument can have.
class ToolArguments(
    private val values: Map<String, Any?>,
) {
    fun text(name: String): String? = values[name]?.let { it as? String ?: invalid(name) }

    fun texts(name: String): List<String> = list(name).map { it as? String ?: invalid(name) }

    fun bool(name: String): Boolean? = values[name]?.let { it as? Boolean ?: invalid(name) }

    fun int(name: String): Int? =
        values[name]?.let { value ->
            when (value) {
                is Int -> value
                is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else invalid(name)
                else -> invalid(name)
            }
        }

    fun long(name: String): Long? =
        values[name]?.let { value ->
            when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> invalid(name)
            }
        }

    /** An amount as the client wrote it: a JSON number, read exactly (a double through its shortest text). */
    fun decimal(name: String): BigDecimal? =
        values[name]?.let { value ->
            when (value) {
                is Int, is Long, is Double, is BigDecimal -> BigDecimal(value.toString())
                else -> invalid(name)
            }
        }

    /** A list of JSON objects, each read through its own [ToolArguments]. */
    fun objects(name: String): List<ToolArguments> =
        list(name).map { item ->
            val fields = item as? Map<*, *> ?: invalid(name)
            ToolArguments(fields.entries.associate { (key, value) -> key.toString() to value })
        }

    /** One JSON object, read through its own [ToolArguments]; null if the argument is missing or `null`. */
    fun obj(name: String): ToolArguments? =
        values[name]?.let { item ->
            val fields = item as? Map<*, *> ?: invalid(name)
            ToolArguments(fields.entries.associate { (key, value) -> key.toString() to value })
        }

    fun uuid(name: String): UUID? =
        text(name)?.let { text ->
            try {
                UUID.fromString(text)
            } catch (_: IllegalArgumentException) {
                invalid(name)
            }
        }

    /**
     * The first argument that holds the redaction marker of the "never send to AI" filter, named by its path (such
     * as `notes.offer.benefits` or `channels[0].value`), or null. Results show `[withheld]` in place of flagged
     * values, so an update that sends a result back would store the marker over the real value, and a create call
     * that copies one would store the marker as text; tools refuse such input and name the value to leave alone.
     */
    fun withheldPath(): String? = values.entries.firstNotNullOfOrNull { (key, value) -> markerPath(key, value) }

    private fun markerPath(
        path: String,
        value: Any?,
    ): String? =
        when (value) {
            is String -> path.takeIf { NeverSendFilter.REDACTION in value }
            is Map<*, *> -> value.entries.firstNotNullOfOrNull { (key, item) -> markerPath("$path.$key", item) }
            is List<*> -> value.withIndex().firstNotNullOfOrNull { (index, item) -> markerPath("$path[$index]", item) }
            else -> null
        }

    fun uuids(name: String): Set<UUID> =
        texts(name)
            .map { text ->
                try {
                    UUID.fromString(text)
                } catch (_: IllegalArgumentException) {
                    invalid(name)
                }
            }.toSet()

    fun instant(name: String): Instant? =
        text(name)?.let { text ->
            try {
                Instant.parse(text)
            } catch (_: DateTimeParseException) {
                invalid(name)
            }
        }

    /** A calendar date such as `2026-10-05`. */
    fun date(name: String): LocalDate? =
        text(name)?.let { text ->
            try {
                LocalDate.parse(text)
            } catch (_: DateTimeParseException) {
                invalid(name)
            }
        }

    /** A date and time without a zone, such as `2026-10-05T10:00`. */
    fun localDateTime(name: String): LocalDateTime? =
        text(name)?.let { text ->
            try {
                LocalDateTime.parse(text)
            } catch (_: DateTimeParseException) {
                invalid(name)
            }
        }

    fun <E : Enum<E>> enum(
        name: String,
        type: Class<E>,
    ): E? = text(name)?.let { constant(name, it, type) }

    fun <E : Enum<E>> enums(
        name: String,
        type: Class<E>,
    ): Set<E> = texts(name).map { constant(name, it, type) }.toSet()

    private fun <E : Enum<E>> constant(
        name: String,
        text: String,
        type: Class<E>,
    ): E = type.enumConstants.firstOrNull { it.name == text } ?: invalid(name)

    private fun list(name: String): List<Any?> =
        when (val value = values[name]) {
            null -> emptyList()
            is List<*> -> value
            else -> invalid(name)
        }

    private fun invalid(name: String): Nothing = throw InvalidToolArgument(name)
}

/** An argument of the wrong shape; carries only the argument's name, never its value. */
class InvalidToolArgument(
    val argument: String,
) : RuntimeException("Invalid tool argument: $argument")
