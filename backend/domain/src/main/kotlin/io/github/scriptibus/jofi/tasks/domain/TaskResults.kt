// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.text.TextProblem
import io.github.scriptibus.jofi.shared.domain.text.normalizedText
import io.github.scriptibus.jofi.shared.domain.text.textProblem

/** A problem with one field of a [TaskInput] or [CountdownInput], named so that clients can show it there. */
data class TaskViolation(
    val field: TaskField,
    val problem: TaskProblem,
)

enum class TaskField {
    /** A task's or a countdown's title. */
    TITLE,
    NOTES,

    /** The timing as a whole: neither or both of a due time and a bucket, or a bucket out of range. */
    TIMING,

    /** A task's exact due time: within `TaskTiming.EARLIEST` and `TaskTiming.LATEST`. */
    DUE,
    TIME_ZONE,

    /** The linked application, company or contact: it does not exist. */
    LINK,

    /** A countdown's target date: within `TaskTiming.EARLIEST_DAY` and `TaskTiming.LATEST_DAY`. */
    TARGET_DATE,
}

enum class TaskProblem {
    /** The field is required but empty. */
    REQUIRED,

    /** The text is longer than allowed. */
    TOO_LONG,

    /** The text contains U+0000, which the database cannot store. */
    INVALID_CHARACTER,

    /** The date or time is outside its range. */
    OUT_OF_RANGE,

    /** Not a time zone Java knows: an IANA id such as `Europe/Berlin`, or an offset such as `+02:00`. */
    INVALID_TIME_ZONE,

    /** Both an exact due time and a bucket: a task has one timing. */
    AMBIGUOUS,

    /** The referenced entity (an application, a company, a contact) does not exist. */
    NOT_FOUND,
}

/** Outcome of validating a raw input: the normalized value, or every violation at once. */
sealed interface TaskValidation<out T> {
    data class Valid<out T>(
        val value: T,
    ) : TaskValidation<T>

    data class Invalid(
        val violations: List<TaskViolation>,
    ) : TaskValidation<Nothing> {
        init {
            require(violations.isNotEmpty()) { "An invalid input names at least one violation" }
        }
    }
}

/**
 * Outcome of a task or countdown use case (#93, #94, #95, #112). Callers map every case: the REST controller to a
 * status and problem type, an MCP tool to a tool error.
 */
sealed interface TaskResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : TaskResult<T>

    /** Every outcome but [Success]: nothing was changed. */
    sealed interface Failure : TaskResult<Nothing>

    /** The input breaks the rules, or links to something that does not exist ([TaskField.LINK], `NOT_FOUND`). */
    data class Invalid(
        val violations: List<TaskViolation>,
    ) : Failure

    /** No task with the requested id. */
    data object NotFound : Failure

    /** No countdown with the requested id. */
    data object CountdownNotFound : Failure

    /** The change was based on an older version of the task or countdown; nothing was changed. */
    data object VersionConflict : Failure

    /** A task cannot move [from] its state [to] the requested one (see [TaskState]). */
    data class InvalidTransition(
        val from: TaskState,
        val to: TaskState,
    ) : Failure

    /** The delete needs (another) confirmation step (ADR-0039); nothing was deleted. */
    data class Unconfirmed(
        val outcome: ConfirmationResult.Unconfirmed,
    ) : Failure

    /** The store could not complete [operation]; nothing was changed. */
    data class StorageFailure(
        val operation: String,
    ) : Failure
}

/** Outcome of a task or countdown repository call; storage failures are values, not exceptions. */
sealed interface TaskStoreResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : TaskStoreResult<T>

    /** No task or countdown with the requested id. */
    data object NotFound : TaskStoreResult<Nothing>

    /** The stored version is newer than the change was based on; reload and retry. */
    data object VersionConflict : TaskStoreResult<Nothing>

    /**
     * The linked application, company or contact does not exist (any more): `task_application_fk`, `task_company_fk`
     * or `task_contact_fk` rejected it.
     */
    data object LinkNotFound : TaskStoreResult<Nothing>

    /** A task with the same suggestion (rule and key) exists already: `task_suggestion_unique`; nothing was added. */
    data object SuggestionExists : TaskStoreResult<Nothing>

    /** The confirmation proof does not cover deleting this task or countdown; nothing was deleted. */
    data object NotConfirmed : TaskStoreResult<Nothing>

    /** The store could not complete [operation]. Carries no row data, so it is safe to log. */
    data class StorageFailure(
        val operation: String,
    ) : TaskStoreResult<Nothing>
}

/** Collects violations while an input is validated. */
internal class TaskChecks {
    private val found = mutableListOf<TaskViolation>()

    val violations: List<TaskViolation> get() = found.toList()
    val count: Int get() = found.size

    fun report(
        field: TaskField,
        problem: TaskProblem?,
    ) {
        if (problem != null) found += TaskViolation(field, problem)
    }

    /** The normalized, trimmed text; `null` if it is blank (absent, or reported when [required]) or broken. */
    fun text(
        field: TaskField,
        raw: String?,
        maxLength: Int,
        required: Boolean = false,
    ): String? {
        val text = raw?.normalizedText()?.trim()?.takeIf(String::isNotEmpty)
        if (text == null) {
            if (required) report(field, TaskProblem.REQUIRED)
            return null
        }
        val problem = problemOf(text, maxLength)
        report(field, problem)
        return text.takeIf { problem == null }
    }

    private fun problemOf(
        text: String,
        maxLength: Int,
    ): TaskProblem? =
        when (textProblem(text, maxLength)) {
            TextProblem.BLANK_OR_UNTRIMMED -> TaskProblem.REQUIRED
            TextProblem.UNSTORABLE_CHARACTER -> TaskProblem.INVALID_CHARACTER
            TextProblem.TOO_LONG -> TaskProblem.TOO_LONG
            null -> null
        }
}
