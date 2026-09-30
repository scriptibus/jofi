// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

// API enums are copies of the domain's (ADR-0041), mapped by name and tested for equal constants.

/** Copy of `TimeBucket`: a rough time relative to today in the given zone; weeks start on Monday. */
enum class TaskBucket { TODAY, THIS_WEEK, NEXT_WEEK, THIS_MONTH, SOMEDAY }

/** Copy of `BucketSpan`: how long a stored bucket lasts from its `startsOn`. */
enum class TaskSpan { DAY, WEEK, MONTH, SOMEDAY }

/** Copy of `TaskState`. */
enum class TaskStatus { SUGGESTED, OPEN, DONE, DISMISSED }

/** Copy of `TaskGroupKind`: the groups of the task list, in this order. */
enum class TaskDueGroup { OVERDUE, TODAY, THIS_WEEK, NEXT_WEEK, THIS_MONTH, LATER, SOMEDAY }

/** Copy of `CountdownKind`: what a dashboard countdown counts down to. */
enum class CountdownSource { CUSTOM, NEXT_INTERVIEW, APPLICATION_DEADLINE, OFFER_ANSWER_DEADLINE }

/** The kinds of `TaskOrigin`: created in the app, through a chat, or suggested by a rule. */
enum class TaskOriginKind { MANUAL, CHAT, SUGGESTED }

/** The kinds of `TaskLink`: what a task is about. */
enum class TaskLinkType { APPLICATION, COMPANY, CONTACT }

/** The constant of [T] with this constant's name (API enum to domain enum and back). */
internal inline fun <reified T : Enum<T>> Enum<*>.toEnum(): T = enumValueOf<T>(name)
