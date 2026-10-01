// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useInfiniteQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import {
  type ChangeActorDto,
  getApplicationTimeline,
  getGetApplicationTimelineQueryKey,
  type TimelineEntryResponse,
  type TimelineEntryResponseKind,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import {
  AiIcon,
  Button,
  DescriptionIcon,
  EditIcon,
  EmptyState,
  ExternalClientIcon,
  FrozenIcon,
  type Icon,
  InterviewIcon,
  MoveIcon,
  ScannerIcon,
  SystemIcon,
  TasksIcon,
  UserIcon,
} from "../../ui";
import { sectionCard } from "../companies/RelatedRecords";
import { LoadFailure } from "./DescriptionVersions";
import { formatInstant, formatInstantDate, formatLocalDateTime } from "./format";
import {
  type ActorKind,
  actorBadgeLabels,
  declineCategoryLabels,
  interviewOutcomeLabels,
  interviewTypeLabels,
  snapshotReasonLabels,
} from "./labels";
import { StatusBadge } from "./StatusBadge";
import { type ChangedField, describeChangedField } from "./timelineChanges";

const kinds: Record<TimelineEntryResponseKind, { icon: Icon; title: () => string }> = {
  CHANGE: { icon: EditIcon, title: m.application_timeline_kind_change },
  STATUS_CHANGE: { icon: MoveIcon, title: m.application_timeline_kind_status },
  DESCRIPTION_SNAPSHOT: { icon: DescriptionIcon, title: m.application_timeline_kind_snapshot },
  INTERVIEW: { icon: InterviewIcon, title: m.application_timeline_kind_interview },
  TASK: { icon: TasksIcon, title: m.application_timeline_kind_task },
};

const actorIcons: Record<ActorKind, Icon> = {
  USER: UserIcon,
  AI: AiIcon,
  SCANNER: ScannerIcon,
  EXTERNAL_CLIENT: ExternalClientIcon,
  SYSTEM: SystemIcon,
};

const badge =
  "inline-flex w-fit items-center gap-1.5 rounded border border-line bg-sunken px-2 py-0.5 font-semibold text-body";

/**
 * The Timeline tab (spec §6.1 changelog, §6.3): changes, status moves, saved job descriptions, interviews and
 * tasks of the application, newest first as the server orders them, a page at a time.
 */
export function TimelineTab({ applicationId }: { applicationId: string }) {
  const timeline = useInfiniteQuery({
    queryKey: getGetApplicationTimelineQueryKey(applicationId),
    queryFn: ({ pageParam, signal }) =>
      getApplicationTimeline(applicationId, pageParam === undefined ? undefined : { cursor: pageParam }, {
        signal,
      }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    meta: { errorHandledLocally: true },
  });
  if (timeline.data === undefined) {
    if (timeline.isError)
      return (
        <section aria-labelledby="application-timeline-heading" className={sectionCard}>
          <h2 id="application-timeline-heading" className="text-h2">
            {m.application_tab_timeline()}
          </h2>
          <LoadFailure
            error={timeline.error}
            message={m.application_timeline_failed()}
            onRetry={() => void timeline.refetch()}
          />
        </section>
      );
    return <p role="status">{m.loading()}</p>;
  }
  const entries = timeline.data.pages.flatMap((page) => page.entries);
  if (entries.length === 0)
    return (
      <EmptyState title={m.application_timeline_empty_heading()}>{m.application_timeline_empty()}</EmptyState>
    );
  return (
    <section aria-labelledby="application-timeline-heading" className={sectionCard}>
      <div className="flex flex-col gap-1">
        <h2 id="application-timeline-heading" className="text-h2">
          {m.application_tab_timeline()}
        </h2>
        <p className="text-muted">{m.application_timeline_order()}</p>
      </div>
      <ol aria-labelledby="application-timeline-heading" className="flex flex-col divide-y divide-line">
        {entries.map((entry) => (
          <TimelineItem key={`${entry.kind}-${entry.id}`} entry={entry} />
        ))}
      </ol>
      {timeline.isFetchNextPageError ? (
        <LoadFailure
          error={timeline.error}
          message={m.application_timeline_more_failed()}
          onRetry={() => void timeline.fetchNextPage()}
        />
      ) : timeline.hasNextPage ? (
        <Button
          variant="secondary"
          className="self-start"
          onPress={() => void timeline.fetchNextPage()}
          isDisabled={timeline.isFetchingNextPage}
        >
          {timeline.isFetchingNextPage ? m.loading() : m.application_timeline_more()}
        </Button>
      ) : null}
    </section>
  );
}

function TimelineItem({ entry }: { entry: TimelineEntryResponse }) {
  const { icon: KindIcon, title } = kinds[entry.kind];
  const actor = entry.change?.actor ?? entry.statusChange?.actor;
  return (
    <li className="flex gap-3 py-3">
      <KindIcon className="mt-0.5 size-5 shrink-0 text-muted" aria-hidden="true" />
      <div className="flex min-w-0 flex-col gap-1.5">
        <p className="font-semibold">{title()}</p>
        <EntryDetails entry={entry} />
        <p className="flex flex-wrap items-center gap-2 text-muted">
          {actor ? <ActorBadge actor={actor} /> : null}
          <EntryTime entry={entry} />
        </p>
      </div>
    </li>
  );
}

/** Who made the change: an icon and the words, never colour alone. A client's own name is shown as text. */
function ActorBadge({ actor }: { actor: ChangeActorDto }) {
  const ActorIcon = actorIcons[actor.kind];
  const kind = actorBadgeLabels[actor.kind]();
  return (
    <span className={badge}>
      <ActorIcon className="size-4" aria-hidden="true" />
      <span className="sr-only">{m.application_timeline_actor()}: </span>
      {actor.name ? m.application_actor_named({ actor: kind, name: actor.name }) : kind}
    </span>
  );
}

/** When it happened in the user's zone; an interview at its agreed time in the zone it was planned in (ADR-0048). */
function EntryTime({ entry }: { entry: TimelineEntryResponse }) {
  const interview = entry.interview;
  const text = interview
    ? m.application_timeline_interview_time({
        time: formatLocalDateTime(interview.localStart),
        zone: interview.timeZone,
      })
    : formatInstant(entry.occurredAt);
  return (
    <time dateTime={entry.occurredAt} className="font-data">
      {text}
    </time>
  );
}

function EntryDetails({ entry }: { entry: TimelineEntryResponse }): ReactNode {
  const { change, statusChange, descriptionSnapshot, interview, task } = entry;
  if (statusChange)
    return (
      <>
        <p className="flex flex-wrap items-center gap-2">
          {statusChange.from ? (
            <>
              <StatusBadge status={statusChange.from} bare />
              <span aria-hidden="true">→</span>
              <span className="sr-only">{m.application_status_history_to()}</span>
            </>
          ) : (
            <span>{m.application_status_history_initial()}</span>
          )}
          <StatusBadge status={statusChange.to} bare />
        </p>
        {statusChange.declineCategory ? (
          <p>
            <span className="font-semibold">{m.application_decline_reason()}:</span>{" "}
            {declineCategoryLabels[statusChange.declineCategory]()}
          </p>
        ) : null}
      </>
    );
  if (change) return <ChangedFields fields={change.fields.map(describeChangedField)} />;
  if (descriptionSnapshot)
    return (
      <p className="flex flex-wrap items-center gap-2">
        {snapshotReasonLabels[descriptionSnapshot.reason]()}
        {descriptionSnapshot.frozenAt ? (
          <span className={badge}>
            <FrozenIcon className="size-4" aria-hidden="true" />
            {m.application_description_frozen()}
          </span>
        ) : null}
      </p>
    );
  if (interview)
    return (
      <p>
        {interviewTypeLabels[interview.type]()}
        {interview.outcome
          ? ` · ${m.application_timeline_interview_outcome({ outcome: interviewOutcomeLabels[interview.outcome]() })}`
          : null}
      </p>
    );
  if (task)
    return (
      <p className="flex flex-wrap items-center gap-2">
        <span className="break-words">{task.title}</span>
        <span className={badge}>
          {task.completedAt
            ? m.application_timeline_task_done({ date: formatInstantDate(task.completedAt) })
            : m.application_timeline_task_open()}
        </span>
      </p>
    );
  return null;
}

function ChangedFields({ fields }: { fields: readonly ChangedField[] }) {
  if (fields.length === 0) return <p>{m.application_timeline_change_unnamed()}</p>;
  return (
    <ul className="flex flex-col gap-0.5">
      {fields.map(({ field, label, values }) => (
        <li key={field}>
          {values ? (
            <>
              <span className="font-semibold">{label}:</span> {values.before}{" "}
              <span aria-hidden="true">→</span>{" "}
              <span className="sr-only">{m.application_status_history_to()}</span> {values.after}
            </>
          ) : (
            label
          )}
        </li>
      ))}
    </ul>
  );
}
