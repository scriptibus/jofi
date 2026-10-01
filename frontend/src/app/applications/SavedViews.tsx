// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type UseQueryResult, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  deleteSavedView,
  getListSavedViewsQueryKey,
  type SavedViewListResponse,
  type SavedViewResponse,
  useListSavedViews,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { AddIcon, Alert, Button, MenuButton, MoreIcon } from "../../ui";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import type { ErrorDescription } from "../problems";
import { useConfirmation } from "../useConfirmation";
import type { ApplicationsSearch } from "./applicationsSearch";
import { DELETE_VIEW_OPERATION, describeViewError, openView } from "./savedViews";
import { ViewNameDialog, type ViewNameMode } from "./ViewNameDialog";

export interface SavedViewsProps {
  /** The list's filters and order now: what "Save view" stores. */
  search: ApplicationsSearch;
  /** Shows the list with a view's filters and order. */
  onOpen: (search: ApplicationsSearch) => void;
}

/**
 * Saved views of the list (spec §6.3, ADR-0050): store the filters and order under a name, open, rename
 * or delete one (a confirmed delete, ADR-0039). Opening a view replaces the filters and order in the URL.
 */
export function SavedViews({ search, onOpen }: SavedViewsProps) {
  const views = useListSavedViews({ query: { meta: { errorHandledLocally: true } } });
  const [naming, setNaming] = useState<ViewNameMode | null>(null);
  const [notice, setNotice] = useState("");
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const open = (view: SavedViewResponse) => {
    const opened = openView(view);
    onOpen(search.view ? { ...opened.search, view: search.view } : opened.search);
    const parts = [m.saved_views_opened({ name: view.name })];
    if (view.adjusted) parts.push(m.saved_views_adjusted());
    if (opened.leftOut) parts.push(m.saved_views_left_out());
    setNotice(parts.join(" "));
    setFailure(null);
  };

  return (
    <section aria-labelledby="saved-views-heading" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="saved-views-heading" className="text-h3">
          {m.saved_views_heading()}
        </h2>
        <Button variant="secondary" onPress={() => setNaming({ kind: "save" })}>
          <AddIcon className="size-4" aria-hidden="true" />
          {m.saved_views_save()}
        </Button>
      </div>
      <ViewList
        query={views}
        onOpen={open}
        onRename={(view) => setNaming({ kind: "rename", view })}
        onNotice={setNotice}
        onFailure={setFailure}
      />
      <FailureMessage failure={failure} />
      <p role="status" className="text-muted empty:hidden">
        {notice}
      </p>
      <ViewNameDialog
        mode={naming}
        search={search}
        onClose={() => setNaming(null)}
        onSaved={(view, mode) => {
          setNotice(
            mode.kind === "save"
              ? m.saved_views_saved({ name: view.name })
              : m.saved_views_renamed({ name: view.name }),
          );
          setNaming(null);
        }}
      />
    </section>
  );
}

interface ViewListProps {
  query: UseQueryResult<SavedViewListResponse>;
  onOpen: (view: SavedViewResponse) => void;
  onRename: (view: SavedViewResponse) => void;
  onNotice: (notice: string) => void;
  onFailure: (failure: ErrorDescription | null) => void;
}

/** The views by name; before the first answer "Loading…" or why it failed, with a retry. */
function ViewList({ query, onOpen, onRename, ...report }: ViewListProps) {
  if (!query.data) {
    if (!query.isError) return <p role="status">{m.saved_views_loading()}</p>;
    return (
      <Alert tone="error" title={m.saved_views_load_failed()}>
        <span className="flex flex-col items-start gap-3">
          {describeViewError(query.error).message}
          <Button variant="secondary" onPress={() => void query.refetch()}>
            {m.error_retry()}
          </Button>
        </span>
      </Alert>
    );
  }
  const { views } = query.data;
  if (views.length === 0) return <p className="text-muted">{m.saved_views_empty()}</p>;
  return (
    <ul aria-labelledby="saved-views-heading" className="flex flex-wrap gap-2">
      {views.map((view) => (
        <li key={view.id} className="flex items-center gap-1">
          <Button variant="secondary" onPress={() => onOpen(view)}>
            {view.name}
          </Button>
          <ViewActions view={view} onRename={onRename} {...report} />
        </li>
      ))}
    </ul>
  );
}

interface ViewActionsProps {
  view: SavedViewResponse;
  onRename: (view: SavedViewResponse) => void;
  onNotice: (notice: string) => void;
  onFailure: (failure: ErrorDescription | null) => void;
}

/** Rename or delete one view; the delete asks first with the server's effect (ADR-0039). */
function ViewActions({ view, onRename, onNotice, onFailure }: ViewActionsProps) {
  const queryClient = useQueryClient();
  const { confirmed, dialog } = useConfirmation();
  const remove = useMutation({
    meta: { errorHandledLocally: true },
    mutationFn: () =>
      confirmed((options) => deleteSavedView(view.id, options), {
        expect: { operation: DELETE_VIEW_OPERATION, targets: [view.id] },
        describe: (effect) => m.saved_views_delete_confirm({ name: effect.name }),
        title: m.saved_views_delete_title(),
        confirmLabel: m.saved_views_delete_action(),
      }),
  });

  const start = () => {
    onFailure(null);
    remove.mutate(undefined, {
      onSuccess: (outcome) => {
        if (outcome.status !== "done") return;
        onNotice(m.saved_views_deleted({ name: view.name }));
        void queryClient.invalidateQueries({ queryKey: getListSavedViewsQueryKey() });
      },
      onError: (error) => onFailure(describeViewError(error)),
    });
  };

  return (
    <>
      <MenuButton
        label={m.saved_views_actions({ name: view.name })}
        isDisabled={remove.isPending}
        groups={[
          {
            id: "actions",
            actions: [
              { id: "rename", label: m.saved_views_rename() },
              { id: "delete", label: m.saved_views_delete() },
            ],
          },
        ]}
        onAction={(id) => (id === "rename" ? onRename(view) : start())}
      >
        <MoreIcon className="size-4" aria-hidden="true" />
      </MenuButton>
      {dialog}
    </>
  );
}
