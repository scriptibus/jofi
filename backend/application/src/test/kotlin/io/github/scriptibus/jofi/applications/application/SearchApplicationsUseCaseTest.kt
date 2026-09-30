// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SearchApplicationsUseCaseTest {
    private val repository = mockk<ApplicationRepositoryPort>()
    private val useCase = SearchApplicationsUseCase(repository)
    private val search =
        ApplicationSearch(statuses = setOf(ApplicationStatus.APPLIED), unread = true, page = 1, size = 1)
    private val application =
        Application.create(
            ApplicationId(UUID.randomUUID()),
            ApplicationDetails("Backend Engineer", CompanyRef(UUID.randomUUID())),
            Instant.parse("2026-09-30T08:00:00Z"),
            unread = true,
        )

    @Test
    fun `answers the store's page for exactly the search, marking nothing read`() {
        every { repository.search(search) } returns
            ApplicationStoreResult.Success(ApplicationPage(listOf(application), 2))

        useCase.execute(search) shouldBe ApplicationResult.Success(ApplicationPage(listOf(application), 2))

        verify(exactly = 1) { repository.search(search) }
        verify(exactly = 0) { repository.setUnread(any(), any()) }
    }

    @Test
    fun `a store failure is a storage failure`() {
        every { repository.search(search) } returns ApplicationStoreResult.StorageFailure("search")

        useCase.execute(search) shouldBe ApplicationResult.StorageFailure("search")
    }
}
