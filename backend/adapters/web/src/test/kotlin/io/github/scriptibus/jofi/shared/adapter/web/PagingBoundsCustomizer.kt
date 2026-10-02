// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.swagger.v3.oas.models.Operation
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.web.method.HandlerMethod
import java.math.BigDecimal

/**
 * Documents the bounds of `page` and `size` of the lists that use `PageInput` (ADR-0056) in the API contract: the
 * domain's limits, so a client sees them without reading the problem answers.
 */
class PagingBoundsCustomizer : OperationCustomizer {
    override fun customize(
        operation: Operation,
        handlerMethod: HandlerMethod,
    ): Operation {
        if (operation.operationId in PAGED_OPERATIONS) {
            operation.parameters.orEmpty().forEach { parameter ->
                when (parameter.name) {
                    "page" -> parameter.schema.minimum(BigDecimal.ZERO).maximum(BigDecimal(PageRequest.MAX_PAGE))
                    "size" -> parameter.schema.minimum(BigDecimal.ONE).maximum(BigDecimal(PageRequest.MAX_SIZE))
                }
            }
        }
        return operation
    }

    private companion object {
        val PAGED_OPERATIONS = setOf("listTaskGroups", "listSuggestedTasks", "listInterviews")
    }
}
