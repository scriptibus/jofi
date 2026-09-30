// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.domain.CompanyField
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanyViolation
import io.github.scriptibus.jofi.companies.domain.ViolationKind
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRejection
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.HttpStatus
import java.net.URI

class CompanyProblemsTest {
    @Test
    fun `invalid input is a 400 with every violation under the request field name`() {
        val failure =
            CompanyResult.Invalid(
                listOf(
                    CompanyViolation(CompanyField.NAME, ViolationKind.REQUIRED),
                    CompanyViolation(CompanyField.CAREERS_PAGE, ViolationKind.INVALID_URL),
                ),
            )

        val problem = CompanyProblems.of(failure)

        problem.statusCode shouldBe HttpStatus.BAD_REQUEST
        problem.body.type shouldBe URI.create(CompanyProblems.INVALID)
        problem.body.shouldBeInstanceOf<ValidationProblem>().violations shouldBe
            listOf(FieldViolation("name", "REQUIRED"), FieldViolation("careersPage", "INVALID_URL"))
    }

    @Test
    fun `each other failure has its status and problem type`() {
        expect(CompanyResult.NotFound, HttpStatus.NOT_FOUND, CompanyProblems.NOT_FOUND)
        expect(CompanyResult.VersionConflict, HttpStatus.CONFLICT, CompanyProblems.VERSION_CONFLICT)
        expect(CompanyResult.HasApplications, HttpStatus.CONFLICT, CompanyProblems.HAS_APPLICATIONS)
        expect(
            CompanyResult.StorageFailure("find-company"),
            HttpStatus.SERVICE_UNAVAILABLE,
            CompanyProblems.UNAVAILABLE,
        )
        expect(
            CompanyResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.EXPIRED)),
            HttpStatus.PRECONDITION_FAILED,
            Confirmations.INVALID,
        )
    }

    @Test
    fun `search parameters out of range name the parameter`() {
        val problem = CompanyProblems.invalidSearch(page = -1, size = 0)

        problem.body.type shouldBe URI.create(CompanyProblems.INVALID_SEARCH)
        problem.body.shouldBeInstanceOf<ValidationProblem>().violations shouldBe
            listOf(FieldViolation("page", "OUT_OF_RANGE"), FieldViolation("size", "OUT_OF_RANGE"))
    }

    @ParameterizedTest
    @EnumSource(CompanyField::class)
    fun `every domain field has a distinct request field name`(field: CompanyField) {
        val names = CompanyField.entries.map(CompanyProblems::apiName)

        names.count { it == CompanyProblems.apiName(field) } shouldBe 1
    }

    private fun expect(
        failure: CompanyResult.Failure,
        status: HttpStatus,
        type: String,
    ) {
        val problem = CompanyProblems.of(failure)
        problem.statusCode shouldBe status
        problem.body.type shouldBe URI.create(type)
    }
}
