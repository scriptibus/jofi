// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import io.github.scriptibus.jofi.shared.domain.paging.PageInfo

/**
 * Where a page sits in its list (ADR-0056): the [page] (from 0) of [size] entries, [total] entries in all, and whether
 * a next page has [hasMore]. Offset paging: a list that changes between two reads can repeat or skip an entry.
 */
data class PageResponse(
    val page: Int,
    val size: Int,
    val total: Int,
    val hasMore: Boolean,
) {
    companion object {
        fun from(info: PageInfo): PageResponse = PageResponse(info.page, info.size, info.total, info.hasMore)
    }
}
