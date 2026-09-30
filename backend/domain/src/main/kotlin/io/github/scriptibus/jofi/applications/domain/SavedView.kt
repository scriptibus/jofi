// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.text.trimmedOrNull
import java.time.Instant
import java.util.UUID

/** Identifies one saved view of the application list. */
@JvmInline
value class SavedViewId(
    val value: UUID,
) {
    /** How changelog entries refer to this view (entity type [ENTITY_TYPE]). */
    fun toEntityRef(): EntityRef = EntityRef(ENTITY_TYPE, value.toString())

    companion object {
        /** The changelog entity type of saved views; never rename it, stored entries use it. */
        const val ENTITY_TYPE = "saved_view"
    }
}

/**
 * The filters and order of the application list a view keeps: an [ApplicationSearch] without its page (ADR-0050).
 * The fields are exactly the search's; a test fails when a filter is added to one but not the other. Building one
 * runs the search's invariants, so a view never holds what the list would refuse. [company] and [contact] are ids
 * that are never checked: after a delete they match nothing, as the list does for an unknown id. [toString] leaves
 * out [text], which may quote a title.
 */
data class SavedViewFilter(
    val text: String? = null,
    val company: CompanyRef? = null,
    val contact: ContactRef? = null,
    val statuses: Set<ApplicationStatus> = emptySet(),
    val unread: Boolean? = null,
    val languages: Set<LanguageTag> = emptySet(),
    val sourceKinds: Set<SourceKind> = emptySet(),
    val created: TimeRange? = null,
    val updated: TimeRange? = null,
    val wantScore: ScoreRange? = null,
    val fitScore: ScoreRange? = null,
    val order: ApplicationOrder? = null,
) {
    init {
        toSearch()
    }

    /** The list's search with these filters, on page [page] of [size] applications. */
    fun toSearch(
        page: Int = 0,
        size: Int = ApplicationSearch.DEFAULT_SIZE,
    ): ApplicationSearch =
        ApplicationSearch(
            text,
            company,
            contact,
            statuses,
            unread,
            languages,
            sourceKinds,
            created,
            updated,
            wantScore,
            fitScore,
            order,
            page,
            size,
        )

    override fun toString(): String = "SavedViewFilter(${toSearch()})"

    /** A stored filter read back: what today's rules accept, and whether anything had to be left out. */
    data class Restored(
        val filter: SavedViewFilter,
        val adjusted: Boolean,
    )

    companion object {
        /** The filters of [search]; its page and size are not part of a view. */
        fun of(search: ApplicationSearch): SavedViewFilter =
            SavedViewFilter(
                search.text,
                search.company,
                search.contact,
                search.statuses,
                search.unread,
                search.languages,
                search.sourceKinds,
                search.created,
                search.updated,
                search.wantScore,
                search.fitScore,
                search.order,
            )

        /**
         * The tolerant reader for stored views (ADR-0050): [stored] goes through the list's own validation, and
         * each filter today's rules refuse (a range as a whole) is left out instead of failing the view, which is
         * then [Restored.adjusted]. [dropped] says the store already left out something it could not read, such as
         * a status that no longer exists.
         */
        fun restore(
            stored: ApplicationSearchInput,
            dropped: Boolean = false,
        ): Restored {
            val input = stored.copy(page = 0, size = ApplicationSearch.DEFAULT_SIZE)
            return when (val validation = input.validate()) {
                is SearchValidation.Valid -> {
                    Restored(of(validation.search), dropped)
                }

                is SearchValidation.Invalid -> {
                    val cleared = validation.violations.fold(input) { acc, violation -> acc.without(violation.field) }
                    val search = (cleared.validate() as? SearchValidation.Valid)?.search ?: ApplicationSearch()
                    Restored(of(search), adjusted = true)
                }
            }
        }

        private fun ApplicationSearchInput.without(field: SearchField): ApplicationSearchInput =
            when (field) {
                SearchField.TEXT -> copy(text = null)
                SearchField.LANGUAGES -> copy(languages = emptyList())
                SearchField.CREATED_TO -> copy(createdFrom = null, createdTo = null)
                SearchField.UPDATED_TO -> copy(updatedFrom = null, updatedTo = null)
                SearchField.WANT_MIN, SearchField.WANT_MAX -> copy(wantMin = null, wantMax = null)
                SearchField.FIT_MIN, SearchField.FIT_MAX -> copy(fitMin = null, fitMax = null)
                SearchField.PAGE, SearchField.SIZE -> this
            }
    }
}

/** A view's [name] (unique ignoring case, see [SavedView.isNamed]) and its [filter]. [toString] shows no name. */
data class SavedViewDetails(
    val name: String,
    val filter: SavedViewFilter,
) {
    init {
        require(ApplicationRules.textProblemOf(name, MAX_NAME_LENGTH) == null) { "A view name breaks an invariant" }
    }

    override fun toString(): String = "SavedViewDetails(filter=$filter)"

    companion object {
        const val MAX_NAME_LENGTH = 100
    }
}

/**
 * A named filter and order of the application list (spec §6.3) the user, the AI or an external client saved, to
 * open it again. [version] counts changes (ADR-0041). [adjusted] is never stored: it says the stored filter had
 * something today's rules refuse, which was left out when it was read ([SavedViewFilter.restore]); saving the view
 * again stores it as it is now.
 */
data class SavedView(
    val id: SavedViewId,
    val details: SavedViewDetails,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val adjusted: Boolean = false,
) {
    init {
        require(version >= INITIAL_VERSION) { "A view version must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "A view cannot be updated before it was created" }
    }

    /** The view with new [details], changed [at]; the same view if nothing changes (an adjusted view is stored). */
    fun edit(
        details: SavedViewDetails,
        at: Instant,
    ): SavedView =
        if (details == this.details && !adjusted) {
            this
        } else {
            copy(details = details, version = version + 1, updatedAt = at, adjusted = false)
        }

    /**
     * Whether this view is called [name], ignoring case (character by character, the same in every locale): names
     * are unique that way, so "Active" and "active" are one view. The database only rejects exactly equal names.
     */
    fun isNamed(name: String): Boolean = details.name.equals(name, ignoreCase = true)

    companion object {
        const val INITIAL_VERSION = 0L

        /** The confirmable operation (ADR-0039) of deleting saved views; its targets are view ids. */
        const val DELETE_OPERATION = "saved-views.delete"

        /** A new view with [details], created [at]. */
        fun create(
            id: SavedViewId,
            details: SavedViewDetails,
            at: Instant,
        ): SavedView = SavedView(id, details, INITIAL_VERSION, at, at)
    }
}

/** Where a problem with a [SavedViewInput] is: the name, or one of the filter's parameters. */
sealed interface SavedViewField {
    data object Name : SavedViewField

    data class Filter(
        val field: SearchField,
    ) : SavedViewField
}

data class SavedViewViolation(
    val field: SavedViewField,
    val problem: ApplicationProblem,
)

/** Outcome of validating a [SavedViewInput]: the details, or every violation at once. */
sealed interface SavedViewValidation {
    data class Valid(
        val details: SavedViewDetails,
    ) : SavedViewValidation

    data class Invalid(
        val violations: List<SavedViewViolation>,
    ) : SavedViewValidation {
        init {
            require(violations.isNotEmpty()) { "An invalid view names at least one violation" }
        }
    }
}

/**
 * A view as a client sent it: a [name] and the list's parameters as [filter]. [validate] normalizes the name like
 * every text (NFC, trimmed, no U+0000, at most [SavedViewDetails.MAX_NAME_LENGTH]) and validates [filter] with the
 * list's own [ApplicationSearchInput.validate], ignoring its page and size, so a view holds exactly what the list
 * accepts.
 */
data class SavedViewInput(
    val name: String,
    val filter: ApplicationSearchInput,
) {
    fun validate(): SavedViewValidation {
        val name = name.trimmedOrNull()
        val nameProblem = if (name == null) ApplicationProblem.REQUIRED else nameProblemOf(name)
        val violations = listOfNotNull(nameProblem?.let { SavedViewViolation(SavedViewField.Name, it) })
        return when (val search = filter.copy(page = 0, size = ApplicationSearch.DEFAULT_SIZE).validate()) {
            is SearchValidation.Invalid -> {
                SavedViewValidation.Invalid(
                    violations +
                        search.violations.map { SavedViewViolation(SavedViewField.Filter(it.field), it.problem) },
                )
            }

            is SearchValidation.Valid -> {
                if (name == null || nameProblem != null) {
                    SavedViewValidation.Invalid(violations)
                } else {
                    SavedViewValidation.Valid(SavedViewDetails(name, SavedViewFilter.of(search.search)))
                }
            }
        }
    }

    override fun toString(): String = "SavedViewInput(filter=$filter)"

    private fun nameProblemOf(name: String): ApplicationProblem? =
        ApplicationRules.textProblemOf(name, SavedViewDetails.MAX_NAME_LENGTH)
}
