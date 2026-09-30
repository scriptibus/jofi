// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
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
            )
    }

    @Test
    fun `every field has its own request field name`() {
        ApplicationField.entries.map(ApplicationProblems::apiName).shouldBeUnique()
    }

    @Test
    fun `each other failure has its status and problem type`() {
        expect(ApplicationResult.NotFound, HttpStatus.NOT_FOUND, ApplicationProblems.NOT_FOUND)
        expect(ApplicationResult.VersionConflict, HttpStatus.CONFLICT, ApplicationProblems.VERSION_CONFLICT)
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
    fun `search parameters out of range name the parameter`() {
        val problem = ApplicationProblems.invalidSearch(page = 0, size = 0)

        problem.body.type shouldBe URI.create(ApplicationProblems.INVALID_SEARCH)
        problem.body.shouldBeInstanceOf<ValidationProblem>().violations shouldBe
            listOf(FieldViolation("size", "OUT_OF_RANGE"))
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
