// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.application.CorrectModelCapabilitiesUseCase
import io.github.scriptibus.jofi.setup.application.CreateProviderUseCase
import io.github.scriptibus.jofi.setup.application.DeleteProviderUseCase
import io.github.scriptibus.jofi.setup.application.ListProviderModelsUseCase
import io.github.scriptibus.jofi.setup.application.ListProvidersUseCase
import io.github.scriptibus.jofi.setup.application.RefreshProviderModelsUseCase
import io.github.scriptibus.jofi.setup.application.UpdateProviderUseCase
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.Actor
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * AI providers and their models (spec §3.2). Only the logged-in user reaches these endpoints, and the
 * use cases refuse every other actor too. Keys go in, never out: responses say `apiKeySet` only.
 */
@RestController
@RequestMapping("/api/setup/providers")
class AiProviderController(
    private val listProviders: ListProvidersUseCase,
    private val createProvider: CreateProviderUseCase,
    private val updateProvider: UpdateProviderUseCase,
    private val deleteProvider: DeleteProviderUseCase,
    private val refreshModels: RefreshProviderModelsUseCase,
    private val listModels: ListProviderModelsUseCase,
    private val correctCapabilities: CorrectModelCapabilitiesUseCase,
) {
    @GetMapping
    fun listProviders(): List<ProviderResponse> = listProviders.execute().orThrow().map(ProviderResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun createProvider(
        @RequestBody request: CreateProviderRequest,
    ): ProviderResponse =
        ProviderResponse.from(createProvider.execute(request.kind.toDomain(), request.toInput(), Actor.User).orThrow())

    /** Replaces name and base URL; the stored key stays unless the request carries a new one. */
    @PutMapping("/{id}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND)
    fun updateProvider(
        @PathVariable id: UUID,
        @RequestBody request: UpdateProviderRequest,
    ): ProviderResponse =
        ProviderResponse.from(updateProvider.execute(ProviderId(id), request.toInput(), Actor.User).orThrow())

    /**
     * Two steps (ADR-0039): the first call answers 428 with a token, the repeat with it removes the
     * provider, its key and its models. 409 `provider-in-use` while tasks are assigned to it.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun deleteProvider(
        @PathVariable id: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ) {
        deleteProvider
            .execute(ProviderId(id), Confirmations.requester(request), Confirmations.token(confirmation))
            .orThrow()
    }

    /**
     * The connection test: lists the provider's models through the guarded transport and stores what
     * they can do. A provider failure answers 502 with the reason in the problem type.
     */
    @PostMapping("/{id}/models/refresh")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun refreshProviderModels(
        @PathVariable id: UUID,
    ): List<ModelResponse> = refreshModels.execute(ProviderId(id), Actor.User).orThrow().map(ModelResponse::from)

    @GetMapping("/{id}/models")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun listProviderModels(
        @PathVariable id: UUID,
    ): List<ModelResponse> = listModels.execute(ProviderId(id)).orThrow().map(ModelResponse::from)

    /** Stores what the user says a model can do; later refreshes keep it. The model name is in the body. */
    @PutMapping("/{id}/models")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND)
    fun correctModelCapabilities(
        @PathVariable id: UUID,
        @RequestBody request: CorrectModelCapabilitiesRequest,
    ): ModelResponse =
        ModelResponse.from(correctCapabilities.execute(ProviderId(id), request.toInput(), Actor.User).orThrow())
}

/** The value, or the failure's problem thrown for Spring to answer. */
internal fun <T> SetupResult<T>.orThrow(): T =
    when (this) {
        is SetupResult.Success -> value
        is SetupResult.Failure -> throw SetupProblems.of(this)
    }
