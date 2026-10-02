// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

/** Names for [ArgumentProblem]s, so every context reports a domain violation the same way. */
object ToolProblems {
    /** `CREATED_TO` becomes `createdTo`, the argument's name in the tool's schema. */
    fun argumentName(field: String): String =
        field
            .lowercase()
            .split('_')
            .mapIndexed { index, word -> if (index == 0) word else word.replaceFirstChar(Char::uppercaseChar) }
            .joinToString("")

    /** `TOO_LONG` becomes `too-long`. */
    fun problemCode(problem: String): String = problem.lowercase().replace('_', '-')

    /** The refusal of input that carries the redaction marker, see [ToolArguments.withheldPath]. */
    fun withheldValue(argument: String): ToolAnswer.Error =
        ToolAnswer.Error(
            "invalid-arguments",
            "A value shows [withheld]: it is hidden from you and cannot be stored or sent back. " +
                "Nothing was changed.",
            listOf(ArgumentProblem(argument, "withheld-value")),
        )

    /** The shared failure for a write based on an older version of the entity. */
    fun versionConflict(): ToolAnswer.Error =
        ToolAnswer.Error(
            "version-conflict",
            "The entity changed since it was read. Read it again and retry with its current version.",
        )
}
