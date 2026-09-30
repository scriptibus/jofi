// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.NOW
import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.rejects
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationValidation
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContentHash
import io.github.scriptibus.jofi.applications.domain.DescriptionInput
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotReason
import io.github.scriptibus.jofi.applications.domain.SourceDraft
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.SourceInput
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_DESCRIPTION_SNAPSHOT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_SOURCE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationDescriptionSnapshotRecord
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationSourceRecord
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.util.UUID

/**
 * `application_source` and `application_description_snapshot` on a real PostgreSQL (ADR-0041, ADR-0046):
 * their constraints mirror the domain without being stricter, each has a name, the stored hash is the one
 * the domain computes, snapshots only ever get frozen once, and everything goes with its application.
 */
class ApplicationSourceSchemaTest {
    private lateinit var dsl: DSLContext
    private lateinit var rows: ApplicationRows
    private val application = UUID.randomUUID()
    private val source = UUID.randomUUID()
    private val at = NOW.toInstant()

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        rows = ApplicationRows(dsl)
        rows.application(application, rows.company())
        insertSource(source)
    }

    @Test
    fun `every constraint has a name of its own`() {
        rows.constraintsOf("application_source") shouldBe SOURCE_CONSTRAINTS
        rows.constraintsOf("application_description_snapshot") shouldBe SNAPSHOT_CONSTRAINTS
    }

    @Test
    fun `stores every kind and reason the domain has`() {
        SourceKind.entries.forEach {
            insertSource(UUID.randomUUID()) {
                kind = it.name
                originalUrl = "https://jobs.example/1"
            }
        }
        SnapshotReason.entries.forEach { insertSnapshot("Text ${it.name}") { reason = it.name } }

        dsl.fetchCount(APPLICATION_SOURCE) shouldBe SourceKind.entries.size + 1
        dsl.fetchCount(APPLICATION_DESCRIPTION_SNAPSHOT) shouldBe SnapshotReason.entries.size
    }

    @Test
    fun `stores whatever the domain accepts, at exactly its limits`() {
        val prefix = "https://bücher.example/stellen/köln?q="
        val drafts =
            listOf(
                draft(SourceInput(SourceKind.URL, prefix + "x".repeat(WebAddress.MAX_LENGTH - prefix.length))),
                draft(SourceInput(SourceKind.SCANNER, "HTTP://my_team.example:8080/careers#open")),
                draft(SourceInput(SourceKind.MANUAL_CHAT, discoveredAt = at)),
                draft(SourceInput(SourceKind.MANUAL_CHAT, discoveredAt = ApplicationSource.EARLIEST_DISCOVERY)),
            )
        drafts.forEach { store(it) }
        // Exactly the limit in UTF-16 units, which the domain counts (the rocket is two).
        val longest = "ü".repeat(DescriptionText.MAX_LENGTH - 3) + " 🚀"
        val texts = listOf(longest, "# Stelle in İstanbul\n\n Straße, \"Kotlin\"; 𝔘𝔫𝔦𝔠𝔬𝔡𝔢 ☕", "x")

        texts.forEach { store(snapshot(it)) }

        dsl.fetchValues(APPLICATION_SOURCE.ORIGINAL_URL).filterNotNull().toSet() shouldBe
            drafts.mapNotNull { it.originalUrl?.value }.toSet()
        dsl.fetchValues(APPLICATION_DESCRIPTION_SNAPSHOT.CONTENT_HASH).toSet() shouldBe
            texts.map { DescriptionText(it).contentHash.hex }.toSet()
    }

    @Test
    fun `rejects sources the domain rejects`() {
        rejects("application_source_kind_valid") { insertSource(UUID.randomUUID()) { kind = "url" } }
        rejects("application_source_original_url_valid") {
            insertSource(UUID.randomUUID()) { originalUrl = "ftp://x.example" }
        }
        rejects("application_source_original_url_valid") {
            insertSource(UUID.randomUUID()) { originalUrl = "https://me:secret@x.example/job" }
        }
        rejects("application_source_original_url_valid") {
            insertSource(UUID.randomUUID()) { originalUrl = "https://x.example/" + "x".repeat(WebAddress.MAX_LENGTH) }
        }
        rejects("application_source_url_matches_kind") {
            insertSource(UUID.randomUUID()) {
                kind = "URL"
                originalUrl = null
            }
        }
        rejects("application_source_offline_after_discovery") {
            insertSource(UUID.randomUUID()) { offlineSince = NOW.minusSeconds(1) }
        }
        rejects("application_source_application_fk") { insertSource(UUID.randomUUID(), UUID.randomUUID()) }
        dsl.fetchCount(APPLICATION_SOURCE) shouldBe 1
    }

    @Test
    fun `rejects snapshots the domain rejects`() {
        rejects("application_description_snapshot_description_valid") { insertSnapshot(" \n") }
        rejects("application_description_snapshot_description_valid") { insertSnapshot("Text\n") }
        rejects("application_description_snapshot_description_valid") {
            insertSnapshot("x".repeat(DescriptionText.MAX_LENGTH + 1))
        }
        rejects("application_description_snapshot_content_hash_matches") {
            insertSnapshot("Text") { contentHash = "0".repeat(64) }
        }
        rejects("application_description_snapshot_reason_valid") { insertSnapshot("Text") { reason = "manual" } }
        rejects("application_description_snapshot_frozen_after_capture") {
            insertSnapshot("Text") { frozenAt = NOW.minusSeconds(1) }
        }
        rejects("application_description_snapshot_source_fk") {
            insertSnapshot("Text") { sourceId = UUID.randomUUID() }
        }
        dsl.fetchCount(APPLICATION_DESCRIPTION_SNAPSHOT) shouldBe 0
    }

    @Test
    fun `PostgreSQL cannot store U+0000, which is why the domain rejects it`() {
        shouldThrow<DataAccessException> { insertSnapshot("Back\u0000end") }
        DescriptionInput("Back\u0000end", SnapshotReason.MANUAL)
            .validate()
            .shouldBeInstanceOf<ApplicationValidation.Invalid>()
    }

    @Test
    fun `a source found after applying stores its discovery snapshot frozen at capture`() {
        val details = ApplicationDetails("Backend Engineer", CompanyRef(UUID.randomUUID()))
        val applied =
            Application
                .create(
                    ApplicationId(application),
                    details,
                    at,
                ).copy(status = ApplicationStatus.APPLIED)
        val discovery =
            DescriptionSnapshot.discovery(
                SnapshotId(UUID.randomUUID()),
                SourceId(source),
                DescriptionText("Text"),
                applied,
                at,
            )

        store(discovery)

        dsl.fetchValues(APPLICATION_DESCRIPTION_SNAPSHOT.FROZEN_AT).map { it?.toInstant() } shouldBe listOf(at)
    }

    @Test
    fun `a snapshot is frozen once and never changes otherwise`() {
        val id = insertSnapshot("Text")
        val later = NOW.plusMinutes(5)

        update(id) { frozenAt = later } shouldBe 1
        rejects("application_description_snapshot_immutable") { update(id) { frozenAt = later.plusMinutes(1) } }
        rejects("application_description_snapshot_immutable") { update(id) { frozenAt = null } }
        val open = insertSnapshot("Other text")
        rejects("application_description_snapshot_immutable") { update(open) { reason = "MANUAL" } }
        rejects("application_description_snapshot_immutable") {
            update(open) {
                frozenAt = later
                capturedAt = NOW.minusMinutes(1)
            }
        }
        dsl
            .select(APPLICATION_DESCRIPTION_SNAPSHOT.FROZEN_AT)
            .from(APPLICATION_DESCRIPTION_SNAPSHOT)
            .where(APPLICATION_DESCRIPTION_SNAPSHOT.ID.eq(id))
            .fetchSingle(APPLICATION_DESCRIPTION_SNAPSHOT.FROZEN_AT)
            ?.toInstant() shouldBe later.toInstant()
    }

    @Test
    fun `sources and their snapshots, frozen ones too, go with the application`() {
        insertSnapshot("Text") { frozenAt = NOW }
        insertSnapshot("Newer text")

        dsl.deleteFrom(APPLICATION).where(APPLICATION.ID.eq(application)).execute()

        dsl.fetchCount(APPLICATION_SOURCE) shouldBe 0
        dsl.fetchCount(APPLICATION_DESCRIPTION_SNAPSHOT) shouldBe 0
    }

    private fun draft(input: SourceInput): SourceDraft =
        input.validate(at).shouldBeInstanceOf<ApplicationValidation.Valid<SourceDraft>>().value

    private fun snapshot(text: String): DescriptionSnapshot =
        DescriptionSnapshot(
            SnapshotId(UUID.randomUUID()),
            SourceId(source),
            DescriptionText(text),
            SnapshotReason.DISCOVERY,
            at,
        )

    // What the repositories (#86, #96) will write for domain values.
    private fun store(draft: SourceDraft) {
        val stored = draft.toSource(SourceId(UUID.randomUUID()), ApplicationId(application))
        insertSource(stored.id.value) {
            kind = stored.kind.name
            originalUrl = stored.originalUrl?.value
            discoveredAt = stored.discoveredAt.atOffset(ZoneOffset.UTC)
        }
    }

    private fun store(snapshot: DescriptionSnapshot) {
        insertSnapshot(snapshot.text.value) {
            id = snapshot.id.value
            contentHash = snapshot.contentHash.hex
            reason = snapshot.reason.name
            capturedAt = snapshot.capturedAt.atOffset(ZoneOffset.UTC)
            frozenAt = snapshot.frozenAt?.atOffset(ZoneOffset.UTC)
        }
    }

    private fun insertSource(
        id: UUID,
        applicationId: UUID = application,
        customize: ApplicationSourceRecord.() -> Unit = {},
    ) {
        dsl
            .newRecord(APPLICATION_SOURCE)
            .apply {
                this.id = id
                this.applicationId = applicationId
                kind = "MANUAL_CHAT"
                discoveredAt = NOW
                customize()
            }.insert()
    }

    private fun insertSnapshot(
        text: String,
        customize: ApplicationDescriptionSnapshotRecord.() -> Unit = {},
    ): UUID {
        val record =
            dsl.newRecord(APPLICATION_DESCRIPTION_SNAPSHOT).apply {
                id = UUID.randomUUID()
                sourceId = source
                description = text
                contentHash = ContentHash.of(text).hex
                reason = "DISCOVERY"
                capturedAt = NOW
                customize()
            }
        record.insert()
        return record.id
    }

    private fun update(
        id: UUID,
        change: ApplicationDescriptionSnapshotRecord.() -> Unit,
    ): Int =
        dsl
            .fetchSingle(APPLICATION_DESCRIPTION_SNAPSHOT, APPLICATION_DESCRIPTION_SNAPSHOT.ID.eq(id))
            .apply(change)
            .update()

    private companion object {
        val SOURCE_CONSTRAINTS =
            listOf(
                "application_source_application_fk",
                "application_source_kind_valid",
                "application_source_offline_after_discovery",
                "application_source_original_url_valid",
                "application_source_pk",
                "application_source_url_matches_kind",
            )
        val SNAPSHOT_CONSTRAINTS =
            listOf(
                "application_description_snapshot_content_hash_matches",
                "application_description_snapshot_description_valid",
                "application_description_snapshot_frozen_after_capture",
                "application_description_snapshot_pk",
                "application_description_snapshot_reason_valid",
                "application_description_snapshot_source_fk",
            )
    }
}
