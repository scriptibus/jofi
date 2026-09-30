// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.rejects
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The references of `application` on a real PostgreSQL (ADR-0041): a company with applications cannot
 * be deleted, and `application_contact` links go with the application, the contact or its company.
 */
class ApplicationContactSchemaTest {
    private lateinit var dsl: DSLContext
    private lateinit var rows: ApplicationRows
    private lateinit var company: UUID
    private val application = UUID.randomUUID()

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        rows = ApplicationRows(dsl)
        company = rows.company()
        rows.application(application, company)
    }

    @Test
    fun `the application's company must exist and cannot be deleted while it has applications`() {
        rejects("application_company_fk") { rows.application(UUID.randomUUID(), UUID.randomUUID()) }
        rejects("application_company_fk") { dsl.deleteFrom(COMPANY).where(COMPANY.ID.eq(company)).execute() }

        dsl.deleteFrom(APPLICATION).execute()
        dsl.deleteFrom(COMPANY).where(COMPANY.ID.eq(company)).execute() shouldBe 1
    }

    @Test
    fun `every link constraint has a name of its own`() {
        rows.constraintsOf("application_contact") shouldBe
            listOf("application_contact_application_fk", "application_contact_contact_fk", "application_contact_pk")
    }

    @Test
    fun `a contact is linked once, and only an existing one to an existing application`() {
        val contact = rows.contact(company = null)
        rows.link(application, contact)

        rejects("application_contact_pk") { rows.link(application, contact) }
        rejects("application_contact_contact_fk") { rows.link(application, UUID.randomUUID()) }
        rejects("application_contact_application_fk") { rows.link(UUID.randomUUID(), contact) }
    }

    @Test
    fun `deleting a contact or its company unlinks it, never blocked by the link`() {
        val contact = rows.contact(company = null)
        val otherCompany = rows.company()
        val contactOfOtherCompany = rows.contact(otherCompany)
        rows.link(application, contact)
        rows.link(application, contactOfOtherCompany)

        dsl.deleteFrom(COMPANY).where(COMPANY.ID.eq(otherCompany)).execute()
        dsl.fetchValues(APPLICATION_CONTACT.CONTACT_ID) shouldBe listOf(contact)

        dsl.deleteFrom(CONTACT).where(CONTACT.ID.eq(contact)).execute()
        dsl.fetchCount(APPLICATION_CONTACT) shouldBe 0
    }

    @Test
    fun `deleting an application deletes its links, the contacts stay`() {
        val contact = rows.contact(company)
        rows.link(application, contact)

        dsl.deleteFrom(APPLICATION).where(APPLICATION.ID.eq(application)).execute()

        dsl.fetchCount(APPLICATION_CONTACT) shouldBe 0
        dsl.fetchCount(CONTACT) shouldBe 1
    }
}
