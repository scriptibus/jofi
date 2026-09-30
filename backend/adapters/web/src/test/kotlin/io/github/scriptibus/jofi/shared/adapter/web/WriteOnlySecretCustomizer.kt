// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.PropertyCustomizer

/** Documents [WriteOnlySecret] properties as `writeOnly` passwords in the API contract. */
class WriteOnlySecretCustomizer : PropertyCustomizer {
    override fun customize(
        property: Schema<*>,
        type: AnnotatedType,
    ): Schema<*> {
        if (type.ctxAnnotations.orEmpty().any { it is WriteOnlySecret }) {
            property.writeOnly(true).format("password")
        }
        return property
    }
}
