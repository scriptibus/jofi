// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Renders the OpenAPI contract (ADR-0016) from the controllers and compares it with the committed
 * `api/openapi.json`, so `./gradlew check` fails when they drift apart. With
 * `-Djofi.openapi.write=true` (task `updateOpenApiSpec`) it rewrites the file instead.
 */
@SpringBootTest(
    classes = [OpenApiSpecApplication::class],
    properties = [
        "springdoc.api-docs.version=openapi_3_0",
        "springdoc.writer-with-order-by-keys=true",
        "springdoc.writer-with-default-pretty-printer=true",
        "springdoc.default-produces-media-type=application/json",
        // Errors are declared once, as the `default` ProblemDetail response (ContractCustomizer).
        "springdoc.override-with-generic-response=false",
        // A query parameter object (e.g. `ApplicationListQuery`) is one query parameter per property, as
        // Spring binds it; `@ParameterObject` would do the same, but springdoc is not on the main classpath.
        "springdoc.default-flat-param-object=true",
    ],
)
// The spec is rendered without the security filter chain, which lives in bootstrap.
@AutoConfigureMockMvc(addFilters = false)
class OpenApiSpecTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    private val specFile: Path =
        Path.of(requireNotNull(System.getProperty("jofi.openapi.spec")) { "jofi.openapi.spec is not set" })

    @Test
    fun `the paged lists document the bounds of page and size, each its own`() {
        val spec = JsonMapper().readTree(renderSpec())
        val lists = listOf("/api/tasks" to "get", "/api/tasks/suggestions" to "get")

        lists.forEach { (path, method) ->
            val parameters = spec["paths"][path][method]["parameters"].associateBy { it["name"].asString() }
            val page = parameters.getValue("page")["schema"]
            val size = parameters.getValue("size")["schema"]
            listOf(page["minimum"].asInt(), page["maximum"].asInt()) shouldBe listOf(0, PageRequest.MAX_PAGE)
            listOf(size["minimum"].asInt(), size["maximum"].asInt()) shouldBe listOf(1, PageRequest.MAX_SIZE)
        }
    }

    @Test
    fun `the committed spec matches the controllers`() {
        val rendered = renderSpec()
        if (System.getProperty("jofi.openapi.write") == "true") {
            specFile.writeText(rendered)
        }
        val committed = if (specFile.exists()) specFile.readText() else ""

        withClue("$specFile is stale: run `./gradlew :adapters:web:updateOpenApiSpec` and commit it") {
            committed shouldBe rendered
        }
    }

    @Test
    fun `system info is part of the contract with a typed response`() {
        val spec = servedSpec()

        spec
            .extractingPath("$.paths['/api/system/info'].get.responses['200'].content['application/json'].schema.\$ref")
            .isEqualTo("#/components/schemas/SystemInfoResponse")
        spec
            .extractingPath("$.components.schemas.SystemInfoResponse.properties.*.type")
            .asArray()
            .containsExactly("string", "string")
        spec
            .extractingPath("$.components.schemas.SystemInfoResponse.required")
            .asArray()
            .containsExactlyInAnyOrder("name", "version")
    }

    @Test
    fun `every operation documents errors as problem details`() {
        val spec = servedSpec()

        spec
            .extractingPath("$.paths.*.*.responses.default.content['application/problem+json'].schema.\$ref")
            .asArray()
            .isNotEmpty()
            .containsOnly("#/components/schemas/ProblemDetail")
        spec
            .extractingPath("$.components.schemas.ProblemDetail.properties")
            .asMap()
            .containsOnlyKeys("type", "title", "status", "detail", "instance")
    }

    @Test
    fun `the confirmation-required problem is part of the contract`() {
        servedSpec()
            .extractingPath("$.components.schemas.ConfirmationRequiredProblem.allOf[1].required")
            .asArray()
            .containsExactlyInAnyOrder("confirmationToken", "expiresAt", "operation", "targets", "effect")
    }

    private fun servedSpec() =
        mvc
            .get()
            .uri(API_DOCS)
            .assertThat()
            .hasStatusOk()
            .bodyJson()

    private fun renderSpec(): String {
        val response =
            mvc
                .get()
                .uri(API_DOCS)
                .exchange()
                .response
        // Never compare or write an error page as the contract.
        response.status shouldBe HttpStatus.OK.value()
        val body = response.getContentAsString(StandardCharsets.UTF_8)
        return body.replace("\r\n", "\n").trimEnd() + "\n"
    }

    private companion object {
        const val API_DOCS = "/v3/api-docs"
    }
}
