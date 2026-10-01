// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { type SyntheticEvent, useState } from "react";
import {
  getListSavedViewsQueryKey,
  listSavedViews,
  type SavedViewResponse,
  useCreateSavedView,
  useUpdateSavedView,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, Dialog, Form, RefreshIcon, TextField } from "../../ui";
import { useFieldErrors } from "../auth/useFieldErrors";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { fieldErrorsOf } from "../companies/company";
import type { ErrorDescription } from "../problems";
import type { ApplicationsSearch } from "./applicationsSearch";
import {
  describeViewError,
  filterForRename,
  isViewVersionConflict,
  MAX_VIEW_NAME_LENGTH,
  toViewFilter,
  VIEW_VIOLATION_MESSAGES,
} from "./savedViews";

/** Saving the list as a new view, or renaming a stored one. */
export type ViewNameMode = { kind: "save" } | { kind: "rename"; view: SavedViewResponse };

export interface ViewNameDialogProps {
  /** Closed while null. */
  mode: ViewNameMode | null;
  /** The list's filters and order now, which a new view stores. */
  search: ApplicationsSearch;
  onClose: () => void;
  onSaved: (view: SavedViewResponse, mode: ViewNameMode) => void;
}

/** Asks for a view's name: to save the list as a new view, or to rename one. */
export function ViewNameDialog({ mode, search, onClose, onSaved }: ViewNameDialogProps) {
  const title = mode?.kind === "rename" ? m.saved_views_rename_title() : m.saved_views_save_title();
  return (
    <Dialog isOpen={mode !== null} title={title} onClose={onClose}>
      {/* Mounted per opening, so it starts from the view as it is now. */}
      {mode ? <ViewNameForm mode={mode} search={search} onClose={onClose} onSaved={onSaved} /> : null}
    </Dialog>
  );
}

interface ViewNameFormProps extends Omit<ViewNameDialogProps, "mode"> {
  mode: ViewNameMode;
}

function ViewNameForm({ mode, search, onClose, onSaved }: ViewNameFormProps) {
  const queryClient = useQueryClient();
  const fieldErrors = useFieldErrors();
  const [base, setBase] = useState(mode.kind === "rename" ? mode.view : undefined);
  const [name, setName] = useState(base?.name ?? "");
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const [conflict, setConflict] = useState(false);
  const local = { mutation: { meta: { errorHandledLocally: true } } };
  const create = useCreateSavedView(local);
  const update = useUpdateSavedView(local);

  const saved = (view: SavedViewResponse) => {
    void queryClient.invalidateQueries({ queryKey: getListSavedViewsQueryKey() });
    onSaved(view, mode);
  };
  const failed = (error: unknown) => {
    if (isViewVersionConflict(error)) return setConflict(true);
    const nameError = fieldErrorsOf(error, VIEW_VIOLATION_MESSAGES)?.name;
    if (nameError) fieldErrors.set({ name: nameError });
    else setFailure(describeViewError(error));
  };

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFailure(null);
    setConflict(false);
    const callbacks = { onSuccess: saved, onError: failed };
    if (base === undefined)
      return create.mutate({ data: { name: name.trim(), filter: toViewFilter(search) } }, callbacks);
    const view = { name: name.trim(), filter: filterForRename(base) };
    update.mutate({ id: base.id, data: { basedOnVersion: base.version, view } }, callbacks);
  };

  /** After a conflict: the view as stored now, so the rename applies to it. */
  const reload = async () => {
    try {
      const { views } = await queryClient.fetchQuery({
        queryKey: getListSavedViewsQueryKey(),
        queryFn: () => listSavedViews(),
        staleTime: 0,
      });
      const latest = views.find((view) => view.id === base?.id);
      if (latest) setBase(latest);
      else setFailure({ message: m.saved_views_error_not_found() });
      setConflict(false);
    } catch (error) {
      setFailure(describeViewError(error));
    }
  };

  return (
    <Form onSubmit={submit} validationErrors={fieldErrors.errors} className="flex flex-col gap-4">
      {conflict ? (
        <Alert tone="error" title={m.saved_views_conflict_title()}>
          <p>{m.saved_views_conflict()}</p>
          <Button variant="secondary" className="self-start" onPress={() => void reload()}>
            <RefreshIcon className="size-4" aria-hidden="true" />
            {m.saved_views_conflict_reload()}
          </Button>
        </Alert>
      ) : (
        <FailureMessage failure={failure} />
      )}
      {mode.kind === "save" ? <p className="text-muted">{m.saved_views_save_hint()}</p> : null}
      <TextField
        name="name"
        label={m.saved_views_name_label()}
        value={name}
        onChange={fieldErrors.clearing("name", setName)}
        isRequired
        maxLength={MAX_VIEW_NAME_LENGTH}
        autoFocus
      />
      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onPress={onClose}>
          {m.confirm_cancel()}
        </Button>
        <Button type="submit" isDisabled={create.isPending || update.isPending}>
          {mode.kind === "rename" ? m.saved_views_rename_action() : m.saved_views_save_action()}
        </Button>
      </div>
    </Form>
  );
}
