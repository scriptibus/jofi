// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.persistence

import org.jooq.DSLContext
import java.util.UUID

/**
 * Seeds the companies and applications tables for the backup round trip, with values that break naive
 * dumps. Every row refers to [at] as a `timestamptz` literal.
 */
internal class BackupDomainSeeds(
    private val dsl: DSLContext,
    private val at: String,
) {
    fun seedCompanies(company: UUID) {
        dsl.execute(
            "insert into company (id, name, website, size, locations, research_notes, preference, " +
                "preference_reason, version, created_at, updated_at) values (?, 'ACME, \"Inc.\"', " +
                "'https://acme.example', 'SMALL', '{\"Berlin, Mitte\",Köln}', 'Notes\nwith lines', 'FAVOURITE', " +
                "'Nice', 3, ?::timestamptz, ?::timestamptz)",
            company,
            at,
            at,
        )
        seedContacts(company)
    }

    // A quoted email local part, CR/LF in notes, a non-ASCII phone number, several channels, NULL labels.
    private fun seedContacts(company: UUID) {
        val contact = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
        dsl.execute(
            "insert into contact (id, company_id, name, role, relationship_notes, version, created_at, updated_at) " +
                "values (?, ?, 'Jördis \"JJ\" Müller-Lüdenscheidt', 'Head of, well, \"people\"', " +
                "'Met at the meetup,\r\nsaid \"call me\";\nfollow up in Q4', 2, ?::timestamptz, ?::timestamptz)",
            contact,
            company,
            at,
            at,
        )
        dsl.execute(
            "insert into contact (id, company_id, name, version, created_at, updated_at) " +
                "values (?, null, 'Without company', 0, ?::timestamptz, ?::timestamptz)",
            UUID.fromString("00000000-0000-0000-0000-0000000000c2"),
            at,
            at,
        )
        seedChannels(contact)
        seedApplications(company, contact)
    }

    // Two-decimal amounts, BCP 47 tags, CR/LF and quotes in notes, dates, a score, NULLs, a contact link.
    private fun seedApplications(
        company: UUID,
        contact: UUID,
    ) {
        val application = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
        dsl.execute(
            "insert into application (id, company_id, title, location, remote_share, employment_type, deadline, " +
                "portal_notes, pay_min, pay_max, pay_currency, pay_period, pay_source, pay_estimate_basis, " +
                "pay_estimate_confidence, posting_language, application_language, form_of_address, tone, " +
                "decline_category, decline_reason, offer_salary, offer_salary_currency, offer_salary_period, " +
                "offer_vacation_days, offer_start_date, unread, want_score, status, version, created_at, updated_at) " +
                "values (?, ?, 'Backend \"Kotlin\", Berlin', 'Zürich, CH', 40, 'FULL_TIME', '2026-10-31', " +
                "'Login: jj,\r\nref \"A-1\";\nsee mail', 70000.50, 9999999999.99, 'EUR', 'YEAR', 'ESTIMATED', " +
                "'levels, \"senior\"', 'MEDIUM', 'gsw-CH', 'zh-Hant-TW', 'SIE', 'PROFESSIONAL', 'SALARY', " +
                "'Too low,\r\nsorry', 0.01, 'CHF', 'MONTH', 30, '2027-01-01', true, 3.5, 'REJECTED', 4, " +
                "?::timestamptz, ?::timestamptz)",
            application,
            company,
            at,
            at,
        )
        dsl.execute(
            "insert into application (id, company_id, title, created_at, updated_at) " +
                "values (?, ?, 'Minimal', ?::timestamptz, ?::timestamptz)",
            UUID.fromString("00000000-0000-0000-0000-0000000000b2"),
            company,
            at,
            at,
        )
        dsl.execute("insert into application_contact (application_id, contact_id) values (?, ?)", application, contact)
        seedStatusHistory(application)
    }

    // The first entry (no from status), a scanner with a quoted name, a rejection with CR/LF in its reason.
    private fun seedStatusHistory(application: UUID) {
        dsl.execute(
            "insert into application_status_change (application_id, from_status, to_status, reason, " +
                "decline_category, actor_kind, actor_name, changed_at) values " +
                "(?, null, 'DISCOVERED', null, null, 'SCANNER', 'Feed \"Jobs, Berlin\"', ?::timestamptz), " +
                "(?, 'DISCOVERED', 'APPLIED', 'Sent via portal; ref \"A-1\"', null, 'USER', null, ?::timestamptz), " +
                "(?, 'APPLIED', 'REJECTED', 'Too low,\r\nsorry', 'SALARY', 'EXTERNAL_CLIENT', 'mcp', ?::timestamptz)",
            application,
            at,
            application,
            at,
            application,
            at,
        )
    }

    private fun seedChannels(contact: UUID) {
        listOf(
            Triple("EMAIL", "\"jj, müller\"@acme.example", "work"),
            Triple("PHONE", "+49 (0) 30 – 123 456 ☎", null),
            Triple("WEB", "https://acme.example/team?who=jj", "profile, public"),
        ).forEachIndexed { position, (kind, value, label) ->
            dsl.execute(
                "insert into contact_channel (contact_id, position, kind, value, label) values (?, ?, ?, ?, ?)",
                contact,
                position.toShort(),
                kind,
                value,
                label,
            )
        }
    }
}
