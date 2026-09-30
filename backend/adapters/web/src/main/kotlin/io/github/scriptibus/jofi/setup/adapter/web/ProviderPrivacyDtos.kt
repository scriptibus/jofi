// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.domain.LocalizedText
import io.github.scriptibus.jofi.setup.domain.PrivacyClaim
import io.github.scriptibus.jofi.setup.domain.PrivacyClaimStatus
import io.github.scriptibus.jofi.setup.domain.ProviderPrivacyOverview
import io.github.scriptibus.jofi.setup.domain.ProviderPrivacyView
import java.time.LocalDate

/** The API's copy of `PrivacyClaimStatus`: what the provider's pages say about one question. */
enum class PrivacyStatus {
    YES,
    ON_REQUEST,
    CONDITIONAL,
    NO,
    DEPENDS_ON_ENDPOINT,
    UNKNOWN,
    ;

    companion object {
        fun from(status: PrivacyClaimStatus): PrivacyStatus = valueOf(status.name)
    }
}

/** A text in both UI languages. */
data class LocalizedTextResponse(
    val en: String,
    val de: String,
) {
    companion object {
        fun from(text: LocalizedText) = LocalizedTextResponse(text.en, text.de)
    }
}

/** A verbatim quote from the provider's official page it links to. */
data class PrivacyEvidenceResponse(
    val source: String,
    val quote: String,
)

/** One answer, its summary for the user and the quotes it rests on (at least one). */
data class PrivacyClaimResponse(
    val status: PrivacyStatus,
    val summary: LocalizedTextResponse,
    val evidence: List<PrivacyEvidenceResponse>,
) {
    companion object {
        fun from(claim: PrivacyClaim) =
            PrivacyClaimResponse(
                PrivacyStatus.from(claim.status),
                LocalizedTextResponse.from(claim.summary),
                claim.evidence.map { PrivacyEvidenceResponse(it.source.toString(), it.quote) },
            )
    }
}

/** A provider kind's API privacy terms as read on [checkedOn]; [stale] when that is too long ago. */
data class ProviderPrivacyEntryResponse(
    val kind: AiProviderType,
    val checkedOn: LocalDate,
    val stale: Boolean,
    val zeroDataRetention: PrivacyClaimResponse,
    val noTraining: PrivacyClaimResponse,
    val dataLocation: PrivacyClaimResponse,
) {
    companion object {
        fun from(view: ProviderPrivacyView) =
            ProviderPrivacyEntryResponse(
                AiProviderType.from(view.info.provider),
                view.info.checkedOn,
                view.stale,
                PrivacyClaimResponse.from(view.info.zeroDataRetention),
                PrivacyClaimResponse.from(view.info.noTraining),
                PrivacyClaimResponse.from(view.info.dataLocation),
            )
    }
}

/** The "verify these terms yourself" notice: a message key for the UI and its text in both languages. */
data class PrivacyDisclaimerResponse(
    val key: String,
    val text: LocalizedTextResponse,
)

/** Body of `GET /api/setup/providers/privacy`: one entry per provider kind, and the disclaimer. */
data class ProviderPrivacyResponse(
    val checkedOn: LocalDate,
    val staleAfterMonths: Int,
    val disclaimer: PrivacyDisclaimerResponse,
    val providers: List<ProviderPrivacyEntryResponse>,
) {
    companion object {
        fun from(overview: ProviderPrivacyOverview) =
            ProviderPrivacyResponse(
                overview.checkedOn,
                overview.staleAfterMonths,
                PrivacyDisclaimerResponse(
                    overview.disclaimer.key,
                    LocalizedTextResponse.from(overview.disclaimer.text),
                ),
                overview.entries.map(ProviderPrivacyEntryResponse::from),
            )
    }
}
