// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChangelogQueryTest {
    @Test
    fun `a limit is between 1 and the maximum`() {
        ChangelogLimit(1).value shouldBe 1
        ChangelogLimit(ChangelogLimit.MAXIMUM).value shouldBe 500
        shouldThrow<IllegalArgumentException> { ChangelogLimit(0) }
        shouldThrow<IllegalArgumentException> { ChangelogLimit(ChangelogLimit.MAXIMUM + 1) }
    }

    @Test
    fun `a result is either a success with a value or a storage failure`() {
        val outcomes: List<ChangelogResult<Int>> =
            listOf(ChangelogResult.Success(3), ChangelogResult.StorageFailure("append"))

        val described =
            outcomes.map {
                when (it) {
                    is ChangelogResult.Success -> "ok ${it.value}"
                    is ChangelogResult.StorageFailure -> "failed ${it.operation}"
                }
            }

        described shouldBe listOf("ok 3", "failed append")
    }
}
