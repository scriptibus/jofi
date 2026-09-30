// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { type SyntheticEvent, useState } from "react";
import type { ConfirmationEffect } from "../../api/confirmation";
import { restoreBackup, type StagedBackupResponse, useStageBackupRestore } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, FilePicker, Form, RestoreIcon, TextField, UploadIcon } from "../../ui";
import { FormFeedback } from "../auth/FormFeedback";
import { markLoggedOut } from "../auth/session";
import { useFieldErrors } from "../auth/useFieldErrors";
import { useThrottle } from "../auth/useThrottle";
import { type ErrorDescription, isProblem, isSessionEnded, ProblemType, throttledFor } from "../problems";
import { useConfirmation } from "../useConfirmation";
import { describeBackupError } from "./backupProblems";
import { formatCount, formatDateTime } from "./files";
import { StagedBackupSummary } from "./StagedBackupSummary";

/** Operation of the restore's server-side confirmation (backend `BackupRestore.OPERATION`, ADR-0042). */
export const RESTORE_OPERATION = "system.backup.restore";

/** The confirmation text, from the server's effect (what it would really restore). */
export function describeRestore(effect: ConfirmationEffect): string {
  return m.backup_restore_confirm({
    createdAt: formatDateTime(effect.name),
    rows: formatCount(effect.counts.rows ?? 0),
    files: formatCount(effect.counts.files ?? 0),
    keyset: effect.counts.keyset
      ? m.backup_restore_confirm_keyset_yes()
      : m.backup_restore_confirm_keyset_no(),
  });
}

/**
 * Settings > Backup: upload a backup, see what it contains, then replace all data with it. The server
 * checks the upload before anything is touched, asks for the current password and the two-step
 * confirmation (ADR-0039), and ends every session; the login screen then says why.
 */
export function RestoreBackup() {
  const [fileName, setFileName] = useState("");
  const [staged, setStaged] = useState<StagedBackupResponse | null>(null);
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const stage = useStageBackupRestore({ mutation: { meta: { errorHandledLocally: true } } });

  const upload = (file: File) => {
    setFileName(file.name);
    setStaged(null);
    setFailure(null);
    stage.mutate(
      { data: file },
      {
        onSuccess: setStaged,
        onError: (error) => {
          if (!isSessionEnded(error)) setFailure(describeBackupError(error));
        },
      },
    );
  };

  return (
    <section aria-labelledby="backup-restore-heading" className="flex flex-col gap-4">
      <h3 id="backup-restore-heading" className="text-h3">
        {m.backup_restore_heading()}
      </h3>
      <Alert tone="warning" title={m.backup_restore_warning_title()}>
        <p>{m.backup_restore_warning()}</p>
      </Alert>
      <FilePicker
        acceptedFileTypes={[".zip", "application/zip"]}
        onSelect={upload}
        isDisabled={stage.isPending}
        className="self-start"
      >
        <UploadIcon className="size-4" aria-hidden="true" />
        {staged ? m.backup_restore_choose_other() : m.backup_restore_choose()}
      </FilePicker>
      {stage.isPending ? <Alert tone="info">{m.backup_restore_checking({ file: fileName })}</Alert> : null}
      {failure ? (
        <Alert tone="error" title={m.error_heading()}>
          <p>{failure.message}</p>
        </Alert>
      ) : null}
      {staged ? (
        <>
          <StagedBackupSummary backup={staged} />
          <RestoreForm key={staged.id} backupId={staged.id} />
        </>
      ) : null}
    </section>
  );
}

/** The current password and the confirmed restore of one staged backup. */
function RestoreForm({ backupId }: { backupId: string }) {
  const queryClient = useQueryClient();
  const router = useRouter();
  const throttle = useThrottle();
  const fieldErrors = useFieldErrors();
  const { confirmed, dialog } = useConfirmation();
  const [password, setPassword] = useState("");
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const [restoring, setRestoring] = useState(false);

  const restore = useMutation({
    meta: { errorHandledLocally: true },
    mutationFn: (current: string) =>
      confirmed(
        (options) => {
          // Only the second call carries the token: from here on the restore itself runs.
          if (options) setRestoring(true);
          return restoreBackup(backupId, { password: current }, options);
        },
        {
          expect: { operation: RESTORE_OPERATION, targets: [backupId] },
          describe: describeRestore,
          title: m.backup_restore_confirm_title(),
          confirmLabel: m.backup_restore_confirm_action(),
        },
      ),
  });

  const onError = (error: unknown) => {
    const wait = throttledFor(error);
    if (wait !== undefined) throttle.start(wait);
    else if (isProblem(error, ProblemType.invalidCredentials))
      fieldErrors.set({ password: m.password_change_wrong_current() });
    else if (!isSessionEnded(error)) setFailure(describeBackupError(error));
  };

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    throttle.reset();
    setFailure(null);
    fieldErrors.set({});
    restore.mutate(password, {
      onSuccess: async (outcome) => {
        if (outcome.status !== "done") return;
        // Every session ended with the restore; the password is now the backup's.
        markLoggedOut(queryClient);
        await router.navigate({ to: "/login", search: { reason: "restored" } });
      },
      onError,
      onSettled: () => setRestoring(false),
    });
  };

  return (
    <>
      <Form onSubmit={submit} validationErrors={fieldErrors.errors} className="flex max-w-md flex-col gap-4">
        <FormFeedback throttle={throttle} failure={failure} />
        <TextField
          name="password"
          type="password"
          label={m.backup_restore_password_label()}
          autoComplete="current-password"
          value={password}
          onChange={fieldErrors.clearing("password", setPassword)}
          validate={(value) => (value === "" ? m.password_required() : null)}
        />
        <Button
          type="submit"
          isDisabled={restore.isPending || throttle.waitSeconds !== undefined}
          className="self-start"
        >
          <RestoreIcon className="size-4" aria-hidden="true" />
          {restoring ? m.backup_restore_pending() : m.backup_restore_submit()}
        </Button>
        {restoring ? <Alert tone="info">{m.backup_restore_pending()}</Alert> : null}
      </Form>
      {dialog}
    </>
  );
}
