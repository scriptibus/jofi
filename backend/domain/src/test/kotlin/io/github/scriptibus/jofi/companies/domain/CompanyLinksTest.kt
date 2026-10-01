// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class CompanyLinksTest {
    private val erika = ContactId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val max = ContactId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val backend = EntityRef("application", "a-1")
    private val platform = EntityRef("application", "a-2")
    private val screening = EntityRef("interview", "i-1")

    @Test
    fun `groups the deleted contacts per application and per interview, each contact once`() {
        val links =
            CompanyLinks(
                emptyList(),
                linkedMapOf(
                    max to ContactLinks(listOf(platform, backend), listOf(screening), emptyList()),
                    erika to ContactLinks(listOf(backend, backend), emptyList(), emptyList()),
                ),
            )

        links.contactsByApplication.keys shouldContainExactly listOf(backend, platform)
        links.contactsByApplication.getValue(backend) shouldContainExactly listOf(max, erika)
        links.contactsByApplication.getValue(platform) shouldContainExactly listOf(max)
        links.contactsByInterview shouldBe mapOf(screening to listOf(max))
    }

    @Test
    fun `counts the tasks of the company and of its contacts`() {
        val task = EntityRef("task", "t-1")
        val links =
            CompanyLinks(
                listOf(task, task),
                mapOf(
                    erika to ContactLinks(emptyList(), emptyList(), listOf(task)),
                    max to ContactLinks(emptyList(), emptyList(), emptyList()),
                ),
            )

        links.taskCount shouldBe 3
        links.contactsByApplication.shouldBeEmpty()
        links.contactsByInterview.shouldBeEmpty()
    }
}
