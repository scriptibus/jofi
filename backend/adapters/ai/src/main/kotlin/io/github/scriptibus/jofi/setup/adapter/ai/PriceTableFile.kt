// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.domain.LongPromptPrice
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ModelPrice
import io.github.scriptibus.jofi.setup.domain.PriceTable
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.TokenPrice
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate

/**
 * Reads the dated price table (`price-table.json` next to this class, ADR-0043). A broken file
 * fails at startup instead of metering with wrong prices; every entry names its source page and the
 * day its price was read there.
 */
object PriceTableFile {
    private const val RESOURCE = "price-table.json"

    fun load(): PriceTable {
        val stream = requireNotNull(PriceTableFile::class.java.getResourceAsStream(RESOURCE)) { "Missing $RESOURCE" }
        return stream.use { parse(it.readAllBytes().decodeToString()) }
    }

    fun parse(json: String): PriceTable {
        val root = JsonMapper.builder().build().readTree(json)
        check(root.required("currency").asString() == "USD") { "Prices are in USD (ADR-0032)" }
        return PriceTable(root.required("prices").values().flatMap(::pricesOf))
    }

    private fun pricesOf(entry: JsonNode): List<ModelPrice> {
        val provider = ProviderKind.valueOf(entry.required("provider").asString())
        val base = TokenPrice(decimal(entry, "inputPerMillion"), decimal(entry, "outputPerMillion"))
        val longPrompt = entry.get("longPrompt")?.takeUnless { it.isNull }?.let(::longPromptOf)
        val checkedOn = LocalDate.parse(entry.required("checkedOn").asString())
        val source = URI(entry.required("source").asString())
        val models = entry.required("models").values().map { ModelName(it.asString()) }
        check(models.isNotEmpty()) { "A price names at least one model" }
        return models.map { ModelPrice(provider, it, base, longPrompt, checkedOn, source) }
    }

    private fun longPromptOf(node: JsonNode): LongPromptPrice =
        LongPromptPrice(
            node.required("aboveInputTokens").asLong(),
            TokenPrice(decimal(node, "inputPerMillion"), decimal(node, "outputPerMillion")),
        )

    // Prices are strings in the file, so no binary floating point ever touches them.
    private fun decimal(
        node: JsonNode,
        field: String,
    ): BigDecimal = BigDecimal(node.required(field).asString())
}
