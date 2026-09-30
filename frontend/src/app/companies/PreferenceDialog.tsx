// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { type SyntheticEvent, useState } from "react";
import { type CompanyResponse, useSetCompanyPreference } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, Dialog, Form, RefreshIcon, SegmentedControl, TextArea } from "../../ui";
import { useFieldErrors } from "../auth/useFieldErrors";
import type { ErrorDescription } from "../problems";
import { FailureMessage } from "./CompanyLoadFailure";
import { fieldErrorsOf, PREFERENCES, type Preference, preferenceLabel } from "./company";
import { storeSaved } from "./companyCache";
import { describeCompanyError, isVersionConflict } from "./companyProblems";

export interface PreferenceDialogProps {
  company: CompanyResponse;
  isOpen: boolean;
  onClose: () => void;
  /** Loads the company again after a version conflict. */
  onReload: () => Promise<CompanyResponse | undefined>;
}

/** Favourite, blacklisted or neither, with an optional reason (for "why did I block them?" later). */
export function PreferenceDialog({ company, isOpen, onClose, onReload }: PreferenceDialogProps) {
  return (
    <Dialog isOpen={isOpen} title={m.company_preference_dialog_title()} onClose={onClose}>
      {/* Mounted per opening, so it starts from the company as it is now. */}
      {isOpen ? <PreferenceForm company={company} onClose={onClose} onReload={onReload} /> : null}
    </Dialog>
  );
}

function PreferenceForm({ company, onClose, onReload }: Omit<PreferenceDialogProps, "isOpen">) {
  const queryClient = useQueryClient();
  const fieldErrors = useFieldErrors();
  const [base, setBase] = useState(company);
  const [preference, setPreference] = useState<Preference>(company.preference);
  const [reason, setReason] = useState(company.preferenceReason ?? "");
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const [conflict, setConflict] = useState(false);
  const save = useSetCompanyPreference({ mutation: { meta: { errorHandledLocally: true } } });

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFailure(null);
    setConflict(false);
    const trimmed = reason.trim();
    const data = {
      preference,
      reason: preference === "NONE" || trimmed === "" ? null : trimmed,
      basedOnVersion: base.version,
    };
    save.mutate(
      { id: base.id, data },
      {
        onSuccess: (saved) => {
          storeSaved(queryClient, saved);
          onClose();
        },
        onError: (error) => {
          if (isVersionConflict(error)) return setConflict(true);
          const fields = fieldErrorsOf(error);
          if (fields) fieldErrors.set(fields);
          else setFailure(describeCompanyError(error));
        },
      },
    );
  };

  const reload = async () => {
    const latest = await onReload();
    if (latest === undefined) return;
    setBase(latest);
    setConflict(false);
  };

  return (
    <Form onSubmit={submit} validationErrors={fieldErrors.errors} className="flex flex-col gap-4">
      {conflict ? (
        <Alert tone="error" title={m.company_conflict_title()}>
          <p>{m.company_conflict_preference()}</p>
          <Button variant="secondary" className="self-start" onPress={() => void reload()}>
            <RefreshIcon className="size-4" aria-hidden="true" />
            {m.company_conflict_reload()}
          </Button>
        </Alert>
      ) : (
        <FailureMessage failure={failure} />
      )}
      <SegmentedControl<Preference>
        label={m.company_preference_label()}
        value={preference}
        onChange={setPreference}
        options={PREFERENCES.map((value) => ({ value, label: preferenceLabel(value) }))}
      />
      {preference === "NONE" ? null : (
        <TextArea
          name="reason"
          label={m.company_preference_reason_label()}
          description={m.company_preference_reason_hint()}
          value={reason}
          onChange={fieldErrors.clearing("reason", setReason)}
          maxLength={1000}
          rows={3}
        />
      )}
      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onPress={onClose}>
          {m.confirm_cancel()}
        </Button>
        <Button type="submit" isDisabled={save.isPending}>
          {m.company_preference_save()}
        </Button>
      </div>
    </Form>
  );
}
