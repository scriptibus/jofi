// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port

import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.PostingExtraction

/**
 * Reads the fields of a job posting with AI (task `EXTRACTION`, #96, threat model T2): through `LlmPort` only, without
 * tools, with the posting delimited as data and a JSON schema for the answer. The answer is untrusted like the
 * posting: the adapter keeps only fields of the expected types, the use case validates them. Neither the text nor the
 * answer is ever logged. Never throws.
 */
interface PostingExtractionPort {
    fun extract(text: DescriptionText): PostingExtraction
}
