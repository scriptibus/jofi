// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.domain.ContactField
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.companies.domain.ContactViolation
import io.github.scriptibus.jofi.companies.domain.ViolationKind
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRejection
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.net.URI

class ContactProblemsTest {
    @Test
    fun `invalid input is a 400 with every violation under the request field name`() {
        val failure =
            ContactResult.Invalid(
                listOf(
                    ContactViolation(ContactField.NAME, ViolationKind.REQUIRED),
                    ContactViolation(ContactField.ROLE, ViolationKind.TOO_LONG),
                    ContactViolation(ContactField.COMPANY, ViolationKind.NOT_FOUND),
                    ContactViolation(ContactField.CHANNELS, ViolationKind.TOO_MANY),
                    ContactViolation(ContactField.CHANNEL_VALUE, ViolationKind.INVALID_EMAIL, 2),
                    ContactViolation(ContactField.CHANNEL_LABEL, ViolationKind.TOO_LONG, 0),
                    ContactViolation(ContactField.RELATIONSHIP_NOTES, ViolationKind.TOO_LONG),
                ),
            )

        val problem = ContactProblems.of(failure)

        problem.statusCode shouldBe HttpStatus.BAD_REQUEST
        problem.body.type shouldBe URI.create(ContactProblems.INVALID)
        problem.body.shouldBeInstanceOf<ValidationProblem>().violations shouldBe
            listOf(
                FieldViolation("name", "REQUIRED"),
                FieldViolation("role", "TOO_LONG"),
                FieldViolation("companyId", "NOT_FOUND"),
                FieldViolation("channels", "TOO_MANY"),
                FieldViolation("channels[2].value", "INVALID_EMAIL"),
                FieldViolation("channels[0].label", "TOO_LONG"),
                FieldViolation("relationshipNotes", "TOO_LONG"),
            )
    }

    @Test
    fun `each other failure has its status and problem type`() {
        expect(ContactResult.NotFound, HttpStatus.NOT_FOUND, ContactProblems.NOT_FOUND)
        expect(ContactResult.VersionConflict, HttpStatus.CONFLICT, ContactProblems.VERSION_CONFLICT)
        expect(
            ContactResult.StorageFailure("find-contact"),
            HttpStatus.SERVICE_UNAVAILABLE,
            CompanyProblems.UNAVAILABLE,
        )
        expect(
            ContactResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH)),
            HttpStatus.PRECONDITION_FAILED,
            Confirmations.INVALID,
        )
    }

    @Test
    fun `search parameters out of range name the parameter`() {
        val problem = ContactProblems.invalidSearch(page = 0, size = 0)

        problem.body.type shouldBe URI.create(ContactProblems.INVALID_SEARCH)
        problem.body.shouldBeInstanceOf<ValidationProblem>().violations shouldBe
            listOf(FieldViolation("size", "OUT_OF_RANGE"))
    }

    private fun expect(
        failure: ContactResult.Failure,
        status: HttpStatus,
        type: String,
    ) {
        val problem = ContactProblems.of(failure)
        problem.statusCode shouldBe status
        problem.body.type shouldBe URI.create(type)
    }
}
