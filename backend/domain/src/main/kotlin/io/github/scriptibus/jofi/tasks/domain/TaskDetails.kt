// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import io.github.scriptibus.jofi.shared.domain.text.textProblem
import java.time.Instant
import java.util.UUID

/**
 * What a task is about, if anything (spec §10.2): one application, company or contact, by the id its own context gave
 * it. Tasks refer to other contexts' aggregates by id only, through reference types of their own (ADR-0041), so the
 * tasks domain depends on no other context. The foreign keys keep a reference valid; when its target is deleted the
 * link is cleared and the task stays (`ON DELETE SET NULL`, ADR-0049).
 */
sealed interface TaskLink {
    val value: UUID
}

/** An application a task is about. */
@JvmInline
value class ApplicationRef(
    override val value: UUID,
) : TaskLink

/** A company a task is about. */
@JvmInline
value class CompanyRef(
    override val value: UUID,
) : TaskLink

/** A contact person a task is about. */
@JvmInline
value class ContactRef(
    override val value: UUID,
) : TaskLink

/**
 * What the user (or the AI, or a suggestion rule) wants done (spec §10.2): a [title], its [timing], an optional
 * [link] and [notes] (Markdown, the user's words, which may describe other people). [toString] shows neither title
 * nor notes.
 */
data class TaskDetails(
    val title: String,
    val timing: TaskTiming,
    val link: TaskLink? = null,
    val notes: String? = null,
) {
    init {
        require(textProblem(title, MAX_TITLE_LENGTH) == null) { "A task title breaks an invariant" }
        require(notes == null || textProblem(notes, MAX_NOTES_LENGTH) == null) { "Task notes break an invariant" }
    }

    override fun toString(): String = "TaskDetails(timing=$timing, link=${link?.let { it::class.simpleName }})"

    companion object {
        /** As an application title. */
        const val MAX_TITLE_LENGTH = 300
        const val MAX_NOTES_LENGTH = 10_000
    }
}

/**
 * A task as entered by the user, the AI or an external client. [validate] normalizes text like every text (NFC,
 * trimmed, blank optional text is absent), resolves the [timing] with the current instant, and reports what is still
 * wrong, every violation at once. Whether the [link] exists is the store's check (`task_*_fk`). [toString] shows
 * neither title nor notes.
 */
data class TaskInput(
    val title: String,
    val timing: TaskTimingInput,
    val link: TaskLink? = null,
    val notes: String? = null,
) {
    fun validate(now: Instant): TaskValidation<TaskDetails> {
        val checks = TaskChecks()
        val title = checks.text(TaskField.TITLE, title, TaskDetails.MAX_TITLE_LENGTH, required = true)
        val timing = timing.validate(now, checks)
        val notes = checks.text(TaskField.NOTES, notes, TaskDetails.MAX_NOTES_LENGTH)
        if (title == null || timing == null || checks.count > 0) return TaskValidation.Invalid(checks.violations)
        return TaskValidation.Valid(TaskDetails(title, timing, link, notes))
    }

    override fun toString(): String = "TaskInput(timing=$timing, link=${link?.let { it::class.simpleName }})"
}
