// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.persistence

import io.github.scriptibus.jofi.companies.domain.ChannelInput
import io.github.scriptibus.jofi.companies.domain.ChannelKind
import io.github.scriptibus.jofi.companies.domain.ContactChannel
import io.github.scriptibus.jofi.companies.domain.ContactDetails
import io.github.scriptibus.jofi.companies.domain.ContactInput
import io.github.scriptibus.jofi.companies.domain.ContactValidation
import io.github.scriptibus.jofi.companies.domain.WebAddress
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT_CHANNEL
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ContactChannelRecord
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ContactRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The `contact` and `contact_channel` tables on a real PostgreSQL migrated from zero: their
 * constraints mirror the domain invariants without being stricter, and deletes cascade from the
 * company to its contacts to their channels, so no personal data is left behind (spec §13).
 */
class ContactSchemaTest {
    private lateinit var dsl: DSLContext
    private val contactId = UUID.randomUUID()

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
    }

    @Test
    fun `stores a contact with every field and defaults the rest`() {
        val company = insertCompany()
        insertContact {
            companyId = company
            role = "Recruiter"
            relationshipNotes = "# Met at the fair"
        }
        insertChannel(0, "EMAIL", "erika@acme.example") { label = "work" }

        val stored = dsl.selectFrom(CONTACT).fetchSingle()
        stored.version shouldBe 0L
        stored.companyId shouldBe company
        dsl.fetchCount(CONTACT_CHANNEL) shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(ChannelKind::class)
    fun `stores every channel kind the domain knows`(kind: ChannelKind) {
        insertContact()
        val value =
            when (kind) {
                ChannelKind.EMAIL -> "erika@acme.example"
                ChannelKind.PHONE -> "+49 30 1234567"
                ChannelKind.WEB -> "https://www.linkedin.com/in/erika"
                ChannelKind.OTHER -> "Signal: @erika.01"
            }
        insertChannel(0, kind.name, value)

        dsl.fetchCount(CONTACT_CHANNEL) shouldBe 1
    }

    @Test
    fun `accepts every text, channel and position at exactly its domain limit`() {
        insertContact {
            name = "n".repeat(ContactDetails.MAX_NAME_LENGTH)
            role = "r".repeat(ContactDetails.MAX_ROLE_LENGTH)
            relationshipNotes = "x".repeat(ContactDetails.MAX_NOTES_LENGTH)
        }
        val email = "e".repeat(ContactChannel.MAX_EMAIL_LENGTH - "@x.example".length) + "@x.example"
        val web = "https://x.example/" + "w".repeat(WebAddress.MAX_LENGTH - "https://x.example/".length)
        val label = "l".repeat(ContactChannel.MAX_LABEL_LENGTH)
        insertChannel(0, "EMAIL", email) { this.label = label }
        insertChannel(1, "PHONE", "1".repeat(ContactChannel.MAX_PHONE_LENGTH))
        insertChannel(2, "WEB", web)
        insertChannel(ContactDetails.MAX_CHANNELS - 1, "OTHER", "o".repeat(ContactChannel.MAX_OTHER_LENGTH))

        dsl.fetchCount(CONTACT_CHANNEL) shouldBe 4
    }

    @Test
    fun `stores whatever the domain accepts`() {
        val channels =
            listOf(
                ChannelInput(ChannelKind.EMAIL, "\"erika@home\"@bücher.example", "privat"),
                ChannelInput(ChannelKind.EMAIL, "用户@例子.广告"),
                ChannelInput(ChannelKind.PHONE, "٠١٢٣٤٥٦٧٨٩"),
                ChannelInput(ChannelKind.PHONE, "+49 (0)30 1234-567 ext. 8"),
                ChannelInput(ChannelKind.WEB, "https://my_team.example/in/jörg?x=1#top", "XING"),
                ChannelInput(ChannelKind.OTHER, "Signal: @jörg.01"),
            )
        val input = ContactInput("Jörg İnce", "Personalerin", null, channels, " Straße")
        val details = input.validate().shouldBeInstanceOf<ContactValidation.Valid<ContactDetails>>().value

        insertContact {
            name = details.name
            role = details.role
            relationshipNotes = details.relationshipNotes
        }
        details.channels.forEachIndexed { position, channel ->
            insertChannel(position, channel.kind.name, channel.value) { label = channel.label }
        }

        dsl.fetchCount(CONTACT_CHANNEL) shouldBe channels.size
    }

    @Test
    fun `every constraint has a name of its own`() {
        constraintsOf("contact") shouldBe
            listOf(
                "contact_company_fk",
                "contact_name_valid",
                "contact_pk",
                "contact_relationship_notes_valid",
                "contact_role_valid",
                "contact_updated_after_created",
                "contact_version_valid",
            )
        constraintsOf("contact_channel") shouldBe
            listOf(
                "contact_channel_contact_fk",
                "contact_channel_email_valid",
                "contact_channel_kind_valid",
                "contact_channel_label_valid",
                "contact_channel_other_valid",
                "contact_channel_phone_valid",
                "contact_channel_pk",
                "contact_channel_position_valid",
                "contact_channel_value_valid",
                "contact_channel_web_valid",
            )
    }

    @Test
    fun `rejects contact texts, versions and times the domain rejects`() {
        rejects("contact_name_valid") { insertContact { name = " " } }
        rejects("contact_name_valid") { insertContact { name = "\tErika" } }
        rejects("contact_name_valid") { insertContact { name = "x".repeat(ContactDetails.MAX_NAME_LENGTH + 1) } }
        rejects("contact_role_valid") { insertContact { role = "" } }
        rejects("contact_role_valid") { insertContact { role = "x".repeat(ContactDetails.MAX_ROLE_LENGTH + 1) } }
        rejects("contact_relationship_notes_valid") { insertContact { relationshipNotes = "Notes\n" } }
        rejects("contact_relationship_notes_valid") {
            insertContact { relationshipNotes = "x".repeat(ContactDetails.MAX_NOTES_LENGTH + 1) }
        }
        rejects("contact_version_valid") { insertContact { version = -1 } }
        rejects("contact_updated_after_created") { insertContact { updatedAt = NOW.minusSeconds(1) } }
        dsl.fetchCount(CONTACT) shouldBe 0
    }

    @Test
    fun `rejects a company that does not exist and duplicate channel positions`() {
        rejects("contact_company_fk") { insertContact { companyId = UUID.randomUUID() } }
        insertContact()
        insertChannel(0, "PHONE", "030 123")
        rejects("contact_channel_pk") { insertChannel(0, "PHONE", "030 456") }
        rejects("contact_channel_contact_fk") {
            dsl
                .insertInto(CONTACT_CHANNEL)
                .set(channel(UUID.randomUUID(), 0, "PHONE", "030 123"))
                .execute()
        }
    }

    @Test
    fun `rejects channels the domain rejects`() {
        insertContact()
        rejects("contact_channel_kind_valid") { insertChannel(0, "FAX", "030 123") }
        rejects("contact_channel_position_valid") { insertChannel(-1, "PHONE", "030 123") }
        rejects("contact_channel_position_valid") { insertChannel(ContactDetails.MAX_CHANNELS, "PHONE", "030 123") }
        rejects("contact_channel_value_valid") { insertChannel(0, "OTHER", " ") }
        rejects("contact_channel_value_valid") { insertChannel(0, "OTHER", "@erika\n") }
        rejects("contact_channel_label_valid") { insertChannel(0, "OTHER", "@erika") { label = " work" } }
        rejects("contact_channel_label_valid") {
            insertChannel(0, "OTHER", "@erika") { label = "x".repeat(ContactChannel.MAX_LABEL_LENGTH + 1) }
        }
        rejects("contact_channel_phone_valid") {
            insertChannel(0, "PHONE", "1".repeat(ContactChannel.MAX_PHONE_LENGTH + 1))
        }
        rejects("contact_channel_email_valid") {
            insertChannel(0, "EMAIL", "e".repeat(ContactChannel.MAX_EMAIL_LENGTH) + "@x")
        }
        rejects("contact_channel_other_valid") {
            insertChannel(0, "OTHER", "o".repeat(ContactChannel.MAX_OTHER_LENGTH + 1))
        }
        rejects("contact_channel_value_valid") {
            insertChannel(0, "WEB", "https://x.example/" + "w".repeat(WebAddress.MAX_LENGTH))
        }
        dsl.fetchCount(CONTACT_CHANNEL) shouldBe 0
    }

    @ParameterizedTest
    @ValueSource(strings = ["erika", "@acme.example", "erika@", "erika@acme.example@"])
    fun `rejects email addresses without an @ that has text on both sides`(address: String) {
        insertContact()
        rejects("contact_channel_email_valid") { insertChannel(0, "EMAIL", address) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["linkedin.com/in/erika", "ftp://x.example", "https://user:secret@x.example"])
    fun `rejects web links that are not http(s) or carry credentials`(address: String) {
        insertContact()
        rejects("contact_channel_web_valid") { insertChannel(0, "WEB", address) }
    }

    @Test
    fun `deleting a company deletes its contacts and their channels, other contacts stay`() {
        val company = insertCompany()
        insertContact { companyId = company }
        insertChannel(0, "EMAIL", "erika@acme.example")
        val other = UUID.randomUUID()
        insertContact { id = other }

        dsl.deleteFrom(COMPANY).where(COMPANY.ID.eq(company)).execute()

        dsl.fetchValues(CONTACT.ID) shouldBe listOf(other)
        dsl.fetchCount(CONTACT_CHANNEL) shouldBe 0
    }

    @Test
    fun `deleting a contact deletes its channels`() {
        insertContact()
        insertChannel(0, "EMAIL", "erika@acme.example")
        insertChannel(1, "PHONE", "030 123")

        dsl.deleteFrom(CONTACT).where(CONTACT.ID.eq(contactId)).execute()

        dsl.fetchCount(CONTACT_CHANNEL) shouldBe 0
    }

    private fun constraintsOf(table: String): List<String> =
        dsl
            .fetchValues(
                "select conname from pg_constraint where conrelid = ?::regclass and contype <> 'n' order by conname",
                table,
            ).map(Any?::toString)

    private fun insertCompany(): UUID {
        val id = UUID.randomUUID()
        dsl
            .insertInto(COMPANY, COMPANY.ID, COMPANY.NAME, COMPANY.CREATED_AT, COMPANY.UPDATED_AT)
            .values(id, "ACME GmbH", NOW, NOW)
            .execute()
        return id
    }

    private fun insertContact(customize: ContactRecord.() -> Unit = {}) {
        dsl
            .newRecord(CONTACT)
            .apply {
                id = contactId
                name = "Erika Mustermann"
                createdAt = NOW
                updatedAt = NOW
                customize()
            }.insert()
    }

    private fun insertChannel(
        position: Int,
        kind: String,
        value: String,
        customize: ContactChannelRecord.() -> Unit = {},
    ) {
        dsl.executeInsert(channel(contactId, position, kind, value).apply(customize))
    }

    private fun channel(
        contact: UUID,
        position: Int,
        kind: String,
        value: String,
    ): ContactChannelRecord =
        ContactChannelRecord().apply {
            contactId = contact
            this.position = position.toShort()
            this.kind = kind
            this.value = value
        }

    /** The statement fails on exactly the named constraint (repositories map violations by name). */
    private fun rejects(
        constraint: String,
        statement: () -> Unit,
    ) {
        shouldThrow<DataAccessException> { statement() }.message shouldContain "\"$constraint\""
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-09-30T08:00:00Z")
    }
}
