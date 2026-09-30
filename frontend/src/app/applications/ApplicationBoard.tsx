// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  type ApplicationPageResponse,
  type ApplicationResponse,
  getSearchApplicationsQueryKey,
  useChangeApplicationStatus,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, BoardColumn, Disclosure, MenuButton, MoveIcon } from "../../ui";
import type { CompanyChoices } from "../contacts/companyChoices";
import { CompanyLink, Title } from "./ApplicationList";
import { storeStatusChange } from "./applicationCache";
import {
  describeApplicationError,
  isApplicationVersionConflict,
  isInvalidTransition,
} from "./applicationProblems";
import {
  APPLICATION_DRAG_TYPE,
  boardDragData,
  canDropOn,
  draggedStatus,
  groupByStatus,
  withStatus,
} from "./board";
import { formatDate } from "./format";
import { type Status, statusLabels } from "./labels";
import { StatusChangeDialog } from "./StatusChangeDialog";
import { nextStatuses, PIPELINE, TERMINAL, takesDeclineReason } from "./statusMatrix";

type QueryKey = ReturnType<typeof getSearchApplicationsQueryKey>;

export interface ApplicationBoardProps {
  page: ApplicationPageResponse;
  /** The board's query, whose cache the optimistic move writes to. */
  queryKey: QueryKey;
  companies: CompanyChoices;
  /** The ended columns start open (e.g. when the status filter asks for an ended status). */
  showEnded: boolean;
}

/**
 * The applications as a Kanban board by status (spec §6.3): a column per pipeline status, the ended ones
 * behind a disclosure. A card moves by drag and drop or its "Move to…" menu; both offer only the moves
 * ADR-0044 allows. Declined and Rejected ask for a reason first; every other move shows at once and is
 * undone if the server refuses it.
 */
export function ApplicationBoard({ page, queryKey, companies, showEnded }: ApplicationBoardProps) {
  const { move, problem, clearProblem, announcement, reasonFor, closeReason, reload } =
    useBoardMoves(queryKey);
  const columns = groupByStatus(page.applications);
  const [endedOpen, setEndedOpen] = useState(showEnded);
  const byId = new Map(page.applications.map((application) => [application.id, application]));
  const column = (status: Status) => (
    <BoardColumn<ApplicationResponse>
      key={status}
      title={statusLabels[status]()}
      count={String(columns[status].length)}
      items={columns[status]}
      textValue={(application) => application.title}
      dragLabel={(application) => m.applications_board_drag({ title: application.title })}
      dragData={boardDragData}
      dragType={APPLICATION_DRAG_TYPE}
      canDrop={(types) => {
        const from = draggedStatus(types);
        return from !== null && canDropOn(from, status);
      }}
      onDrop={(id) => {
        const application = byId.get(id);
        if (application) move(application, status);
      }}
      renderItem={(application) => (
        <BoardCard application={application} companies={companies} onMove={move} />
      )}
      emptyText={m.applications_board_empty_column()}
    />
  );
  const ended = TERMINAL.reduce((sum, status) => sum + columns[status].length, 0);

  return (
    <div className="flex flex-col gap-4">
      <p role="status" className="sr-only">
        {announcement}
      </p>
      {problem ? (
        <Alert
          tone="error"
          title={m.applications_board_move_failed()}
          dismissLabel={m.error_dismiss()}
          onDismiss={clearProblem}
        >
          {problem}
        </Alert>
      ) : null}
      <div className="flex snap-x gap-4 overflow-x-auto pb-2">{PIPELINE.map(column)}</div>
      <Disclosure
        label={m.applications_board_ended({ count: ended })}
        isExpanded={endedOpen}
        onExpandedChange={setEndedOpen}
      >
        {/* Rendered only while open: a closed column must not be a drop target for keyboard drags. */}
        {endedOpen ? (
          <div className="flex snap-x gap-4 overflow-x-auto pb-2">{TERMINAL.map(column)}</div>
        ) : null}
      </Disclosure>
      {reasonFor ? (
        <StatusChangeDialog
          application={reasonFor.application}
          target={reasonFor.target}
          onClose={closeReason}
          onReload={reload}
        />
      ) : null}
    </div>
  );
}

interface BoardCardProps {
  application: ApplicationResponse;
  companies: CompanyChoices;
  onMove: (application: ApplicationResponse, to: Status) => void;
}

/** A card: title (with the unread dot), company, deadline, and the menu of allowed moves. */
function BoardCard({ application, companies, onMove }: BoardCardProps) {
  const { pipeline, ended } = nextStatuses(application.status);
  const actions = (statuses: Status[]) => statuses.map((id) => ({ id, label: statusLabels[id]() }));
  return (
    <>
      <div className="flex items-start justify-between gap-2">
        <span className="min-w-0 font-semibold break-words">
          <Title application={application} />
        </span>
        <MenuButton
          label={m.applications_board_move({ title: application.title })}
          groups={[
            { id: "pipeline", title: m.application_status_group_pipeline(), actions: actions(pipeline) },
            { id: "ended", title: m.application_status_group_ended(), actions: actions(ended) },
          ]}
          onAction={(id) => onMove(application, id as Status)}
        >
          <MoveIcon className="size-4" aria-hidden="true" />
        </MenuButton>
      </div>
      <span className="text-body">
        <CompanyLink application={application} companies={companies} />
      </span>
      {application.deadline ? (
        <span className="font-data text-eyebrow text-muted">
          {m.applications_deadline({ date: formatDate(application.deadline) })}
        </span>
      ) : null}
    </>
  );
}

interface ReasonRequest {
  application: ApplicationResponse;
  target: Status;
}

/**
 * The board's moves. Declined and Rejected open the reason dialog (which saves on its own). Every other
 * move is optimistic: the card changes column in the cache at once, then the server is asked; on a
 * refusal (409: changed elsewhere or no such move) the card goes back, a message says why, and the board
 * loads again.
 */
function useBoardMoves(queryKey: QueryKey) {
  const queryClient = useQueryClient();
  const [problem, setProblem] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const [reasonFor, setReasonFor] = useState<ReasonRequest | null>(null);
  const change = useChangeApplicationStatus({ mutation: { meta: { errorHandledLocally: true } } });
  const reload = () => void queryClient.invalidateQueries({ queryKey: getSearchApplicationsQueryKey() });

  const move = async (application: ApplicationResponse, to: Status) => {
    setProblem(null);
    if (takesDeclineReason(to)) return setReasonFor({ application, target: to });
    await queryClient.cancelQueries({ queryKey });
    const before = queryClient.getQueryData<ApplicationPageResponse>(queryKey);
    queryClient.setQueryData(queryKey, withStatus(before, application.id, to));
    const { id, title, status: from, version } = application;
    change.mutate(
      { id, data: { status: to, reason: null, declineCategory: null, basedOnVersion: version } },
      {
        onSuccess: (saved) => {
          setAnnouncement(m.applications_board_moved({ title, status: statusLabels[to]() }));
          storeStatusChange(queryClient, saved);
        },
        onError: (error) => {
          queryClient.setQueryData(queryKey, before);
          setProblem(refusal(error, title, from, to));
          reload();
        },
      },
    );
  };

  return {
    move,
    problem,
    clearProblem: () => setProblem(null),
    announcement,
    reasonFor,
    closeReason: () => setReasonFor(null),
    reload,
  };
}

function refusal(error: unknown, title: string, from: Status, to: Status): string {
  if (isApplicationVersionConflict(error)) return m.applications_board_conflict({ title });
  if (isInvalidTransition(error))
    return m.applications_board_invalid({ title, from: statusLabels[from](), to: statusLabels[to]() });
  return describeApplicationError(error).message;
}
