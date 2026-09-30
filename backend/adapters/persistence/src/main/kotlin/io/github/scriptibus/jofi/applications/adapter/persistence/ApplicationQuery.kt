// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.domain.ApplicationOrder
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationSortKey
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.LanguageTag
import io.github.scriptibus.jofi.applications.domain.Score
import io.github.scriptibus.jofi.applications.domain.ScoreRange
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.applications.domain.TimeRange
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_SOURCE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import org.jooq.Condition
import org.jooq.Field
import org.jooq.SortField
import org.jooq.Table
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * The SQL of an [ApplicationSearch]: [condition] (every set filter, AND), [table] (`application`, joined with
 * `company` only to sort by its name) and [order] (ending with the id, a total order, so offset paging is
 * stable). Indexes: the title trigram index for [ApplicationSearch.text], `application_company_idx`,
 * `application_contact_contact_idx` (contact), `application_unread_idx` (unread) and
 * `application_source_application_idx` (source kinds, per application); the other filters scan `application`,
 * which a single user keeps small.
 */
internal class ApplicationQuery(
    search: ApplicationSearch,
) {
    val condition: Condition =
        DSL.and(
            listOfNotNull(
                search.text?.let(::titleMatches),
                search.company?.let { APPLICATION.COMPANY_ID.eq(it.value) },
                search.contact?.let { contact ->
                    APPLICATION.ID.`in`(
                        DSL
                            .select(APPLICATION_CONTACT.APPLICATION_ID)
                            .from(APPLICATION_CONTACT)
                            .where(APPLICATION_CONTACT.CONTACT_ID.eq(contact.value)),
                    )
                },
                search.statuses.takeIf(Set<*>::isNotEmpty)?.let { statuses ->
                    APPLICATION.STATUS.`in`(statuses.map { it.name })
                },
                search.unread?.let(APPLICATION.UNREAD::eq),
                search.languages.takeIf(Set<*>::isNotEmpty)?.let(::languageMatches),
                search.sourceKinds.takeIf(Set<*>::isNotEmpty)?.let { kinds ->
                    DSL.exists(
                        DSL
                            .selectOne()
                            .from(APPLICATION_SOURCE)
                            .where(APPLICATION_SOURCE.APPLICATION_ID.eq(APPLICATION.ID))
                            .and(APPLICATION_SOURCE.KIND.`in`(kinds.map { it.name })),
                    )
                },
                search.created?.let { within(APPLICATION.CREATED_AT, it) },
                search.updated?.let { within(APPLICATION.UPDATED_AT, it) },
                search.wantScore?.let { within(APPLICATION.WANT_SCORE, it) },
                search.fitScore?.let { within(APPLICATION.FIT_SCORE, it) },
            ),
        )

    val table: Table<*> =
        if (search.order?.key == ApplicationSortKey.COMPANY) {
            APPLICATION.join(COMPANY).on(COMPANY.ID.eq(APPLICATION.COMPANY_ID))
        } else {
            APPLICATION
        }

    val order: List<SortField<*>> =
        when (val order = search.order) {
            null -> search.text?.let(::bestMatchFirst).orEmpty() + APPLICATION.UPDATED_AT.desc()
            else -> listOf(sortField(order))
        } + APPLICATION.ID.asc()

    /**
     * Like the company name search (`NameQuery`): similar as a whole (`%`), similar to a word of the title (`<%`),
     * or contained in it (`ILIKE` with `%`, `_` and `\` escaped); pg_trgm ignores case.
     */
    private fun titleMatches(text: String): Condition {
        val value = DSL.value(text)
        return DSL
            .condition("{0} % {1}", APPLICATION.TITLE, value)
            .or(DSL.condition("{0} <% {1}", value, APPLICATION.TITLE))
            .or(APPLICATION.TITLE.likeIgnoreCase("%${text.escapedForLike()}%", LIKE_ESCAPE))
    }

    private fun bestMatchFirst(text: String): List<SortField<*>> {
        val value = DSL.value(text)
        return listOf(
            DSL.field("word_similarity({0}, {1})", Double::class.java, value, APPLICATION.TITLE).desc(),
            DSL.field("similarity({0}, {1})", Double::class.java, APPLICATION.TITLE, value).desc(),
        )
    }

    /**
     * RFC 4647 basic filtering on the effective application language: the tag itself or a longer tag it is a
     * prefix of, ignoring case. Tags are ASCII letters, digits and hyphens (checked on both sides), so
     * `lower()` and `LIKE` need no escaping and depend on no locale.
     */
    private fun languageMatches(tags: Set<LanguageTag>): Condition {
        val language = DSL.lower(DSL.coalesce(APPLICATION.APPLICATION_LANGUAGE, APPLICATION.POSTING_LANGUAGE))
        return DSL.or(
            tags.map { tag ->
                val range = tag.value.lowercase(Locale.ROOT)
                language.eq(range).or(language.like("$range-%"))
            },
        )
    }

    private fun sortField(order: ApplicationOrder): SortField<*> {
        val ascending = order.direction == SortDirection.ASCENDING
        return when (order.key) {
            ApplicationSortKey.UPDATED -> APPLICATION.UPDATED_AT.sorted(ascending)
            ApplicationSortKey.CREATED -> APPLICATION.CREATED_AT.sorted(ascending)
            ApplicationSortKey.TITLE -> APPLICATION.TITLE.sorted(ascending)
            ApplicationSortKey.COMPANY -> COMPANY.NAME.sorted(ascending)
            ApplicationSortKey.STATUS -> statusInPipelineOrder(ascending)
            ApplicationSortKey.DEADLINE -> APPLICATION.DEADLINE.sorted(ascending).nullsLast()
        }
    }

    private fun statusInPipelineOrder(ascending: Boolean): SortField<*> {
        val names = ApplicationStatus.entries.map { it.name }
        return if (ascending) APPLICATION.STATUS.sortAsc(names) else APPLICATION.STATUS.sortDesc(names)
    }

    private fun <T> Field<T>.sorted(ascending: Boolean): SortField<T> = if (ascending) asc() else desc()

    private fun within(
        field: Field<OffsetDateTime>,
        range: TimeRange,
    ): Condition =
        DSL.and(
            listOfNotNull(
                range.from?.let { field.ge(it.atOffset(ZoneOffset.UTC)) },
                range.to?.let { field.lt(it.atOffset(ZoneOffset.UTC)) },
            ),
        )

    /** A score filter never matches an application without that score (`NULL` fails every comparison). */
    private fun within(
        field: Field<BigDecimal>,
        range: ScoreRange,
    ): Condition =
        DSL.and(
            listOfNotNull(
                range.min?.let { field.ge(it.decimal()) },
                range.max?.let { field.le(it.decimal()) },
            ),
        )

    private fun Score.decimal(): BigDecimal = BigDecimal.valueOf(tenths.toLong(), 1)

    private fun String.escapedForLike(): String =
        replace("$LIKE_ESCAPE", "$LIKE_ESCAPE$LIKE_ESCAPE")
            .replace("%", "$LIKE_ESCAPE%")
            .replace("_", "${LIKE_ESCAPE}_")

    private companion object {
        const val LIKE_ESCAPE = '\\'
    }
}
