// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import java.time.Instant
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

    /** A list of JSON objects, each read through its own [ToolArguments]. */
    fun objects(name: String): List<ToolArguments> =
        list(name).map { item ->
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
