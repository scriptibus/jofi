// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.applications.domain.UrlImportOutcome
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/** The cap on concurrent fetches (#224) as the URL import use case meets it. */
class StartUrlImportFetchCapTest {
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

    private fun started() =
        start
            .execute(URL, Actor.User)
            .shouldBeInstanceOf<ApplicationResult.Success<UrlImportOutcome>>()
            .value.import

    @Test
    fun `over the fetch cap the request answers import busy at once, fetching and storing nothing`() {
        fixtures.fetchCapFull = true

        start.execute(URL, Actor.User) shouldBe ApplicationResult.ImportBusy
        fixtures.fetchRequests shouldHaveSize 0
        fixtures.imports.size shouldBe 0
        fixtures.queued shouldHaveSize 0
        fixtures.base.entries.size shouldBe 0
    }

    @Test
    fun `the fetch holds a permit and gives it back after a success, a failed fetch and an exception`() {
        started()
        fixtures.permitsHeldAtFetch shouldBe listOf(1)
        fixtures.permitsHeld shouldBe 0

        fixtures.fetched = FetchResult.Timeout
        start.execute(OTHER, Actor.User).shouldBeInstanceOf<ApplicationResult.Invalid>()
        fixtures.permitsHeld shouldBe 0

        fixtures.duringFetch = { error("the fetch blew up") }
        shouldThrow<IllegalStateException> { start.execute(THIRD, Actor.User) }
        fixtures.permitsHeld shouldBe 0
        fixtures.permitsTaken shouldBe 3
    }

    @Test
    fun `answers that need no fetch take no permit`() {
        val first = started()
        fixtures.permitsTaken shouldBe 1

        start
            .execute(URL, Actor.User)
            .shouldBeInstanceOf<ApplicationResult.Success<UrlImportOutcome>>()
            .value.import shouldBe first
        val application = base.application()
        val known = KNOWN
        val source =
            ApplicationSource(SourceId(UUID.randomUUID()), application.id, SourceKind.URL, WebAddress(known), NOW)
        base.applications[application.id] = application.copy(sources = listOf(source))
        start.execute(known, Actor.User).shouldBeInstanceOf<ApplicationResult.Success<UrlImportOutcome>>()

        fixtures.permitsTaken shouldBe 1
        fixtures.fetchCapFull = true
        start.execute(URL, Actor.User).shouldBeInstanceOf<ApplicationResult.Success<UrlImportOutcome>>()
        start.execute(known, Actor.User).shouldBeInstanceOf<ApplicationResult.Success<UrlImportOutcome>>()
    }

    @Test
    fun `without an extraction model no permit is taken`() {
        fixtures.assignment = CheckAiTaskAssignedPort.Assignment.NotAssigned

        start.execute(URL, Actor.User) shouldBe ApplicationResult.AiNotConfigured
        fixtures.permitsTaken shouldBe 0
    }

    private companion object {
        const val URL = "https://jobs.example/posting"
        const val OTHER = "https://jobs.example/other"
        const val THIRD = "https://jobs.example/third"
        const val KNOWN = "https://jobs.example/known"
    }
}
