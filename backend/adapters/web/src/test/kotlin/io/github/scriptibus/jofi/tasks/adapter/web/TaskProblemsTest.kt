// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import io.github.scriptibus.jofi.tasks.domain.TaskField
import io.github.scriptibus.jofi.tasks.domain.TaskProblem
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskViolation
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.net.URI

class TaskProblemsTest {
    @Test
    fun `violations name the request fields`() {
        val failure =
            TaskResult.Invalid(
                listOf(
                    TaskViolation(TaskField.TITLE, TaskProblem.REQUIRED),
                    TaskViolation(TaskField.TIMING, TaskProblem.AMBIGUOUS),
                    TaskViolation(TaskField.DUE, TaskProblem.OUT_OF_RANGE),
                    TaskViolation(TaskField.TIME_ZONE, TaskProblem.INVALID_TIME_ZONE),
                    TaskViolation(TaskField.LINK, TaskProblem.NOT_FOUND),
                    TaskViolation(TaskField.NOTES, TaskProblem.TOO_LONG),
                    TaskViolation(TaskField.TARGET_DATE, TaskProblem.OUT_OF_RANGE),
                ),
            )

        val problem = TaskProblems.of(failure)

        problem.statusCode shouldBe HttpStatus.BAD_REQUEST
        problem.body.type shouldBe URI.create(TaskProblems.INVALID)
        problem.body.shouldBeInstanceOf<ValidationProblem>().violations shouldBe
            listOf(
                FieldViolation("title", "REQUIRED"),
                FieldViolation("timing", "AMBIGUOUS"),
                FieldViolation("timing.localDue", "OUT_OF_RANGE"),
                FieldViolation("timing.timeZone", "INVALID_TIME_ZONE"),
                FieldViolation("link.id", "NOT_FOUND"),
                FieldViolation("notes", "TOO_LONG"),
                FieldViolation("targetDate", "OUT_OF_RANGE"),
            )
    }

    @Test
    fun `every field has its own request field name`() {
        TaskField.entries.map(TaskProblems::apiName).shouldBeUnique()
    }

    @Test
    fun `each failure has its status and type`() {
        expect(TaskResult.NotFound, HttpStatus.NOT_FOUND, TaskProblems.NOT_FOUND)
        expect(TaskResult.CountdownNotFound, HttpStatus.NOT_FOUND, TaskProblems.COUNTDOWN_NOT_FOUND)
        expect(TaskResult.VersionConflict, HttpStatus.CONFLICT, TaskProblems.VERSION_CONFLICT)
        expect(
            TaskResult.InvalidTransition(TaskState.DISMISSED, TaskState.OPEN),
            HttpStatus.CONFLICT,
            TaskProblems.INVALID_TRANSITION,
        )
        expect(TaskResult.StorageFailure("add task"), HttpStatus.SERVICE_UNAVAILABLE, TaskProblems.UNAVAILABLE)
    }

    private fun expect(
        failure: TaskResult.Failure,
        status: HttpStatus,
        type: String,
    ) {
        val problem = TaskProblems.of(failure)
        problem.statusCode shouldBe status
        problem.body.type shouldBe URI.create(type)
    }
}
