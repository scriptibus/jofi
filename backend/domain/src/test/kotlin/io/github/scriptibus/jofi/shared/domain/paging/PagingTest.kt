// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.paging

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class PagingTest {
    @Test
    fun `a missing page and size mean the first page of the default size`() {
        val valid = PageInput().validate().shouldBeInstanceOf<PageValidation.Valid>()

        valid.request.page shouldBe 0
        valid.request.size shouldBe PageRequest.DEFAULT_SIZE
        valid.request.offset shouldBe 0
    }

    @Test
    fun `the largest size and page are accepted, one more is not`() {
        PageInput(PageRequest.MAX_PAGE, PageRequest.MAX_SIZE).validate().shouldBeInstanceOf<PageValidation.Valid>()
        PageInput(0, PageRequest.MAX_SIZE + 1).validate() shouldBe PageValidation.Invalid(false, true)
        PageInput(PageRequest.MAX_PAGE + 1, 1).validate() shouldBe PageValidation.Invalid(true, false)
    }

    @Test
    fun `a negative page and a zero or negative size are out of range and both are reported`() {
        PageInput(-1, null).validate() shouldBe PageValidation.Invalid(true, false)
        PageInput(null, 0).validate() shouldBe PageValidation.Invalid(false, true)
        PageInput(-1, -5).validate() shouldBe PageValidation.Invalid(true, true)
    }

    @Test
    fun `the offset of the largest page fits an int`() {
        PageRequest(PageRequest.MAX_PAGE, PageRequest.MAX_SIZE).offset shouldBe 500_000
    }

    @Test
    fun `slicing pages through a list reaches every entry exactly once`() {
        val all = (1..120).toList()

        val pages = (0..2).map { Paged.slice(all, PageRequest(it, 50)) }

        pages.flatMap { it.items } shouldContainExactly all
        pages.map { it.info.hasMore } shouldContainExactly listOf(true, true, false)
        pages.forEach { it.info.total shouldBe 120 }
    }

    @Test
    fun `a page that ends exactly at the total has no more, and a page past the end is empty`() {
        val all = (1..100).toList()

        Paged.slice(all, PageRequest(1, 50)).info.hasMore shouldBe false
        Paged.slice(all, PageRequest(2, 50)).items.shouldBeEmpty()
        Paged.slice(emptyList<Int>(), PageRequest.FIRST).info shouldBe PageInfo(0, PageRequest.DEFAULT_SIZE, 0, false)
    }
}
