// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import io.mockk.mockkClass
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MapSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.beans.factory.support.GenericBeanDefinition
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.util.ClassUtils
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * The application context the API contract is rendered from: every controller and controller
 * advice of every context in this module, nothing else. Use cases are relaxed MockK stubs because
 * rendering the spec never calls them; new controllers therefore appear without extra wiring.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan(
    basePackages = ["io.github.scriptibus.jofi"],
    useDefaultFilters = false,
    includeFilters = [
        ComponentScan.Filter(RestController::class),
        ComponentScan.Filter(RestControllerAdvice::class),
    ],
)
@Import(
    OpenApiSpecApplication.UseCaseStubs::class,
    OpenApiSpecApplication.ContractCustomizer::class,
    ProblemResponsesCustomizer::class,
    WriteOnlySecretCustomizer::class,
)
class OpenApiSpecApplication {
    /** Registers a stub for every constructor parameter (a use case) of every controller. */
    class UseCaseStubs : BeanDefinitionRegistryPostProcessor {
        override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
            registry.beanDefinitionNames
                .mapNotNull { registry.getBeanDefinition(it).beanClassName }
                .map { ClassUtils.resolveClassName(it, javaClass.classLoader) }
                .filter { it.isAnnotationPresent(RestController::class.java) }
                .flatMap { controller -> controller.constructors.flatMap { it.parameterTypes.asList() } }
                .distinct()
                .forEach { type -> registry.registerBeanDefinition(type.name, stubDefinition(type)) }
        }

        private fun stubDefinition(type: Class<*>) =
            GenericBeanDefinition().apply {
                setBeanClass(type)
                setInstanceSupplier { mockkClass(type.kotlin, relaxed = true) }
            }
    }

    /**
     * Fixed metadata (so the file only changes when the API does) and the one error contract:
     * every operation may answer with RFC 9457 problem details (`spring.mvc.problemdetails`), and an
     * operation that takes the `Jofi-Confirmation` header with a 428 `ConfirmationRequiredProblem`.
     */
    class ContractCustomizer : OpenApiCustomizer {
        override fun customise(openApi: OpenAPI) {
            openApi.info(Info().title("Jofi API").version("1").description(DESCRIPTION))
            openApi.servers(listOf(Server().url("/")))
            openApi.components.addSchemas(PROBLEM_DETAIL, problemDetailSchema())
            openApi.components.addSchemas(CONFIRMATION_REQUIRED, confirmationRequiredSchema())
            openApi.paths.values
                .flatMap { it.readOperations() }
                .forEach { operation ->
                    if (operation.responses[DEFAULT_RESPONSE] == null) {
                        operation.responses.addApiResponse(
                            DEFAULT_RESPONSE,
                            problemResponse(PROBLEM_DETAIL, "Error as RFC 9457 problem details"),
                        )
                    }
                    if (operation.parameters.orEmpty().any { it.`in` == "header" && it.name == Confirmations.HEADER }) {
                        operation.responses.addApiResponse(
                            PRECONDITION_REQUIRED,
                            problemResponse(CONFIRMATION_REQUIRED, CONFIRMATION_REQUIRED_TEXT),
                        )
                    }
                }
        }

        /** The first step of a two-step confirmation (ADR-0039, `Confirmations`). */
        private fun confirmationRequiredSchema(): Schema<*> =
            ComposedSchema()
                .addAllOfItem(Schema<Any>().`$ref`("#/components/schemas/$PROBLEM_DETAIL"))
                .addAllOfItem(
                    ObjectSchema()
                        .addProperty(
                            "confirmationToken",
                            StringSchema().description("Send it back in the ${Confirmations.HEADER} header"),
                        ).addProperty(
                            "expiresAt",
                            StringSchema().format("date-time").description("The token is refused from then on"),
                        ).addProperty(
                            "operation",
                            StringSchema().description("What would run, e.g. applications.delete"),
                        ).addProperty(
                            "targets",
                            ArraySchema().items(StringSchema()).description("Ids of what it would affect"),
                        ).addProperty("effect", effectSchema())
                        .required(listOf("confirmationToken", "expiresAt", "operation", "targets", "effect")),
                ).description(
                    "428 answer of a destructive or outward-facing operation: nothing ran yet. Repeat the request " +
                        "with confirmationToken in the ${Confirmations.HEADER} header once the user confirmed.",
                )

        /** Structured, server-derived effect; clients render it in the user's language (no prose). */
        private fun effectSchema(): Schema<*> =
            ObjectSchema()
                .description("What would change: kind of thing, its name, and counts of what goes with it")
                .addProperty("kind", StringSchema().description("Kind of thing affected, e.g. application"))
                .addProperty("name", StringSchema().description("Display name of the target"))
                .addProperty(
                    "counts",
                    MapSchema()
                        .additionalProperties(IntegerSchema().format("int32"))
                        .description("What goes with it, e.g. documents: 3"),
                ).required(listOf("kind", "name", "counts"))

        private fun problemDetailSchema(): Schema<*> =
            ObjectSchema()
                .description("RFC 9457 problem details. Extension members may be added per problem type.")
                .addProperty("type", StringSchema().format("uri").description("Identifies the problem type"))
                .addProperty("title", StringSchema().description("Short summary of the problem type"))
                .addProperty("status", IntegerSchema().description("HTTP status code"))
                .addProperty("detail", StringSchema().description("Explanation of this occurrence"))
                .addProperty("instance", StringSchema().format("uri").description("This occurrence"))
                .additionalProperties(true)

        private fun problemResponse(
            schemaName: String,
            description: String,
        ): ApiResponse {
            val schema = Schema<Any>().`$ref`("#/components/schemas/$schemaName")
            return ApiResponse()
                .description(description)
                .content(Content().addMediaType(PROBLEM_JSON, MediaType().schema(schema)))
        }
    }

    private companion object {
        const val PROBLEM_DETAIL = "ProblemDetail"
        const val CONFIRMATION_REQUIRED = "ConfirmationRequiredProblem"
        const val PRECONDITION_REQUIRED = "428"
        const val CONFIRMATION_REQUIRED_TEXT = "Nothing ran yet: confirm, then repeat with the token (ADR-0039)"
        const val PROBLEM_JSON = "application/problem+json"
        const val DEFAULT_RESPONSE = "default"
        const val DESCRIPTION =
            "REST API of the Jofi backend. Generated from the controllers by OpenApiSpecTest; do not edit."
    }
}
