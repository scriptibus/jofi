// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  type ChangeActorDto,
  type StatusChangeResponse,
  useGetApplicationStatusHistory,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Button, Markdown } from "../../ui";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { describeApplicationError } from "./applicationProblems";
import { formatInstant, formatRelative } from "./format";
import { actorLabels, declineCategoryLabels } from "./labels";
import { StatusBadge } from "./StatusBadge";

/** "by you", "by an external client (Claude Desktop)"; the name is the client's own, shown as text. */
function actorText(actor: ChangeActorDto): string {
  const kind = actorLabels[actor.kind]();
  return m.application_status_history_by({
    actor: actor.name ? m.application_actor_named({ actor: kind, name: actor.name }) : kind,
  });
}

/** Every status change of the application, oldest first, with who made it, when and why. */
export function StatusHistory({ applicationId }: { applicationId: string }) {
  const history = useGetApplicationStatusHistory(applicationId, {
    query: { meta: { errorHandledLocally: true } },
  });
  if (history.isError) {
    return (
      <>
        <FailureMessage
          failure={{
            ...describeApplicationError(history.error),
            message: m.application_status_history_failed(),
          }}
        />
        <Button variant="secondary" className="self-start" onPress={() => void history.refetch()}>
          {m.error_retry()}
        </Button>
      </>
    );
  }
  if (history.data === undefined) return <p role="status">{m.loading()}</p>;
  const now = new Date();
  return (
    <ol className="flex flex-col divide-y divide-line">
      {history.data.changes.map((change) => (
        <HistoryEntry key={`${change.at}-${change.to}`} change={change} now={now} />
      ))}
    </ol>
  );
}

function HistoryEntry({ change, now }: { change: StatusChangeResponse; now: Date }) {
  return (
    <li className="flex flex-col gap-1 py-3">
      <p className="flex flex-wrap items-center gap-2">
        {change.from ? (
          <>
            <StatusBadge status={change.from} bare />
            <span aria-hidden="true">→</span>
            <span className="sr-only">{m.application_status_history_to()}</span>
          </>
        ) : (
          <span>{m.application_status_history_initial()}</span>
        )}
        <StatusBadge status={change.to} bare />
      </p>
      <p className="text-muted">
        {actorText(change.actor)} · <time dateTime={change.at}>{formatRelative(change.at, now)}</time> ·{" "}
        <span className="font-data">{formatInstant(change.at)}</span>
      </p>
      {change.declineCategory ? (
        <p>
          <span className="font-semibold">{m.application_decline_reason()}:</span>{" "}
          {declineCategoryLabels[change.declineCategory]()}
        </p>
      ) : null}
      {change.reason ? <Markdown>{change.reason}</Markdown> : null}
    </li>
  );
}
