// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.application.ClearModelPriceUseCase
import io.github.scriptibus.jofi.setup.application.ListModelPricesUseCase
import io.github.scriptibus.jofi.setup.application.SetModelPriceUseCase
import io.github.scriptibus.jofi.setup.domain.ModelPriceInput
import io.github.scriptibus.jofi.setup.domain.ModelPriceOverride
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupViolationKind
import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import io.github.scriptibus.jofi.shared.domain.Actor
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.annotation.JsonDeserialize
import java.time.Instant
import java.util.UUID

/**
 * The prices the user gives the models of an OpenAI-compatible provider (spec §3.2, ADR-0055), so their calls
 * get a cost and count toward the monthly cap. A price applies to calls recorded after it was set; the
 * cost entries already recorded keep theirs. A cloud provider answers 409 `price-not-allowed`. The model
 * name travels in the body or the query: it may contain slashes (`meta-llama/llama-3.1-8b`).
 */
@RestController
@RequestMapping("/api/setup/providers/{id}/model-prices")
class ModelPriceController(
    private val listPrices: ListModelPricesUseCase,
    private val setPrice: SetModelPriceUseCase,
    private val clearPrice: ClearModelPriceUseCase,
) {
    @GetMapping
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun listModelPrices(
        @PathVariable id: UUID,
    ): List<ModelPriceResponse> = listPrices.execute(ProviderId(id)).orThrow().map(ModelPriceResponse::from)

    /** Sets or replaces the price of the model named in the body; 0 is a price. */
    @PutMapping
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun setModelPrice(
        @PathVariable id: UUID,
        @RequestBody request: SetModelPriceRequest,
    ): ModelPriceResponse =
        ModelPriceResponse.from(setPrice.execute(ProviderId(id), request.toInput(), Actor.User).orThrow())

    /**
     * A price that is not a whole number, a string where a number belongs, or a number beyond `Long` is a
     * violation of that field (`INVALID_FORMAT`), never a coerced value: `0.15` dollars must not become a price of 0.
     * Any other unreadable body stays a plain 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun refuseUnreadablePrice(exception: HttpMessageNotReadableException): ResponseEntity<ProblemDetail> {
        val field = (exception.cause as? JacksonException)?.path?.lastOrNull()?.propertyName
        val problem =
            if (field in REQUEST_FIELDS) {
                ValidationProblem.of(
                    SetupProblems.INVALID,
                    listOf(FieldViolation(checkNotNull(field), SetupViolationKind.INVALID_FORMAT.name)),
                )
            } else {
                ErrorResponseException(
                    HttpStatus.BAD_REQUEST,
                    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Failed to read request"),
                    null,
                )
            }
        return ResponseEntity.status(problem.statusCode).body(problem.body)
    }

    /**
     * Removes the price of [model]; later calls have an unknown cost again. Succeeds when there was none.
     * Removing a setting that can be entered again destroys no data, so it takes no confirmation step
     * (like removing the monthly cap); the removal lands in the changelog.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun clearModelPrice(
        @PathVariable id: UUID,
        @RequestParam model: String,
    ) {
        clearPrice.execute(ProviderId(id), model, Actor.User).orThrow()
    }

    private companion object {
        val REQUEST_FIELDS = setOf("model", "inputMicrosPerMillion", "outputMicrosPerMillion")
    }
}

/** Body of `PUT /api/setup/providers/{id}/model-prices`: micros are millionths of a US dollar per million tokens. */
data class SetModelPriceRequest(
    @param:JsonDeserialize(using = StrictStringDeserializer::class)
    val model: String?,
    /**
     * Whole micros, 0 to 10,000,000,000 (10,000 US dollars per million tokens); 0 for a free local model. A
     * fraction (a dollar amount such as `0.15`) or a string is refused, never rounded or coerced.
     */
    @param:JsonDeserialize(using = StrictLongDeserializer::class)
    val inputMicrosPerMillion: Long?,
    @param:JsonDeserialize(using = StrictLongDeserializer::class)
    val outputMicrosPerMillion: Long?,
) {
    fun toInput(): ModelPriceInput = ModelPriceInput(model.orEmpty(), inputMicrosPerMillion, outputMicrosPerMillion)
}

/** The price the user gave a model, in micros of a US dollar per million input and output tokens. */
data class ModelPriceResponse(
    val model: String,
    val inputMicrosPerMillion: Long,
    val outputMicrosPerMillion: Long,
    val updatedAt: Instant,
) {
    companion object {
        fun from(price: ModelPriceOverride): ModelPriceResponse =
            ModelPriceResponse(
                price.model.value,
                price.inputMicrosPerMillion,
                price.outputMicrosPerMillion,
                price.updatedAt,
            )
    }
}
