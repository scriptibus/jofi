// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.paging

/**
 * Which page of a list is wanted: [page] counts from 0, a page holds [size] entries. Offset paging over a stable
 * order (ADR-0056): an entry added or removed between two reads can make the next page skip or repeat one entry; the
 * [PageInfo.total] of each answer shows that the list changed.
 */
data class PageRequest(
    val page: Int,
    val size: Int,
) {
    init {
        require(page in 0..MAX_PAGE && size in 1..MAX_SIZE) { "A page request is out of range" }
    }

    /** Entries before this page. Never overflows: [page] is bounded by [MAX_PAGE]. */
    val offset: Int get() = page * size

    companion object {
        const val DEFAULT_SIZE = 20

        /** Every answer goes into a model's context or a phone's screen, so a page stays small. */
        const val MAX_SIZE = 50
        const val MAX_PAGE = 10_000

        val FIRST = PageRequest(0, DEFAULT_SIZE)

        fun of(
            page: Int,
            size: Int,
        ): PageRequest? = if (page in 0..MAX_PAGE && size in 1..MAX_SIZE) PageRequest(page, size) else null
    }
}

/** A page request as a caller sent it: a missing value means the default. [validate] says what is out of range. */
data class PageInput(
    val page: Int? = null,
    val size: Int? = null,
) {
    fun validate(): PageValidation {
        val pageValue = page ?: 0
        val sizeValue = size ?: PageRequest.DEFAULT_SIZE
        val request = PageRequest.of(pageValue, sizeValue)
        return if (request != null) {
            PageValidation.Valid(request)
        } else {
            PageValidation.Invalid(
                pageOutOfRange = pageValue !in 0..PageRequest.MAX_PAGE,
                sizeOutOfRange = sizeValue !in 1..PageRequest.MAX_SIZE,
            )
        }
    }
}

sealed interface PageValidation {
    data class Valid(
        val request: PageRequest,
    ) : PageValidation

    data class Invalid(
        val pageOutOfRange: Boolean,
        val sizeOutOfRange: Boolean,
    ) : PageValidation
}

/** Where a page sits in the whole list: [total] entries in all, [hasMore] says whether a next page has some. */
data class PageInfo(
    val page: Int,
    val size: Int,
    val total: Int,
    val hasMore: Boolean,
) {
    companion object {
        fun of(
            request: PageRequest,
            total: Int,
        ): PageInfo = PageInfo(request.page, request.size, total, request.offset + request.size < total)
    }
}

/** One page of [items] and its [info]. */
data class Paged<out T>(
    val items: List<T>,
    val info: PageInfo,
) {
    companion object {
        /** The page of an [all] list held in memory. */
        fun <T> slice(
            all: List<T>,
            request: PageRequest,
        ): Paged<T> = Paged(all.drop(request.offset).take(request.size), PageInfo.of(request, all.size))
    }
}
