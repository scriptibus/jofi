// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type ReactNode, type SyntheticEvent, useState } from "react";
import { useExportBackup } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, Dialog, DownloadIcon, Form, TextField } from "../../ui";
import { FormFeedback } from "../auth/FormFeedback";
import { type FieldErrors, useFieldErrors } from "../auth/useFieldErrors";
import { useThrottle } from "../auth/useThrottle";
import { type ErrorDescription, isProblem, isSessionEnded, ProblemType, throttledFor } from "../problems";
import { describeBackupError } from "./backupProblems";
import { backupFileName, saveFile } from "./files";

/**
 * Settings > Backup: download everything as one zip. A backup grants full access, so the server wants
 * the current password; it lives only in the dialog's state and is gone when the dialog closes.
 */
export function ExportBackup() {
  const [open, setOpen] = useState(false);
  const [saved, setSaved] = useState<string | null>(null);
  const throttle = useThrottle();
  const fieldErrors = useFieldErrors();
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  // Kept here, not in the dialog, so a download that finishes still saves; 401 still goes to login.
  const exportBackup = useExportBackup({ mutation: { meta: { errorHandledLocally: true } } });

  const onError = (error: unknown) => {
    const wait = throttledFor(error);
    if (wait !== undefined) throttle.start(wait);
    else if (isProblem(error, ProblemType.invalidCredentials))
      fieldErrors.set({ password: m.password_change_wrong_current() });
    else if (!isSessionEnded(error)) setFailure(describeBackupError(error));
  };

  const submit = (password: string) => {
    throttle.reset();
    setFailure(null);
    fieldErrors.set({});
    exportBackup.mutate(
      { data: { password } },
      {
        onSuccess: (blob) => {
          const name = backupFileName(blob);
          saveFile(blob, name);
          setSaved(name);
          setOpen(false);
        },
        onError,
      },
    );
  };

  const close = () => {
    if (exportBackup.isPending) return;
    setOpen(false);
    setFailure(null);
    fieldErrors.set({});
  };

  return (
    <section aria-labelledby="backup-export-heading" className="flex flex-col gap-4">
      <h3 id="backup-export-heading" className="text-h3">
        {m.backup_export_heading()}
      </h3>
      <Alert tone="warning" title={m.backup_export_warning_title()}>
        <p>{m.backup_export_warning()}</p>
      </Alert>
      {saved ? <Alert tone="success">{m.backup_export_done({ name: saved })}</Alert> : null}
      <Button
        variant="secondary"
        className="self-start"
        onPress={() => {
          setSaved(null);
          setOpen(true);
        }}
      >
        <DownloadIcon className="size-4" aria-hidden="true" />
        {m.backup_export_open()}
      </Button>
      <Dialog isOpen={open} title={m.backup_export_dialog_title()} onClose={close}>
        <PasswordForm
          intro={m.backup_export_dialog_intro()}
          feedback={<FormFeedback throttle={throttle} failure={failure} />}
          errors={fieldErrors}
          pending={exportBackup.isPending}
          waiting={throttle.waitSeconds !== undefined}
          onSubmit={submit}
          onCancel={close}
        />
      </Dialog>
    </section>
  );
}

interface PasswordFormProps {
  intro: string;
  feedback: ReactNode;
  errors: FieldErrors;
  pending: boolean;
  waiting: boolean;
  onSubmit: (password: string) => void;
  onCancel: () => void;
}

/** The dialog's form; its password state ends with the dialog. */
function PasswordForm({ intro, feedback, errors, pending, waiting, onSubmit, onCancel }: PasswordFormProps) {
  const [password, setPassword] = useState("");
  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    onSubmit(password);
  };
  return (
    <Form onSubmit={submit} validationErrors={errors.errors} className="flex flex-col gap-4">
      <p className="text-muted">{intro}</p>
      {feedback}
      <TextField
        name="password"
        type="password"
        label={m.password_current_label()}
        autoComplete="current-password"
        value={password}
        onChange={errors.clearing("password", setPassword)}
        validate={(value) => (value === "" ? m.password_required() : null)}
      />
      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onPress={onCancel} isDisabled={pending}>
          {m.backup_cancel()}
        </Button>
        <Button type="submit" isDisabled={pending || waiting}>
          {pending ? m.backup_export_pending() : m.backup_export_submit()}
        </Button>
      </div>
    </Form>
  );
}
