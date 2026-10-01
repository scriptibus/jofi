// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.ImportStatus
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.applications.domain.UrlImportOutcome
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.http.BlockReason
import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.http.FetchedResource
import io.github.scriptibus.jofi.shared.domain.http.ResponseBody
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.UUID

class StartUrlImportUseCaseTest {
    private val fixtures = PostingImportFixtures()
    private val base = fixtures.base
    private val start =
        StartUrlImportUseCase(
            fixtures.importPort,
            fixtures.sources,
            fixtures.ai,
            fixtures.http,
            fixtures.jobs,
            base.changelog,
            fixtures.transactions,
            CLOCK,
        )

    private fun started(url: String = URL): PostingImport =
        start
            .execute(url, Actor.User)
            .shouldBeInstanceOf<ApplicationResult.Success<UrlImportOutcome>>()
            .value.import

    @Test
    fun `a fetched posting is stored as a pending import, with its link normalised and its job queued`() {
        val pending = started("$URL?utm_source=newsletter")

        pending.status shouldBe ImportStatus.PENDING
        pending.sourceUrl?.value shouldBe URL
        pending.text?.value shouldBe "Senior Kotlin Engineer\n\nACME Robotics"
        fixtures.imports.values.toList() shouldBe listOf(pending)
        fixtures.queued shouldHaveSize 1
    }

    @Test
    fun `a URL already imported successfully answers at once with the existing application, fetching nothing`() {
        val application = base.application()
        val source =
            ApplicationSource(SourceId(UUID.randomUUID()), application.id, SourceKind.URL, WebAddress(URL), NOW)
        base.applications[application.id] = application.copy(sources = listOf(source))

        val outcome =
            start
                .execute(
                    URL,
                    Actor.User,
                ).shouldBeInstanceOf<ApplicationResult.Success<UrlImportOutcome>>()
                .value

        outcome.shouldBeInstanceOf<UrlImportOutcome.AlreadyImported>()
        outcome.import.status shouldBe ImportStatus.SUCCEEDED
        outcome.import.application shouldBe application.id
        fixtures.fetchRequests shouldHaveSize 0
    }

    @Test
    fun `submitting the same link twice before it finishes answers with the same pending import (F6)`() {
        val first = started()

        val second = start.execute(URL, Actor.User).shouldBeInstanceOf<ApplicationResult.Success<UrlImportOutcome>>()

        second.value.shouldBeInstanceOf<UrlImportOutcome.Started>()
        second.value.import shouldBe first
        fixtures.fetchRequests shouldHaveSize 1
    }

    @Test
    fun `a link to a site Jofi never scrapes is refused without any fetch`() {
        val refused = start.execute("https://www.linkedin.com/jobs/view/1", Actor.User)

        refused shouldBe
            ApplicationResult.Invalid(
                listOf(ApplicationViolation(ApplicationField.SOURCE_URL, ApplicationProblem.NOT_ALLOWED)),
            )
        fixtures.fetchRequests shouldHaveSize 0
    }

    @Test
    fun `an invalid URL is refused without any fetch`() {
        val refused = start.execute("not a url", Actor.User)

        refused shouldBe
            ApplicationResult.Invalid(
                listOf(ApplicationViolation(ApplicationField.SOURCE_URL, ApplicationProblem.INVALID_URL)),
            )
        fixtures.fetchRequests shouldHaveSize 0
    }

    @Test
    fun `without an extraction model the import is refused before anything is fetched`() {
        fixtures.assignment = CheckAiTaskAssignedPort.Assignment.NotAssigned

        start.execute(URL, Actor.User) shouldBe ApplicationResult.AiNotConfigured
        fixtures.fetchRequests shouldHaveSize 0
    }

    @Test
    fun `a blocked, failed or non-html fetch is refused with a hint to paste the text instead`() {
        fixtures.fetched = FetchResult.Blocked(BlockReason.ADDRESS_NOT_ALLOWED)
        unreachable()
        fixtures.fetched = FetchResult.HttpError(404)
        unreachable()
        fixtures.fetched = FetchResult.Timeout
        unreachable()
    }

    @Test
    fun `a page with no readable text is refused the same way, and nothing is stored`() {
        fixtures.fetched = html("<html><body><script>x()</script></body></html>")

        unreachable()
        fixtures.imports.size shouldBe 0
    }

    private fun unreachable() {
        start.execute(URL, Actor.User) shouldBe
            ApplicationResult.Invalid(
                listOf(ApplicationViolation(ApplicationField.SOURCE_URL, ApplicationProblem.UNREACHABLE)),
            )
    }

    private fun html(body: String): FetchResult.Success =
        FetchResult.Success(FetchedResource(URI.create(URL), 200, "text/html", ResponseBody(body.toByteArray())))

    private companion object {
        const val URL = "https://jobs.example/posting"
    }
}
