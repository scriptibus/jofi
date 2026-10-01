// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.inbound.FetchPostingTextPort
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationValidation
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.DescriptionInput
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.DisallowedPostingHosts
import io.github.scriptibus.jofi.applications.domain.PostingCharset
import io.github.scriptibus.jofi.applications.domain.PostingHtmlText
import io.github.scriptibus.jofi.applications.domain.SnapshotReason
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort
import io.github.scriptibus.jofi.shared.application.port.OutboundHttpPort
import io.github.scriptibus.jofi.shared.domain.http.FetchLimits
import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.http.FetchedResource
import io.github.scriptibus.jofi.shared.domain.http.OutboundRequest
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import java.net.URI

/**
 * Fetches a job posting through [OutboundHttpPort] (the SSRF guard) and extracts its main text (spec §8.1, #97), after
 * checking that the extraction task has a model (nothing is fetched otherwise). The page is untrusted data: it is
 * decoded with its declared character set ([PostingCharset]), turned into text by the linear [PostingHtmlText] and
 * never interpreted. Where the fetch ended counts, not only where it started: a redirect or shortener into a site Jofi
 * never scrapes is refused after the fact (nothing stored, nothing sent to the AI). Stopping before that hop would
 * need a per-hop check in `adapters/net`, which this context does not own.
 */
class FetchPostingTextUseCase(
    private val ai: CheckAiTaskAssignedPort,
    private val http: OutboundHttpPort,
) : FetchPostingTextPort {
    override fun execute(address: WebAddress): ApplicationResult<DescriptionText> =
        ai.extractionAssigned().then { fetch(address) }

    private fun fetch(address: WebAddress): ApplicationResult<DescriptionText> {
        val uri = address.toUriOrNull() ?: return invalid(ApplicationProblem.INVALID_URL)
        val request = OutboundRequest(uri, acceptedContentTypes = HTML_CONTENT_TYPES, limits = POSTING_LIMITS)
        return when (val result = http.fetch(request)) {
            is FetchResult.Success -> describe(result.resource, uri)
            else -> invalid(ApplicationProblem.UNREACHABLE)
        }
    }

    private fun describe(
        resource: FetchedResource,
        requested: URI,
    ): ApplicationResult<DescriptionText> =
        when {
            DisallowedPostingHosts.isDisallowedHost(resource.finalUri.host.orEmpty()) -> {
                invalid(ApplicationProblem.NOT_ALLOWED)
            }

            resource.redirectedToLogin(requested) -> {
                invalid(ApplicationProblem.UNREACHABLE)
            }

            else -> {
                val bytes = resource.body.bytes()
                val html = String(bytes, PostingCharset.of(resource.contentType, bytes)).removePrefix(BYTE_ORDER_MARK)
                toDescription(PostingHtmlText.extract(html))
            }
        }

    private fun toDescription(extracted: String): ApplicationResult<DescriptionText> =
        when (
            val validated =
                DescriptionInput(
                    extracted.cutAtMaxLength(),
                    SnapshotReason.DISCOVERY,
                ).validate()
        ) {
            is ApplicationValidation.Valid -> ApplicationResult.Success(validated.value)
            is ApplicationValidation.Invalid -> invalid(ApplicationProblem.UNREACHABLE)
        }

    /**
     * A redirect that ended on a login page the submitted link was not: the posting is behind a login wall, so the
     * user pastes it instead. A link that itself has `login` in its path (`/careers/login/42`) is no wall, and
     * neither is a page that answers directly; a 401 or 403 never gets here (an HTTP error is `UNREACHABLE`).
     */
    private fun FetchedResource.redirectedToLogin(requested: URI): Boolean =
        finalUri.rawPath.orEmpty() != requested.rawPath.orEmpty() &&
            finalUri.isLoginPath() &&
            !requested.isLoginPath()

    private fun URI.isLoginPath(): Boolean =
        rawPath
            .orEmpty()
            .lowercase()
            .split('/')
            .any { it in LOGIN_SEGMENTS }

    /** At most `DescriptionText.MAX_LENGTH` characters, never ending in half of a surrogate pair. */
    private fun String.cutAtMaxLength(): String =
        take(DescriptionText.MAX_LENGTH).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }

    private fun <T> invalid(problem: ApplicationProblem): ApplicationResult<T> =
        ApplicationResult.Invalid(listOf(ApplicationViolation(ApplicationField.SOURCE_URL, problem)))

    private companion object {
        val HTML_CONTENT_TYPES = setOf("text/html", "application/xhtml+xml")
        const val BYTE_ORDER_MARK = "\uFEFF"
        val LOGIN_SEGMENTS = setOf("login", "log-in", "signin", "sign-in", "sso", "authwall")

        /**
         * One mebibyte: a posting's text is capped at `DescriptionText.MAX_LENGTH` (100,000 characters), and even a
         * markup-heavy career page with inline styles and scripts is a few hundred kilobytes. A smaller cap than the
         * guard's 5 MB default keeps one fetch's memory and parsing work small.
         */
        const val POSTING_MAX_BODY_BYTES = 1L * 1024 * 1024
        val POSTING_LIMITS = FetchLimits(maxBodyBytes = POSTING_MAX_BODY_BYTES)
    }
}
