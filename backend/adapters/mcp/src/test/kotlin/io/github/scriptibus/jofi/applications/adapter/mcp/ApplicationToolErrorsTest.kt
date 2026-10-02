// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ApplicationToolErrorsTest {
    @Test
    fun `the failures the tools can meet have their own codes and carry no stored content`() {
        val codes =
            mapOf(
                ApplicationResult.NotFound to "not-found",
                ApplicationResult.InterviewNotFound to "not-found",
                ApplicationResult.VersionConflict to "version-conflict",
                ApplicationResult.StorageFailure("secret operation") to "unavailable",
                ApplicationResult.ImportNotFound to "not-found",
                ApplicationResult.AiNotConfigured to "ai-not-configured",
                ApplicationResult.ImportInProgress to "import-in-progress",
                ApplicationResult.ImportBusy to "import-busy",
                ApplicationResult.InvalidTransition(ApplicationStatus.APPLIED, ApplicationStatus.DISCOVERED) to
                    "invalid-transition",
            )

        codes.forEach { (failure, code) -> ApplicationToolErrors.failure(failure).code shouldBe code }
        ApplicationToolErrors.failure(ApplicationResult.StorageFailure("secret operation")).message shouldBe
            "Applications cannot be used now."
    }

    @Test
    fun `violations become the arguments of the tool they were reported for`() {
        val answer =
            ApplicationToolErrors.failure(
                ApplicationResult.Invalid(
                    listOf(
                        ApplicationViolation(ApplicationField.COMPANY, ApplicationProblem.NOT_FOUND),
                        ApplicationViolation(ApplicationField.INTERVIEW_START, ApplicationProblem.OUT_OF_RANGE),
                    ),
                ),
            )

        answer.problems shouldBe
            listOf(ArgumentProblem("companyId", "not-found"), ArgumentProblem("localStart", "out-of-range"))
    }

    @Test
    fun `a link that cannot be imported says to paste the text, for the day a URL import exists`() {
        fun message(problem: ApplicationProblem) =
            ApplicationToolErrors
                .failure(ApplicationResult.Invalid(listOf(ApplicationViolation(ApplicationField.SOURCE_URL, problem))))
                .message

        message(ApplicationProblem.NOT_ALLOWED).contains("LinkedIn, StepStone or Indeed") shouldBe true
        message(ApplicationProblem.UNREACHABLE).contains("start_text_import") shouldBe true
        message(ApplicationProblem.INVALID_URL) shouldBe "The arguments are invalid."
    }

    @Test
    fun `every application field has an argument name`() {
        ApplicationField.entries.map(ApplicationToolErrors::argumentOf).toSet() shouldHaveSize
            ApplicationField.entries.size
    }

    @Test
    fun `a delete's answer is its success or the failure's error`() {
        ApplicationToolErrors.deleted(ApplicationResult.Success(Unit)) shouldBe ToolAnswer.Result(Unit)
        ApplicationToolErrors.deleted(ApplicationResult.NotFound).shouldBe(
            ToolAnswer.Error("not-found", "No application has this id."),
        )
    }
}
