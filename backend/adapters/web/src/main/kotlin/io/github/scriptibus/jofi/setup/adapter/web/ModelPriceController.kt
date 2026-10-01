// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.application.ClearModelPriceUseCase
import io.github.scriptibus.jofi.setup.application.ListModelPricesUseCase
import io.github.scriptibus.jofi.setup.application.SetModelPriceUseCase
import io.github.scriptibus.jofi.setup.domain.ModelPriceInput
import io.github.scriptibus.jofi.setup.domain.ModelPriceOverride
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.Actor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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
}

/** Body of `PUT /api/setup/providers/{id}/model-prices`: micros are millionths of a US dollar per million tokens. */
data class SetModelPriceRequest(
    val model: String?,
    /** 0 to 10,000,000,000 (10,000 US dollars per million tokens); 0 for a free local model. */
    val inputMicrosPerMillion: Long?,
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
