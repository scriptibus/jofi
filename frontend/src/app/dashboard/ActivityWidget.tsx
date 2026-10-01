// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type ActivityEntryResponse, useListRecentActivity } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { TextLink } from "../../ui";
import { formatInstant, formatRelative } from "../applications/format";
import { actorLabels } from "../applications/labels";
import { ACTIVITY_LIMIT, describeActivity } from "./dashboard";
import { Widget, WidgetContent, widgetQuery } from "./Widget";

/**
 * The newest changes of the job search (ADR-0052), newest first: what happened, who did it, when, and the
 * application it is about as a link to its timeline, where the details are.
 */
export function ActivityWidget({ className }: { className?: string }) {
  const query = useListRecentActivity({ limit: ACTIVITY_LIMIT }, { query: widgetQuery });
  return (
    <Widget
      id="dashboard-activity"
      title={m.dashboard_activity_heading()}
      {...(className ? { className } : {})}
    >
      <WidgetContent query={query} failed={m.dashboard_activity_failed()}>
        {({ entries }) =>
          entries.length === 0 ? (
            <p className="text-muted">{m.dashboard_activity_empty()}</p>
          ) : (
            <ul aria-labelledby="dashboard-activity" className="flex flex-col divide-y divide-line">
              {entries.map((entry) => (
                <ActivityItem key={entry.id} entry={entry} />
              ))}
            </ul>
          )
        }
      </WidgetContent>
    </Widget>
  );
}

function ActivityItem({ entry }: { entry: ActivityEntryResponse }) {
  const { actor, application, occurredAt } = entry;
  const kind = actorLabels[actor.kind]();
  const who = actor.name ? m.application_actor_named({ actor: kind, name: actor.name }) : kind;
  return (
    <li className="flex flex-col gap-1 py-3 first:pt-0 last:pb-0 sm:flex-row sm:items-baseline sm:justify-between sm:gap-4">
      <div className="flex min-w-0 flex-col">
        <span className="font-medium">{describeActivity(entry)}</span>
        {application ? (
          <TextLink
            to="/applications/$applicationId"
            params={{ applicationId: application.id }}
            search={{ tab: "timeline" }}
            className="self-start truncate"
          >
            {application.title}
          </TextLink>
        ) : null}
      </div>
      <span className="shrink-0 text-muted">
        {m.application_status_history_by({ actor: who })} ·{" "}
        <time dateTime={occurredAt} title={formatInstant(occurredAt)}>
          {formatRelative(occurredAt)}
        </time>
      </span>
    </li>
  );
}
