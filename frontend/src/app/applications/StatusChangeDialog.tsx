// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { type SyntheticEvent, useState } from "react";
import { type ApplicationResponse, useChangeApplicationStatus } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, Dialog, Form, RefreshIcon, Select, TextArea } from "../../ui";
import { useFieldErrors } from "../auth/useFieldErrors";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { fieldErrorsOf } from "../companies/company";
import type { ErrorDescription } from "../problems";
import { storeStatusChange } from "./applicationCache";
import {
  describeApplicationError,
  isApplicationVersionConflict,
  isInvalidTransition,
} from "./applicationProblems";
import { type DeclineCategory, declineCategoryLabels, optionsOf, type Status, statusLabels } from "./labels";
import { takesDeclineReason } from "./statusMatrix";

/** As long as the server keeps a status change reason (backend `StatusChange.MAX_REASON_LENGTH`). */
const MAX_REASON_LENGTH = 5000;

export interface StatusChangeDialogProps {
  application: ApplicationResponse;
  /** The status to move to (the current one to correct the decline reason), or null while closed. */
  target: Status | null;
  onClose: () => void;
  /** Loads the application (and its history) again after it changed elsewhere. */
  onReload: () => void;
}

/**
 * Moves an application to another status (ADR-0044), with an optional reason; Declined and Rejected also
 * need a decline category. The same dialog corrects the reason of a Declined or Rejected application.
 * Written for any page that changes a status, the detail page and the Kanban board (#36).
 */
export function StatusChangeDialog({ application, target, onClose, onReload }: StatusChangeDialogProps) {
  const correcting = target === application.status;
  const title =
    target === null || correcting
      ? m.application_status_correct_title()
      : m.application_status_dialog_title({ status: statusLabels[target]() });
  return (
    <Dialog isOpen={target !== null} title={title} onClose={onClose}>
      {/* Mounted per opening, so it starts from the application as it is now. */}
      {target === null ? null : (
        <StatusChangeForm application={application} target={target} onClose={onClose} onReload={onReload} />
      )}
    </Dialog>
  );
}

type Problem = { kind: "conflict" } | { kind: "invalid" } | { kind: "other"; failure: ErrorDescription };

function StatusChangeForm({
  application,
  target,
  onClose,
  onReload,
}: Omit<StatusChangeDialogProps, "target"> & { target: Status }) {
  const queryClient = useQueryClient();
  const fieldErrors = useFieldErrors();
  const needsCategory = takesDeclineReason(target);
  const correcting = target === application.status;
  const current = correcting ? application.declineReason : null;
  const [category, setCategory] = useState<DeclineCategory | null>(current?.category ?? null);
  const [reason, setReason] = useState(current?.text ?? "");
  const [problem, setProblem] = useState<Problem | null>(null);
  const change = useChangeApplicationStatus({ mutation: { meta: { errorHandledLocally: true } } });

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    setProblem(null);
    if (needsCategory && category === null) {
      fieldErrors.set({ declineCategory: m.application_status_category_required() });
      return;
    }
    const trimmed = reason.trim();
    const data = {
      status: target,
      reason: trimmed === "" ? null : trimmed,
      declineCategory: needsCategory ? category : null,
      basedOnVersion: application.version,
    };
    change.mutate(
      { id: application.id, data },
      {
        onSuccess: (saved) => {
          storeStatusChange(queryClient, saved);
          onClose();
        },
        onError: (error) => {
          if (isApplicationVersionConflict(error)) return setProblem({ kind: "conflict" });
          if (isInvalidTransition(error)) return setProblem({ kind: "invalid" });
          const fields = fieldErrorsOf(error);
          if (fields) fieldErrors.set(fields);
          else setProblem({ kind: "other", failure: describeApplicationError(error) });
        },
      },
    );
  };

  return (
    <Form onSubmit={submit} validationErrors={fieldErrors.errors} className="flex flex-col gap-4">
      <Feedback
        problem={problem}
        from={application.status}
        to={target}
        onReload={() => {
          onReload();
          onClose();
        }}
      />
      {needsCategory ? (
        <Select
          name="declineCategory"
          label={m.application_decline_reason()}
          placeholder={m.application_status_category_placeholder()}
          groups={[{ id: "categories", options: optionsOf(declineCategoryLabels) }]}
          value={category}
          onChange={fieldErrors.clearing("declineCategory", (id: string) =>
            setCategory(id as DeclineCategory),
          )}
        />
      ) : null}
      <TextArea
        name="reason"
        label={needsCategory ? m.application_status_detail_label() : m.application_status_reason_label()}
        description={m.company_field_markdown_hint()}
        value={reason}
        onChange={fieldErrors.clearing("reason", setReason)}
        maxLength={MAX_REASON_LENGTH}
        rows={3}
      />
      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onPress={onClose}>
          {m.confirm_cancel()}
        </Button>
        <Button type="submit" isDisabled={change.isPending}>
          {correcting ? m.application_status_correct_submit() : m.application_status_submit()}
        </Button>
      </div>
    </Form>
  );
}

interface FeedbackProps {
  problem: Problem | null;
  from: Status;
  to: Status;
  onReload: () => void;
}

/** Why the move did not happen; after a change elsewhere, a button that loads the latest version. */
function Feedback({ problem, from, to, onReload }: FeedbackProps) {
  if (problem === null) return null;
  if (problem.kind === "other") return <FailureMessage failure={problem.failure} />;
  const text =
    problem.kind === "conflict"
      ? m.application_status_conflict()
      : m.application_status_invalid_transition({ from: statusLabels[from](), to: statusLabels[to]() });
  return (
    <Alert tone="error" title={m.company_conflict_title()}>
      <p>{text}</p>
      <Button variant="secondary" className="self-start" onPress={onReload}>
        <RefreshIcon className="size-4" aria-hidden="true" />
        {m.company_conflict_reload()}
      </Button>
    </Alert>
  );
}
