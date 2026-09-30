// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

// Request bodies carry passwords and tokens: their toString() hides them, so a debug log of the
// request (Spring MVC logs bodies at DEBUG) never shows them (threat model T4).

/** Body of `POST /api/auth/login`. There is one user, so there is no user name. */
data class LoginRequest(
    val password: String,
) {
    override fun toString(): String = "LoginRequest(password=***)"
}

/** Body of `POST /api/auth/first-run`. [setupToken] is needed only when Jofi is exposed on a network. */
data class FirstRunRequest(
    val password: String,
    val setupToken: String? = null,
) {
    override fun toString(): String = "FirstRunRequest(password=***, setupToken=***)"
}

/** Body of `PUT /api/auth/password`. */
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
) {
    override fun toString(): String = "ChangePasswordRequest(currentPassword=***, newPassword=***)"
}

/** Body of `GET /api/auth/session`: what the SPA shows before and after login. */
data class AuthSessionResponse(
    /** A password exists; `false` means first run comes next. */
    val setUp: Boolean,
    /** This request belongs to a logged-in session. */
    val authenticated: Boolean,
    /** First run needs the setup token from the data volume. */
    val setupTokenRequired: Boolean,
)
