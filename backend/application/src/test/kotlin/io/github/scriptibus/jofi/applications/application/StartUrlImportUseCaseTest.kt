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
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.UUID

class StartUrlImportUseCaseTest {
    private val fixtures = PostingImportFixtures()
    private val base = fixtures.base
    private val start =
        StartUrlImportUseCase(
            fixtures.resolveUrl,
            fixtures.locks,
            fixtures.importPort,
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

        second.value.shouldBeInstanceOf<UrlImportOutcome.AlreadyPending>()
        second.value.import shouldBe first
        fixtures.fetchRequests shouldHaveSize 1
    }

    @Test
    fun `a pending import that stalled is asked for again as its next attempt and queued again, without a new fetch`() {
        val first = started()
        fixtures.imports[first.id] =
            first.copy(
                createdAt = first.createdAt.minus(PostingImport.STALLED_AFTER),
                updatedAt = first.updatedAt.minus(PostingImport.STALLED_AFTER),
            )

        val second = started()

        second.id shouldBe first.id
        second.attempt shouldBe 2
        second.status shouldBe ImportStatus.PENDING
        fixtures.imports.size shouldBe 1
        fixtures.queued shouldHaveSize 2
        fixtures.fetchRequests shouldHaveSize 1
    }

    @Test
    fun `an application that got the link while it was fetched wins over storing a second import`() {
        val application = fixtures.base.application()
        val source =
            ApplicationSource(SourceId(UUID.randomUUID()), application.id, SourceKind.URL, WebAddress(URL), NOW)
        fixtures.duringFetch =
            { fixtures.base.applications[application.id] = application.copy(sources = listOf(source)) }

        val outcome =
            start.execute(URL, Actor.User).shouldBeInstanceOf<ApplicationResult.Success<UrlImportOutcome>>().value

        outcome.shouldBeInstanceOf<UrlImportOutcome.AlreadyImported>()
        outcome.import.application shouldBe application.id
        fixtures.imports.values.none { it.status == ImportStatus.PENDING } shouldBe true
        fixtures.queued shouldHaveSize 0
    }

    @Test
    fun `a request that waits too long for the link answers import in progress, fetching nothing`() {
        fixtures.lockTimesOut = true

        start.execute(URL, Actor.User) shouldBe ApplicationResult.ImportInProgress
        fixtures.fetchRequests shouldHaveSize 0
        fixtures.imports.size shouldBe 0
    }

    @Test
    fun `no transaction is open while the page is fetched, and the link is locked for the whole import`() {
        started()

        fixtures.transactionsOpenAtFetch shouldBe listOf(0)
        fixtures.lockedKeys shouldBe listOf("url:$URL")
    }

    @Test
    fun `a failed fetch leaves nothing stored, so the next attempt starts clean`() {
        fixtures.fetched = FetchResult.Timeout
        refusedWith(ApplicationProblem.TIMEOUT)
        fixtures.imports.size shouldBe 0
        fixtures.base.entries.size shouldBe 0

        fixtures.fetched = html("<html><body><h1>Senior Kotlin Engineer</h1></body></html>")

        started().status shouldBe ImportStatus.PENDING
        fixtures.imports.size shouldBe 1
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
    fun `each way a fetch fails has its own code, so the dialog can say why`() {
        mapOf(
            FetchResult.Blocked(BlockReason.ADDRESS_NOT_ALLOWED) to ApplicationProblem.UNREACHABLE,
            FetchResult.Unreachable to ApplicationProblem.UNREACHABLE,
            FetchResult.TooManyRedirects(5) to ApplicationProblem.UNREACHABLE,
            FetchResult.HttpError(404) to ApplicationProblem.UNREACHABLE,
            FetchResult.HttpError(503) to ApplicationProblem.UNREACHABLE,
            FetchResult.HttpError(401) to ApplicationProblem.LOGIN_REQUIRED,
            FetchResult.HttpError(403) to ApplicationProblem.LOGIN_REQUIRED,
            FetchResult.Timeout to ApplicationProblem.TIMEOUT,
            FetchResult.TooLarge(1_048_576) to ApplicationProblem.TOO_LARGE,
            FetchResult.ContentTypeNotAccepted("application/pdf") to ApplicationProblem.NOT_HTML,
        ).forEach { (fetched, problem) ->
            fixtures.fetched = fetched
            refusedWith(problem)
        }
        fixtures.imports.size shouldBe 0
    }

    @Test
    fun `a page with no readable text is refused as such, and nothing is stored`() {
        fixtures.fetched = html("<html><body><script>x()</script></body></html>")

        refusedWith(ApplicationProblem.NO_TEXT)
        fixtures.imports.size shouldBe 0
    }

    @Test
    fun `a link that is no URL java net URI can read is refused as invalid, without a fetch or an exception`() {
        listOf(
            "https://[abc/x",
            "https://exa%mple.com/x",
            "https://exa|mple.com/x",
            "https://exa\"mple.com/",
            "https://b\u00fccher.example/" + "a".repeat(2_030),
            "https://my_team.example/job",
        ).forEach { link ->
            start.execute(link, Actor.User) shouldBe
                ApplicationResult.Invalid(
                    listOf(ApplicationViolation(ApplicationField.SOURCE_URL, ApplicationProblem.INVALID_URL)),
                )
        }
        fixtures.fetchRequests shouldHaveSize 0
    }

    @Test
    fun `a shortener of a disallowed site is refused without a fetch`() {
        start.execute("https://lnkd.in/abc123", Actor.User) shouldBe notAllowed()
        fixtures.fetchRequests shouldHaveSize 0
    }

    @Test
    fun `a redirect that ends on a disallowed site is refused after the fetch, storing and sending nothing`() {
        fixtures.fetched =
            FetchResult.Success(
                FetchedResource(
                    URI.create("https://www.linkedin.com/jobs/view/1"),
                    200,
                    "text/html",
                    ResponseBody("<html><body><h1>Senior Kotlin Engineer</h1></body></html>".toByteArray()),
                ),
            )

        start.execute("https://short.example/x", Actor.User) shouldBe notAllowed()

        fixtures.imports.size shouldBe 0
        fixtures.queued shouldHaveSize 0
        fixtures.base.entries.size shouldBe 0
    }

    @Test
    fun `a redirect to a login page is a login wall, refused so the user pastes the text instead`() {
        fixtures.fetched =
            FetchResult.Success(
                FetchedResource(
                    URI.create("https://jobs.example/login?next=/posting"),
                    200,
                    "text/html",
                    ResponseBody("<html><body>Please sign in to continue</body></html>".toByteArray()),
                ),
            )

        refusedWith(ApplicationProblem.LOGIN_REQUIRED)
        fixtures.imports.size shouldBe 0
    }

    @Test
    fun `a posting whose own path says login or sso is no login wall when nothing redirected`() {
        listOf("https://jobs.example/careers/login/42", "https://jobs.example/jobs/sso").forEach { link ->
            fixtures.imports.clear()
            fixtures.fetched =
                FetchResult.Success(
                    FetchedResource(
                        URI.create(link),
                        200,
                        "text/html",
                        ResponseBody("<html><body><h1>Senior Kotlin Engineer</h1></body></html>".toByteArray()),
                    ),
                )

            started(link).text?.value shouldBe "Senior Kotlin Engineer"
        }
    }

    @Test
    fun `a redirect from a login-looking link to another login page is no new wall either`() {
        fixtures.fetched =
            FetchResult.Success(
                FetchedResource(
                    URI.create("https://jobs.example/sso/login"),
                    200,
                    "text/html",
                    ResponseBody("<html><body><h1>Senior Kotlin Engineer</h1></body></html>".toByteArray()),
                ),
            )

        started("https://jobs.example/login/42").text?.value shouldBe "Senior Kotlin Engineer"
    }

    @Test
    fun `a byte order mark is not part of the text`() {
        val bytes = "\uFEFF<html><body><p>Titel</p></body></html>".toByteArray(Charsets.UTF_8)
        fixtures.fetched = FetchResult.Success(FetchedResource(URI.create(URL), 200, "text/html", ResponseBody(bytes)))

        started().text?.value shouldBe "Titel"
    }

    @Test
    fun `the cut at the maximum length never splits a surrogate pair`() {
        val page = "<html><body><p>" + "a".repeat(99_999) + "\uD83D\uDE00 tail</p></body></html>"
        fixtures.fetched = html(page)

        started().text?.value shouldBe "a".repeat(99_999)
    }

    @Test
    fun `the page is decoded with the character set it declares`() {
        val body = "<html><body><p>Entwickler f\u00fcr K\u00fcche</p></body></html>"
        fixtures.fetched =
            FetchResult.Success(
                FetchedResource(
                    URI.create(URL),
                    200,
                    "text/html; charset=ISO-8859-1",
                    ResponseBody(body.toByteArray(Charsets.ISO_8859_1)),
                ),
            )
        started().text?.value shouldBe "Entwickler f\u00fcr K\u00fcche"

        fixtures.imports.clear()
        val meta = "<html><head><meta charset=\"windows-1252\"></head><body><p>Gr\u00f6\u00dfe \u20ac</p></body></html>"
        fixtures.fetched =
            FetchResult.Success(
                FetchedResource(
                    URI.create(URL),
                    200,
                    "text/html",
                    ResponseBody(meta.toByteArray(charset("windows-1252"))),
                ),
            )
        started().text?.value shouldBe "Gr\u00f6\u00dfe \u20ac"
    }

    @Test
    fun `the fetch asks for html only and caps the body at one mebibyte`() {
        started()

        val request = fixtures.fetchRequests.single()
        request.acceptedContentTypes shouldBe setOf("text/html", "application/xhtml+xml")
        request.limits.maxBodyBytes shouldBe 1_048_576L
    }

    @Test
    fun `a page that is all invalid numeric entities still imports its readable text`() {
        fixtures.fetched = html("<html><body><p>&#0; &#xFFFFFFFFFF; Kotlin &#1114112;</p></body></html>")

        started().text?.value shouldBe "&#0; &#xFFFFFFFFFF; Kotlin &#1114112;"
    }

    private fun notAllowed(): ApplicationResult<UrlImportOutcome> =
        ApplicationResult.Invalid(
            listOf(ApplicationViolation(ApplicationField.SOURCE_URL, ApplicationProblem.NOT_ALLOWED)),
        )

    private fun refusedWith(problem: ApplicationProblem) {
        start.execute(URL, Actor.User) shouldBe
            ApplicationResult.Invalid(listOf(ApplicationViolation(ApplicationField.SOURCE_URL, problem)))
    }

    private fun html(body: String): FetchResult.Success =
        FetchResult.Success(FetchedResource(URI.create(URL), 200, "text/html", ResponseBody(body.toByteArray())))

    private companion object {
        const val URL = "https://jobs.example/posting"
    }
}
