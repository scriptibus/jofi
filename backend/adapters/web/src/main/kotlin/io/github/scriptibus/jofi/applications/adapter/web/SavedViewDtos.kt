// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationSettings
import io.github.scriptibus.jofi.applications.domain.ApplicationSettingsInput
import io.github.scriptibus.jofi.applications.domain.SavedView
import io.github.scriptibus.jofi.applications.domain.SavedViewInput
import java.time.Instant
import java.util.UUID

/**
 * A saved view as the client sends it: a [name] (unique ignoring case, at most 100 characters) and the [filter] with
 * exactly the query parameters of `GET /api/applications` (without paging), validated by the list's own rules. A
 * violation answers 400 naming `name` or `filter.<parameter>` (`filter.wantMax`); a name another view has is `name`
 * `TAKEN`.
 */
data class SavedViewRequest(
    val name: String,
    val filter: ApplicationListQuery = ApplicationListQuery(),
) {
    fun toInput(): SavedViewInput = SavedViewInput(name, filter.toInput(0, ApplicationSearch.DEFAULT_SIZE))

    override fun toString(): String = "SavedViewRequest(filter=$filter)"
}

/** Body of `PUT /api/applications/saved-views/{id}`: name and filter, and the version they are based on. */
data class UpdateSavedViewRequest(
    val view: SavedViewRequest,
    val basedOnVersion: Long,
)

/**
 * One saved view. Open it by sending [filter] as the query parameters of `GET /api/applications`. Company and
 * contact ids may name one that was deleted meanwhile: it then matches nothing. [adjusted] means the stored filter
 * held something today's rules refuse, which was left out; saving the view again stores it as shown. [version] goes
 * back as `basedOnVersion` with the next change.
 */
data class SavedViewResponse(
    val id: UUID,
    val name: String,
    val filter: ApplicationListQuery,
    val adjusted: Boolean,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String = "SavedViewResponse(id=$id, adjusted=$adjusted, version=$version)"

    companion object {
        fun from(view: SavedView): SavedViewResponse =
            SavedViewResponse(
                view.id.value,
                view.details.name,
                ApplicationListQuery.from(view.details.filter),
                view.adjusted,
                view.version,
                view.createdAt,
                view.updatedAt,
            )
    }
}

/** JSON body of `GET /api/applications/saved-views`: every view by name. */
data class SavedViewListResponse(
    val views: List<SavedViewResponse>,
) {
    companion object {
        fun from(views: List<SavedView>): SavedViewListResponse =
            SavedViewListResponse(views.map(SavedViewResponse::from))
    }
}

/**
 * New application settings: Ghosted is suggested after [ghostedAfterWeeks] weeks without news (1 to 52), a
 * follow-up [followUpAfterDays] days after applying without a response (1 to 90). [basedOnVersion] is the `version`
 * last read (0 while the defaults apply). A value out of range answers 400 naming it (`OUT_OF_RANGE`).
 */
data class ApplicationSettingsRequest(
    val ghostedAfterWeeks: Int,
    val followUpAfterDays: Int,
    val basedOnVersion: Long,
) {
    fun toInput(): ApplicationSettingsInput = ApplicationSettingsInput(ghostedAfterWeeks, followUpAfterDays)
}

/** The application settings; [version] 0 and no [updatedAt] while the defaults (14 weeks, 14 days) apply. */
data class ApplicationSettingsResponse(
    val ghostedAfterWeeks: Int,
    val followUpAfterDays: Int,
    val version: Long,
    val updatedAt: Instant?,
) {
    companion object {
        fun from(settings: ApplicationSettings): ApplicationSettingsResponse =
            ApplicationSettingsResponse(
                settings.values.ghostedAfterWeeks,
                settings.values.followUpAfterDays,
                settings.version,
                settings.updatedAt,
            )
    }
}
