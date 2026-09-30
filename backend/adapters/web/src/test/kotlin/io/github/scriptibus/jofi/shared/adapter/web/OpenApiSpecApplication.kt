// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import io.mockk.mockkClass
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
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
@Import(OpenApiSpecApplication.UseCaseStubs::class, OpenApiSpecApplication.ContractCustomizer::class)
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
     * every operation may answer with RFC 9457 problem details (`spring.mvc.problemdetails`).
     */
    class ContractCustomizer : OpenApiCustomizer {
        override fun customise(openApi: OpenAPI) {
            openApi.info(Info().title("Jofi API").version("1").description(DESCRIPTION))
            openApi.servers(listOf(Server().url("/")))
            openApi.components.addSchemas(PROBLEM_DETAIL, problemDetailSchema())
            openApi.paths.values
                .flatMap { it.readOperations() }
                .forEach { operation ->
                    if (operation.responses[DEFAULT_RESPONSE] == null) {
                        operation.responses.addApiResponse(DEFAULT_RESPONSE, problemResponse())
                    }
                }
        }

        private fun problemDetailSchema(): Schema<*> =
            ObjectSchema()
                .description("RFC 9457 problem details. Extension members may be added per problem type.")
                .addProperty("type", StringSchema().format("uri").description("Identifies the problem type"))
                .addProperty("title", StringSchema().description("Short summary of the problem type"))
                .addProperty("status", IntegerSchema().description("HTTP status code"))
                .addProperty("detail", StringSchema().description("Explanation of this occurrence"))
                .addProperty("instance", StringSchema().format("uri").description("This occurrence"))
                .additionalProperties(true)

        private fun problemResponse(): ApiResponse {
            val schema = Schema<Any>().`$ref`("#/components/schemas/$PROBLEM_DETAIL")
            return ApiResponse()
                .description("Error as RFC 9457 problem details")
                .content(Content().addMediaType(PROBLEM_JSON, MediaType().schema(schema)))
        }
    }

    private companion object {
        const val PROBLEM_DETAIL = "ProblemDetail"
        const val PROBLEM_JSON = "application/problem+json"
        const val DEFAULT_RESPONSE = "default"
        const val DESCRIPTION =
            "REST API of the Jofi backend. Generated from the controllers by OpenApiSpecTest; do not edit."
    }
}
