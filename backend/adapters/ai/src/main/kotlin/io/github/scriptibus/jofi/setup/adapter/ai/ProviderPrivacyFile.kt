// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.domain.LocalizedText
import io.github.scriptibus.jofi.setup.domain.PrivacyClaim
import io.github.scriptibus.jofi.setup.domain.PrivacyClaimStatus
import io.github.scriptibus.jofi.setup.domain.PrivacyDisclaimer
import io.github.scriptibus.jofi.setup.domain.PrivacyEvidence
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.ProviderPrivacyCatalog
import io.github.scriptibus.jofi.setup.domain.ProviderPrivacyInfo
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.LocalDate

/**
 * Reads the dated provider privacy info (`provider-privacy.json` next to this class, spec §3.2, #138).
 * The file is maintained by hand from the providers' official pages, never fetched at runtime. A
 * broken file stops startup instead of showing the user a claim without its source.
 */
object ProviderPrivacyFile {
    private const val RESOURCE = "provider-privacy.json"
    private const val SCHEMA_VERSION = 1

    fun load(): ProviderPrivacyCatalog {
        val stream =
            requireNotNull(ProviderPrivacyFile::class.java.getResourceAsStream(RESOURCE)) { "Missing $RESOURCE" }
        return stream.use { parse(it.readAllBytes().decodeToString()) }
    }

    fun parse(json: String): ProviderPrivacyCatalog {
        val root = JsonMapper.builder().build().readTree(json)
        check(root.required("schemaVersion").asInt() == SCHEMA_VERSION) { "Unknown $RESOURCE schema version" }
        val disclaimer = root.required("disclaimer")
        return ProviderPrivacyCatalog(
            checkedOn = date(root),
            staleAfterMonths = root.required("staleAfterMonths").asInt(),
            disclaimer = PrivacyDisclaimer(disclaimer.required("key").asString(), text(disclaimer)),
            entries = root.required("providers").values().map(::entryOf),
        )
    }

    private fun entryOf(node: JsonNode): ProviderPrivacyInfo =
        ProviderPrivacyInfo(
            provider = ProviderKind.valueOf(node.required("provider").asString()),
            checkedOn = date(node),
            zeroDataRetention = claimOf(node.required("zeroDataRetention")),
            noTraining = claimOf(node.required("noTraining")),
            dataLocation = claimOf(node.required("dataLocation")),
        )

    private fun claimOf(node: JsonNode): PrivacyClaim =
        PrivacyClaim(
            status = PrivacyClaimStatus.valueOf(node.required("status").asString()),
            summary = text(node.required("summary")),
            evidence =
                node.required("evidence").values().map {
                    PrivacyEvidence(URI(it.required("source").asString()), it.required("quote").asString())
                },
        )

    private fun text(node: JsonNode): LocalizedText =
        LocalizedText(node.required("en").asString(), node.required("de").asString())

    private fun date(node: JsonNode): LocalDate = LocalDate.parse(node.required("checkedOn").asString())
}
