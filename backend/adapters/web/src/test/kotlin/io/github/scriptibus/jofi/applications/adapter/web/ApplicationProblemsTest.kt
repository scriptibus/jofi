// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.SearchField
import io.github.scriptibus.jofi.applications.domain.SearchViolation
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRejection
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.net.URI

class ApplicationProblemsTest {
    @Test
    fun `invalid input is a 400 with every violation under the request field name`() {
        val failure =
            ApplicationResult.Invalid(
                listOf(
                    ApplicationViolation(ApplicationField.TITLE, ApplicationProblem.REQUIRED),
                    ApplicationViolation(ApplicationField.COMPANY, ApplicationProblem.NOT_FOUND),
                    ApplicationViolation(ApplicationField.PAY_MAX, ApplicationProblem.MIN_ABOVE_MAX),
                    ApplicationViolation(ApplicationField.OFFER_SALARY_CURRENCY, ApplicationProblem.INVALID_CURRENCY),
                    ApplicationViolation(ApplicationField.CONTACTS, ApplicationProblem.TOO_MANY),
                    ApplicationViolation(ApplicationField.SOURCE_URL, ApplicationProblem.INVALID_URL),
                    ApplicationViolation(ApplicationField.DESCRIPTION, ApplicationProblem.TOO_LONG),
                ),
            )

        val problem = ApplicationProblems.of(failure)

        problem.statusCode shouldBe HttpStatus.BAD_REQUEST
        problem.body.type shouldBe URI.create(ApplicationProblems.INVALID)
        problem.body.shouldBeInstanceOf<ValidationProblem>().violations shouldBe
            listOf(
                FieldViolation("title", "REQUIRED"),
                FieldViolation("companyId", "NOT_FOUND"),
                FieldViolation("payBand.max", "MIN_ABOVE_MAX"),
                FieldViolation("offer.salary.currency", "INVALID_CURRENCY"),
                FieldViolation("contactIds", "TOO_MANY"),
                FieldViolation("originalUrl", "INVALID_URL"),
                FieldViolation("description", "TOO_LONG"),
            )
    }

    @Test
    fun `interview violations name the interview request fields`() {
        val failure =
            ApplicationResult.Invalid(
                listOf(
                    ApplicationViolation(ApplicationField.INTERVIEW_START, ApplicationProblem.OUT_OF_RANGE),
                    ApplicationViolation(ApplicationField.TIME_ZONE, ApplicationProblem.INVALID_TIME_ZONE),
                    ApplicationViolation(ApplicationField.PARTICIPANTS, ApplicationProblem.NOT_FOUND),
                    ApplicationViolation(ApplicationField.PREPARATION_NOTES, ApplicationProblem.TOO_LONG),
                    ApplicationViolation(ApplicationField.INTERVIEW_NOTES, ApplicationProblem.INVALID_CHARACTER),
                ),
            )

        ApplicationProblems
            .of(failure)
            .body
            .shouldBeInstanceOf<ValidationProblem>()
            .violations shouldBe
            listOf(
                FieldViolation("localStart", "OUT_OF_RANGE"),
                FieldViolation("timeZone", "INVALID_TIME_ZONE"),
                FieldViolation("participantIds", "NOT_FOUND"),
                FieldViolation("preparationNotes", "TOO_LONG"),
                FieldViolation("notes", "INVALID_CHARACTER"),
            )
    }

    @Test
    fun `every field has its own request field name`() {
        ApplicationField.entries.map(ApplicationProblems::apiName).shouldBeUnique()
    }

    @Test
    fun `each other failure has its status and problem type`() {
        expect(ApplicationResult.NotFound, HttpStatus.NOT_FOUND, ApplicationProblems.NOT_FOUND)
        expect(ApplicationResult.SourceNotFound, HttpStatus.NOT_FOUND, ApplicationProblems.SOURCE_NOT_FOUND)
        expect(ApplicationResult.SnapshotNotFound, HttpStatus.NOT_FOUND, ApplicationProblems.SNAPSHOT_NOT_FOUND)
        expect(ApplicationResult.InterviewNotFound, HttpStatus.NOT_FOUND, ApplicationProblems.INTERVIEW_NOT_FOUND)
        expect(ApplicationResult.VersionConflict, HttpStatus.CONFLICT, ApplicationProblems.VERSION_CONFLICT)
        expect(ApplicationResult.ImportNotFound, HttpStatus.NOT_FOUND, ApplicationProblems.IMPORT_NOT_FOUND)
        expect(ApplicationResult.ImportNotRetryable, HttpStatus.CONFLICT, ApplicationProblems.IMPORT_NOT_RETRYABLE)
        expect(ApplicationResult.AiNotConfigured, HttpStatus.CONFLICT, ApplicationProblems.AI_NOT_CONFIGURED)
        expect(
            ApplicationResult.InvalidTransition(ApplicationStatus.DISCOVERED, ApplicationStatus.ACCEPTED),
            HttpStatus.CONFLICT,
            ApplicationProblems.INVALID_TRANSITION,
        )
        expect(
            ApplicationResult.StorageFailure("find-application"),
            HttpStatus.SERVICE_UNAVAILABLE,
            ApplicationProblems.UNAVAILABLE,
        )
        expect(
            ApplicationResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH)),
            HttpStatus.PRECONDITION_FAILED,
            Confirmations.INVALID,
        )
    }

    @Test
    fun `search violations name every query parameter`() {
        val violations = SearchField.entries.map { SearchViolation(it, ApplicationProblem.OUT_OF_RANGE) }

        val problem = ApplicationProblems.invalidSearch(violations)

        problem.body.type shouldBe URI.create(ApplicationProblems.INVALID_SEARCH)
        problem.body
            .shouldBeInstanceOf<ValidationProblem>()
            .violations
            .map { it.field } shouldBe
            listOf(
                "search",
                "language",
                "createdTo",
                "updatedTo",
                "wantMin",
                "wantMax",
                "fitMin",
                "fitMax",
                "page",
                "size",
            )
    }

    private fun expect(
        failure: ApplicationResult.Failure,
        status: HttpStatus,
        type: String,
    ) {
        val problem = ApplicationProblems.of(failure)
        problem.statusCode shouldBe status
        problem.body.type shouldBe URI.create(type)
    }
}
