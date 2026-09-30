// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import java.net.URI
import java.net.URISyntaxException
import java.text.Normalizer

/** The request field a setup violation belongs to. */
enum class SetupField { DISPLAY_NAME, BASE_URL, API_KEY, MODEL, CONTEXT_WINDOW, MONTHLY_CAP, MONTH, MONTHS }

enum class SetupViolationKind {
    /** The field is required but empty. */
    REQUIRED,

    /** The text is longer than allowed. */
    TOO_LONG,

    /** Not an absolute http(s) URL with a host, or it carries user info, a query or a fragment. */
    INVALID_URL,

    /** This provider kind does not take the field (a cloud provider has a fixed endpoint). */
    NOT_ALLOWED,

    /** The number is outside its range. */
    OUT_OF_RANGE,

    /** The text is not in the expected format (e.g. a month that is not `YYYY-MM`). */
    INVALID_FORMAT,
}

data class SetupViolation(
    val field: SetupField,
    val problem: SetupViolationKind,
)

/** Result of validating untrusted setup input: the domain value, or every problem found. */
sealed interface SetupValidation<out T> {
    data class Valid<out T>(
        val value: T,
    ) : SetupValidation<T>

    data class Invalid(
        val violations: List<SetupViolation>,
    ) : SetupValidation<Nothing> {
        init {
            require(violations.isNotEmpty()) { "An invalid input names at least one violation" }
        }
    }
}

/** A provider's settings after validation; [apiKey] is null when none was entered. */
class ProviderSettings(
    val displayName: String,
    val baseUri: URI?,
    val apiKey: SecretValue?,
)

/**
 * Provider settings as the user entered them. [validate] normalizes the name to Unicode NFC and trims
 * all fields; blank optional fields are absent. For an update, [apiKey] absent keeps the stored key, so
 * the key never has to travel back to the client. Not a data class: [toString] must never print the key.
 */
class ProviderInput(
    val displayName: String,
    val baseUrl: String?,
    val apiKey: String?,
) {
    /**
     * Validates for a provider of [kind]; [keyStored] says whether the provider already has a key, which
     * then satisfies a kind that needs one.
     */
    fun validate(
        kind: ProviderKind,
        keyStored: Boolean,
    ): SetupValidation<ProviderSettings> {
        val name = Normalizer.normalize(displayName, Normalizer.Form.NFC).trim()
        val key = apiKey?.trim()?.takeIf(String::isNotEmpty)
        val url = baseUrl?.trim()?.takeIf(String::isNotEmpty)
        val baseUri = url?.let(::parseBaseUri)
        val violations =
            listOfNotNull(
                nameViolation(name),
                baseUrlViolation(kind, url, baseUri),
                SetupViolation(SetupField.API_KEY, SetupViolationKind.REQUIRED)
                    .takeIf { kind.needsApiKey && key == null && !keyStored },
                SetupViolation(SetupField.API_KEY, SetupViolationKind.TOO_LONG).takeIf { (key?.length ?: 0) > MAX_KEY },
            )
        if (violations.isNotEmpty()) return SetupValidation.Invalid(violations)
        return SetupValidation.Valid(ProviderSettings(name, baseUri, key?.let(::SecretValue)))
    }

    override fun toString(): String = "ProviderInput(displayName=$displayName, baseUrl=$baseUrl, apiKey=***)"

    private fun nameViolation(name: String): SetupViolation? =
        when {
            name.isEmpty() -> SetupViolation(SetupField.DISPLAY_NAME, SetupViolationKind.REQUIRED)
            name.length > MAX_DISPLAY_NAME -> SetupViolation(SetupField.DISPLAY_NAME, SetupViolationKind.TOO_LONG)
            else -> null
        }

    private fun baseUrlViolation(
        kind: ProviderKind,
        url: String?,
        parsed: URI?,
    ): SetupViolation? {
        val problem =
            if (url ==
                null
            ) {
                SetupViolationKind.REQUIRED.takeIf { kind.needsBaseUri }
            } else {
                urlProblem(kind, url, parsed)
            }
        return problem?.let { SetupViolation(SetupField.BASE_URL, it) }
    }

    private fun urlProblem(
        kind: ProviderKind,
        url: String,
        parsed: URI?,
    ): SetupViolationKind? =
        when {
            !kind.needsBaseUri -> SetupViolationKind.NOT_ALLOWED
            url.length > MAX_BASE_URL -> SetupViolationKind.TOO_LONG
            parsed == null -> SetupViolationKind.INVALID_URL
            else -> null
        }

    companion object {
        const val MAX_DISPLAY_NAME = 100
        const val MAX_BASE_URL = 2_000
        const val MAX_KEY = 4_096

        /** The URL as [ProviderConfig] accepts it, or null. */
        fun parseBaseUri(url: String): URI? {
            val uri =
                try {
                    URI(url)
                } catch (_: URISyntaxException) {
                    return null
                }
            return uri.takeIf { ProviderConfig.isValidBaseUri(it) }
        }
    }
}

/** The capabilities the user states for a model (source USER), before validation. */
data class CapabilityInput(
    val model: String,
    val features: Set<CapabilityName>,
    val contextWindowTokens: Int?,
) {
    fun validate(): SetupValidation<Pair<ModelName, ModelCapabilities>> {
        val name = Normalizer.normalize(model, Normalizer.Form.NFC).trim()
        val violations =
            listOfNotNull(
                SetupViolation(SetupField.MODEL, SetupViolationKind.REQUIRED).takeIf { name.isEmpty() },
                SetupViolation(SetupField.MODEL, SetupViolationKind.TOO_LONG).takeIf { name.length > MAX_MODEL_NAME },
                SetupViolation(SetupField.CONTEXT_WINDOW, SetupViolationKind.OUT_OF_RANGE)
                    .takeIf { contextWindowTokens != null && contextWindowTokens <= 0 },
            )
        if (violations.isNotEmpty()) return SetupValidation.Invalid(violations)
        val context = listOfNotNull(contextWindowTokens?.let { Capability.ContextSize(it) })
        return SetupValidation.Valid(
            ModelName(name) to ModelCapabilities(features.map { it.capability }.toSet() + context),
        )
    }

    companion object {
        const val MAX_MODEL_NAME = 200

        /** A model name for a task assignment, NFC and trimmed, or the violation. */
        fun modelName(raw: String): SetupValidation<ModelName> =
            when (val validated = CapabilityInput(raw, emptySet(), null).validate()) {
                is SetupValidation.Valid -> SetupValidation.Valid(validated.value.first)
                is SetupValidation.Invalid -> validated
            }
    }
}
