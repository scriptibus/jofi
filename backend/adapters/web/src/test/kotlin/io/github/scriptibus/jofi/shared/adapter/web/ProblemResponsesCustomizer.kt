// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.web.method.HandlerMethod

/**
 * Documents the problem responses a handler declares with [ProblemResponses] (400 with the
 * [ValidationProblem] schema, 404, 409), so clients see them in `api/openapi.json`.
 */
class ProblemResponsesCustomizer :
    OperationCustomizer,
    OpenApiCustomizer {
    override fun customize(
        operation: Operation,
        handlerMethod: HandlerMethod,
    ): Operation {
        handlerMethod.getMethodAnnotation(ProblemResponses::class.java)?.value?.forEach { kind ->
            val schema = if (kind == ProblemKind.INVALID_INPUT) VALIDATION_PROBLEM else PROBLEM_DETAIL
            operation.responses.addApiResponse(kind.status.value().toString(), response(schema, kind.description))
        }
        return operation
    }

    override fun customise(openApi: OpenAPI) {
        openApi.components.addSchemas(VALIDATION_PROBLEM, validationProblemSchema())
    }

    private fun validationProblemSchema(): Schema<*> =
        ComposedSchema()
            .addAllOfItem(Schema<Any>().`$ref`("#/components/schemas/$PROBLEM_DETAIL"))
            .addAllOfItem(
                ObjectSchema()
                    .addProperty("violations", ArraySchema().items(violationSchema()).description("Every broken rule"))
                    .required(listOf("violations")),
            ).description("400 answer for input that breaks the rules: nothing changed.")

    private fun violationSchema(): Schema<*> =
        ObjectSchema()
            .addProperty("field", StringSchema().description("The request field, e.g. name or careersPage"))
            .addProperty(
                "problem",
                StringSchema().description("REQUIRED, TOO_LONG, TOO_MANY, INVALID_URL or OUT_OF_RANGE"),
            ).required(listOf("field", "problem"))

    private fun response(
        schemaName: String,
        description: String,
    ): ApiResponse {
        val schema = Schema<Any>().`$ref`("#/components/schemas/$schemaName")
        return ApiResponse()
            .description(description)
            .content(Content().addMediaType(PROBLEM_JSON, MediaType().schema(schema)))
    }

    private companion object {
        const val PROBLEM_DETAIL = "ProblemDetail"
        const val VALIDATION_PROBLEM = "ValidationProblem"
        const val PROBLEM_JSON = "application/problem+json"
    }
}
