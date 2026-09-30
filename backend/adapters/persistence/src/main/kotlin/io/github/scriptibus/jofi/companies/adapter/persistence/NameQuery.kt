// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.persistence

import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.ContactSearch
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import org.jooq.Condition
import org.jooq.Field
import org.jooq.SortField
import org.jooq.impl.DSL
import java.util.UUID

/**
 * The filter and order of a name search over [name], served by its trigram index (pg_trgm ignores
 * case): [text] matches a name similar as a whole (`%`), similar to a word of it (`<%`, so "acme"
 * finds "ACME Robotics GmbH"), or containing it (`ILIKE` with `%`, `_` and `\` escaped). Best match
 * first: word similarity, then similarity, then name and [id]. [filter] narrows the rows further.
 */
internal class NameQuery(
    name: Field<String>,
    id: Field<UUID>,
    text: String?,
    filter: Condition,
) {
    val condition: Condition
    val order: List<SortField<*>>

    init {
        val byName = listOf(name.asc(), id.asc())
        if (text == null) {
            condition = filter
            order = byName
        } else {
            val value = DSL.value(text)
            val matches =
                DSL
                    .condition("{0} % {1}", name, value)
                    .or(DSL.condition("{0} <% {1}", value, name))
                    .or(name.likeIgnoreCase("%${text.escapedForLike()}%", LIKE_ESCAPE))
            condition = filter.and(matches)
            order =
                listOf(
                    DSL.field("word_similarity({0}, {1})", Double::class.java, value, name).desc(),
                    DSL.field("similarity({0}, {1})", Double::class.java, name, value).desc(),
                ) + byName
        }
    }

    private fun String.escapedForLike(): String =
        replace("$LIKE_ESCAPE", "$LIKE_ESCAPE$LIKE_ESCAPE")
            .replace("%", "$LIKE_ESCAPE%")
            .replace("_", "${LIKE_ESCAPE}_")

    companion object {
        private const val LIKE_ESCAPE = '\\'

        /** Companies by name (`company_name_trgm_idx`), optionally with one preference. */
        fun of(search: CompanySearch): NameQuery =
            NameQuery(
                COMPANY.NAME,
                COMPANY.ID,
                search.text,
                search.preference?.let { COMPANY.PREFERENCE.eq(it.name) } ?: DSL.noCondition(),
            )

        /** Contacts by name (`contact_name_trgm_idx`), optionally of one company (`contact_company_idx`). */
        fun of(search: ContactSearch): NameQuery =
            NameQuery(
                CONTACT.NAME,
                CONTACT.ID,
                search.text,
                search.company?.let { CONTACT.COMPANY_ID.eq(it.value) } ?: DSL.noCondition(),
            )
    }
}
