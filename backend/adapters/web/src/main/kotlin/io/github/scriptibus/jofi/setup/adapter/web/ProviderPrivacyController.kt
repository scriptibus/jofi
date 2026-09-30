// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.application.ListProviderPrivacyInfoUseCase
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * What each provider kind's official pages say about zero data retention, training and data location
 * (spec §3.2), dated and with sources, for the setup wizard (#25). Read-only; the UI always shows the
 * disclaimer with it and marks stale entries.
 */
@RestController
@RequestMapping("/api/setup/providers/privacy")
class ProviderPrivacyController(
    private val listPrivacyInfo: ListProviderPrivacyInfoUseCase,
) {
    @GetMapping
    fun listProviderPrivacyInfo(): ProviderPrivacyResponse = ProviderPrivacyResponse.from(listPrivacyInfo.execute())
}
