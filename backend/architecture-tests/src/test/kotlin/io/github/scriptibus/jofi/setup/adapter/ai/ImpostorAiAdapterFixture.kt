// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import com.openai.client.OpenAIClient

/**
 * Known-bad: declares the AI adapter's package and uses an SDK type from its allowlist, but lives
 * in another module (here the architecture tests). The SDK exemption needs the `adapters/ai`
 * module, not just the package. Test fixture only, never production.
 */
class ImpostorAiAdapterFixture(
    val client: OpenAIClient,
)
