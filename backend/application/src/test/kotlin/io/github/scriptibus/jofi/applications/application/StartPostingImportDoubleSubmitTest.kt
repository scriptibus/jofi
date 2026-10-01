// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Double-submit protection for [StartPostingImportUseCase] (#187 finding F6), split out of [PostingImportUseCasesTest].
 */
class StartPostingImportDoubleSubmitTest {
    private val fixtures = PostingImportFixtures()
    private val start =
        StartPostingImportUseCase(
            fixtures.importPort,
            fixtures.ai,
            fixtures.jobs,
            fixtures.base.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val run =
        RunPostingImportUseCase(
            fixtures.importPort,
            fixtures.extractionPort,
            fixtures.companies,
            fixtures.discovered,
            fixtures.base.changelog,
            fixtures.transactions,
            CLOCK,
        )

    private fun started(): PostingImport =
        start.execute(POSTING, Actor.User).shouldBeInstanceOf<ApplicationResult.Success<PostingImport>>().value

    @Test
    fun `submitting the same text twice before the first import finishes answers with that same import`() {
        val first = started()

        val second = start.execute(POSTING, Actor.User).shouldBeInstanceOf<ApplicationResult.Success<PostingImport>>()

        second.value shouldBe first
        fixtures.imports.size shouldBe 1
        fixtures.queued shouldHaveSize 1
    }

    @Test
    fun `a double submit is not matched once the first import is no longer pending`() {
        val first = started()
        run.execute(first.id, Actor.Ai)

        val second = started()

        second.id shouldNotBe first.id
        fixtures.imports.size shouldBe 2
    }

    @Test
    fun `a pending import that stalled has no job any more, so a resubmit starts a new one and queues it`() {
        val first = started()
        fixtures.imports[first.id] =
            first.copy(
                createdAt = first.createdAt.minus(PostingImport.STALLED_AFTER),
                updatedAt = first.updatedAt.minus(PostingImport.STALLED_AFTER),
            )

        val second = started()

        second.id shouldNotBe first.id
        fixtures.queued shouldHaveSize 2
    }

    private companion object {
        const val POSTING = "# Senior Kotlin Developer\n\nACME Robotics AG, Berlin."
    }
}
