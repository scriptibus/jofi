// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.applications.config.PostingImportConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class PostingImportConfigurationTest {
    @Test
    fun `a positive cap is accepted`() {
        PostingImportConfiguration.validFetchCap(1) shouldBe 1
        PostingImportConfiguration.validFetchCap(3) shouldBe 3
        PostingImportConfiguration().importFetchLimit(2).runIfFree({ "full" }) { "ran" } shouldBe "ran"
    }

    @Test
    fun `a cap above the maximum is refused too, since a huge value would switch the cap off`() {
        PostingImportConfiguration.validFetchCap(50) shouldBe 50
        shouldThrow<IllegalArgumentException> { PostingImportConfiguration.validFetchCap(51) }
            .message shouldContain "at most 50"
    }

    @Test
    fun `zero and negative caps fail the start with a clear message`() {
        listOf(0, -1).forEach { cap ->
            shouldThrow<IllegalArgumentException> { PostingImportConfiguration.validFetchCap(cap) }
                .message shouldContain "jofi.import.max-concurrent-fetches must be positive and at most 50"
            shouldThrow<IllegalArgumentException> { PostingImportConfiguration().importFetchLimit(cap) }
                .message shouldContain "jofi.import.max-concurrent-fetches must be positive and at most 50"
        }
    }
}
