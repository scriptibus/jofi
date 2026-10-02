// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer

/**
 * A JSON integer and nothing else for a `Long`: no fraction (`1.9`, `1e3`), no string (`"150000"`), no boolean.
 * Jackson coerces those by default, which would turn a dollar amount sent by mistake into a price of 0.
 * A number beyond `Long` fails in the parser; either way the property is named in the exception's path.
 */
class StrictLongDeserializer : ValueDeserializer<Long>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): Long? =
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            parser.longValue
        } else {
            context.handleUnexpectedToken(Long::class.javaObjectType, parser) as Long?
        }
}

/** A JSON string and nothing else for a `String`: `5` or `true` is not coerced into a name. */
class StrictStringDeserializer : ValueDeserializer<String>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): String? =
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            parser.string
        } else {
            context.handleUnexpectedToken(String::class.java, parser) as String?
        }
}
