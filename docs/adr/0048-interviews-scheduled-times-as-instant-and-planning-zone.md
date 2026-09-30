<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0048: Interviews and calls: an aggregate of their own, scheduled as an instant plus the zone planned in

- Status: accepted
- Date: 2026-09-30
- Source: spec §6.1 (Interviews & calls), §5 (contacts' history of interactions), §10 (tasks), §12 (countdowns);
  issue #79 (M1-C2d); builds on ADR-0041 and ADR-0039

## Context

An application has interviews and calls: date and time, type, participants (contacts), preparation notes, the
user's notes afterwards, the outcome and later a link to training sessions (spec §6.1). They feed the dashboard's
"next interview" countdown, the task suggestion "prepare the day before" (#95) and the timeline. Jofi is used by one
person, but interviews are often agreed in another zone (a remote company, travel), and the server's zone says
nothing about either. The questions:

- Is an interview part of the application aggregate, and is logging one a new version of it?
- How is the start stored so that ordering and countdowns are exact and the user still sees the time that was
  agreed ("10:00 Berlin time"), also after the user's own zone changes?
- What does an API client send: an instant, or a wall-clock time and a zone?

## Decision

### An aggregate of its own, owned by the application

`Interview` (applications context) has its own id, `version`, `createdAt` and `updatedAt`, and refers to its
application by `ApplicationId` (`interview_application_fk`, `ON DELETE CASCADE`, so it goes with the application
behind that delete's confirmation). Logging, editing or deleting an interview never writes the `application` row and
keeps its `version` (ADR-0041, "no write overwrites what it does not own"), so an edit of an interview and an edit of
its application never conflict. Updates are full replacements with `basedOnVersion` like every other aggregate.
Participants are a set of `ContactRef` (at most 20) stored in the link table `interview_participant`, whose rows
cascade from both sides (ADR-0041's rule for link tables to `contact`). Deleting an interview needs the two-step
confirmation (operation `interviews.delete`). Its changelog entity type is `interview`; entries name changed fields
only, since the notes and the participants are personal data, and `toString()` prints neither.

Types are `PHONE_SCREEN`, `HR`, `TECHNICAL`, `CASE`, `ON_SITE`, `FINAL` (spec) and `OTHER` for calls none fits
(e.g. talking about the offer). The outcome is absent while the interview is to come or undecided, else `PASSED`
(next round or offer), `REJECTED`, `WITHDRAWN` or `CANCELLED` (did not take place; not "upcoming" any more).
Training sessions (M5) link to interviews through a table of their own when they exist; there is no column or
field for them now.

Events `InterviewScheduled` (every logged interview, with type and time) and `InterviewRescheduled` (the start
instant changed; both times) carry ids, types and times only. A change of the zone alone, which keeps the instant,
is no reschedule.

### The start is an instant plus the zone it was planned in

`InterviewTime(startsAt: Instant, zone: ZoneId)`, stored as `starts_at timestamptz` and `time_zone text`:

- **The instant is the truth** for ordering, "upcoming", countdowns and reminders, and it compares correctly across
  zones and daylight saving changes. It has microsecond precision (`timestamptz`) and lies between 2000-01-01 and
  2100-01-01 (exclusive), well inside what PostgreSQL and every client can hold; the table has no bound, so it is
  never stricter.
- **The zone is how it is shown**: `localStart` is the instant on the clocks of that zone, so the interview reads
  "10:00 (Europe/Berlin)" wherever the user is when they look. The zone is an IANA id (`Europe/Berlin`) or an offset
  (`+02:00`, `UTC+05:30`), as `java.time.ZoneId` parses it (case-sensitive, trimmed, at most 64 characters), stored
  as `ZoneId.id`. The database checks only "no whitespace, at most 64 characters", never the name itself: its zone
  database need not match the JVM's, so a lookup could be stricter than the domain. The schema test stores every id
  the JVM knows.
- Alternatives: an instant alone loses the agreed wall-clock time and zone; a wall-clock time plus zone as the truth
  would keep "10:00" if the zone's rules change before the interview, but needs the instant for every query anyway
  and two sources of truth. Rule changes for a zone within the few weeks before an interview are rare enough to
  accept: the instant stays, and the shown wall-clock time follows the new rules.

### Clients send the agreed wall-clock time and the zone

Requests carry `localStart` (`2026-10-05T10:00`, no offset) and `timeZone`; the domain resolves the instant
(`InterviewTime.of`). A wall-clock time a clock change skips moves forward by the gap (02:30 → 03:30), one it repeats
takes the earlier offset, as `java.time` resolves them; the response shows what was resolved. People, the AI and MCP
clients all agree on appointments this way ("Monday 10:00 Berlin time"), and no client needs a zone database to
convert. Responses carry all three: `startsAt` (the instant), `localStart` and `timeZone`. A zone Java does not know
is `400` (`timeZone`, `INVALID_TIME_ZONE`); a start out of range `400` (`localStart`, `OUT_OF_RANGE`).

### API shape

Under `/api/applications/{id}/interviews`: `GET` (the application's interviews in start order), `POST` (log one,
201), `GET /{interviewId}`, `PUT /{interviewId}` (`details` + `basedOnVersion`, 409 when stale) and
`DELETE /{interviewId}` (two steps, `Jofi-Confirmation`); plus `GET /api/interviews/upcoming` (across applications:
starting now or later, not cancelled, soonest first, at most 100, each with its application's title). An interview
the application does not have is 404 `interview-not-found`.

### Use cases (amended with #91)

- Every interview use case reads the application first, so an unknown application is `404 not-found` and an
  interview it does not have `404 interview-not-found`. An update then checks `basedOnVersion`, before the input,
  even when nothing would change; unchanged details store nothing, write no changelog entry and publish nothing.
- A participant that is no contact (`interview_participant_contact_fk`) is `400` on `participantIds` (`NOT_FOUND`).
- Changelog entries (entity `interview`) carry values only for `type`, `startsAt` (the instant), `timeZone` and
  `outcome`; changed participants, preparation notes and notes are named in the description ("also changed: …"),
  never recorded. The log and delete entries also name the `application` id, so a deleted interview's entries still
  say whose it was.
- The delete's confirmation effect is `ConfirmationEffect("interview", "<TYPE> <localStart> <zone>")`, e.g.
  `PHONE_SCREEN 2026-10-06T14:30 Europe/Berlin`, read in the delete's transaction; a reschedule between the steps
  voids the token. The application delete's effect counts the interviews it cascades to (`interviews`).

## Consequences

- Interviews, the application and other interviews never block each other's edits; the use cases (#91, #92) keep the
  application and its interviews consistent only through the foreign key.
- The dashboard, the timeline and task suggestions order by one exact instant and show the agreed time.
- The application delete's confirmation effect (#82) counts the interviews it cascades to (#91). The contact delete
  (#89) could count the interviews a contact took part in, as it does for application links; that is left to #92,
  which reads those interviews for the `ContactDeleted` changelog anyway.
- Other scheduled things (tasks with a time, M1 dashboard countdowns) can reuse this model: an instant plus the zone
  it was planned in.
