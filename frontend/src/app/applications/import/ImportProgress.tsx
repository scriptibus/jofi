// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type ReactNode, useEffect, useRef } from "react";
import type { PostingImportResponse } from "../../../api/generated/jofi";
import { m } from "../../../paraglide/messages.js";
import { Alert, Button, RefreshIcon, TextLink } from "../../../ui";
import { failureMessage, isRetryable, needsAiSetup, startErrorMessage } from "./importModel";
import type { PostingImportFlow } from "./usePostingImport";

/**
 * Where the dialog's focus goes when the form gives way to a status: the form's button is gone, so without this
 * focus would fall out of the dialog. Remounted per status (`key`), so each change is announced from here.
 */
function Status({ label, children }: { label: string; children: ReactNode }) {
  const ref = useRef<HTMLElement>(null);
  useEffect(() => ref.current?.focus(), []);
  return (
    <section ref={ref} tabIndex={-1} aria-label={label} className="flex flex-col gap-4 outline-none">
      {children}
    </section>
  );
}

export interface ImportProgressProps {
  flow: PostingImportFlow;
  onClose: () => void;
}

/** What became of an accepted import: running, failed (with what to do), or done (with its application). */
export function ImportProgress({ flow, onClose }: ImportProgressProps) {
  const { current } = flow;
  if (flow.statusFailed) return <StatusUnknown flow={flow} onClose={onClose} />;
  if (current === undefined || current.status === "PENDING") return <Pending onClose={onClose} />;
  if (current.status === "SUCCEEDED") return <Done current={current} flow={flow} onClose={onClose} />;
  return <Failed current={current} flow={flow} onClose={onClose} />;
}

function Pending({ onClose }: { onClose: () => void }) {
  return (
    <Status key="pending" label={m.import_title()}>
      <Alert tone="info" title={m.import_pending_title()}>
        <p>{m.import_pending_hint()}</p>
      </Alert>
      <div className="flex justify-end">
        <Button variant="secondary" onPress={onClose}>
          {m.import_close()}
        </Button>
      </div>
    </Status>
  );
}

/** The import was accepted, but reading its status failed: it may well be running. */
function StatusUnknown({ flow, onClose }: ImportProgressProps) {
  return (
    <Status key="status-failed" label={m.import_title()}>
      <Alert tone="error" title={m.import_status_failed()}>
        <p>{m.import_status_failed_hint()}</p>
      </Alert>
      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onPress={onClose}>
          {m.import_close()}
        </Button>
        <Button onPress={flow.refreshStatus}>
          <RefreshIcon className="size-4" aria-hidden="true" />
          {m.import_status_retry()}
        </Button>
      </div>
    </Status>
  );
}

interface ResultProps extends ImportProgressProps {
  current: PostingImportResponse;
}

function Done({ current, flow, onClose }: ResultProps) {
  const title = flow.alreadyImported ? m.import_already_title() : m.import_done_title();
  return (
    <Status key="done" label={m.import_title()}>
      <Alert tone="success" title={title}>
        <p>{flow.alreadyImported ? m.import_already_hint() : m.import_done_hint()}</p>
        {current.applicationId ? (
          <TextLink
            to="/applications/$applicationId"
            params={{ applicationId: current.applicationId }}
            className="self-start"
          >
            {m.import_open_application()}
          </TextLink>
        ) : null}
      </Alert>
      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onPress={onClose}>
          {m.import_close()}
        </Button>
        <Button variant="secondary" onPress={flow.reset}>
          {m.import_another()}
        </Button>
      </div>
    </Status>
  );
}

function Failed({ current, flow, onClose }: ResultProps) {
  return (
    <Status key="failed" label={m.import_title()}>
      <Alert tone="error" title={m.import_failed_title()}>
        <p>{failureMessage(current.failure)}</p>
        {needsAiSetup(current.failure) ? (
          <TextLink to="/setup" search={{ step: "welcome" }} className="self-start" onPress={onClose}>
            {m.import_ai_setup_link()}
          </TextLink>
        ) : null}
      </Alert>
      {flow.startError ? <Alert tone="error">{startErrorMessage(flow.startError)}</Alert> : null}
      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onPress={onClose}>
          {m.import_close()}
        </Button>
        <Button variant="secondary" onPress={flow.reset}>
          {m.import_edit()}
        </Button>
        {isRetryable(current.failure) ? (
          <Button onPress={flow.retry} isDisabled={flow.isStarting}>
            {flow.isStarting ? m.import_submitting() : m.import_retry()}
          </Button>
        ) : null}
      </div>
    </Status>
  );
}
