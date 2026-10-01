// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type SyntheticEvent, useState } from "react";
import { m } from "../../../paraglide/messages.js";
import { Alert, Button, Dialog, Form, SegmentedControl, TextArea, TextField, TextLink } from "../../../ui";
import { ImportProgress } from "./ImportProgress";
import {
  EMPTY_DRAFT,
  type ImportDraft,
  type ImportSource,
  type ImportStartError,
  offersPasting,
  startErrorMessage,
} from "./importModel";
import { type PostingImportFlow, usePostingImport } from "./usePostingImport";

export interface ImportPostingDialogProps {
  isOpen: boolean;
  onClose: () => void;
  /** What the form starts with, e.g. what another app shared. The user still confirms before anything is sent. */
  initial?: ImportDraft;
  /**
   * Focus the field on opening (the default: the user pressed "Import posting" and starts typing). A dialog that
   * opens by itself, like the share target's, sets this to false: focus lands on the dialog, so a stray Enter
   * cannot confirm an import the user has not looked at.
   */
  autoFocusField?: boolean;
}

/**
 * Import a job posting from a link or its pasted text (spec §8.1): the form, then the import's status (running,
 * done with its application, or failed with what to do). Nothing is sent before the user presses "Import".
 */
export function ImportPostingDialog({
  isOpen,
  onClose,
  initial = EMPTY_DRAFT,
  autoFocusField = true,
}: ImportPostingDialogProps) {
  return (
    <Dialog isOpen={isOpen} title={m.import_title()} onClose={onClose}>
      {/* Mounted per opening, so each opening starts from `initial` with no import of an earlier one. */}
      {isOpen ? <ImportContent initial={initial} onClose={onClose} autoFocusField={autoFocusField} /> : null}
    </Dialog>
  );
}

function ImportContent({
  initial,
  onClose,
  autoFocusField,
}: {
  initial: ImportDraft;
  onClose: () => void;
  autoFocusField: boolean;
}) {
  const flow = usePostingImport();
  const [draft, setDraft] = useState(initial);
  if (flow.current !== undefined || flow.statusFailed)
    return <ImportProgress flow={flow} onClose={onClose} />;
  return (
    <ImportForm
      flow={flow}
      draft={draft}
      onDraft={setDraft}
      onClose={onClose}
      autoFocusField={autoFocusField}
    />
  );
}

interface ImportFormProps {
  flow: PostingImportFlow;
  draft: ImportDraft;
  onDraft: (draft: ImportDraft) => void;
  onClose: () => void;
  autoFocusField: boolean;
}

function ImportForm({ flow, draft, onDraft, onClose, autoFocusField }: ImportFormProps) {
  const { startError } = flow;
  const edit = (change: Partial<ImportDraft>) => {
    flow.dismissError();
    onDraft({ ...draft, ...change });
  };
  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    flow.start(draft);
  };
  const invalid = startError?.kind === "violation" ? startError.violation.source : undefined;

  return (
    <Form onSubmit={submit} className="flex flex-col gap-4">
      <StartProblem error={startError} onPaste={() => edit({ source: "text" })} onClose={onClose} />
      <SegmentedControl<ImportSource>
        label={m.import_source_label()}
        options={[
          { value: "url", label: m.import_source_url() },
          { value: "text", label: m.import_source_text() },
        ]}
        value={draft.source}
        onChange={(source) => edit({ source })}
      />
      {draft.source === "url" ? (
        <TextField
          key="url"
          name="url"
          label={m.import_url_label()}
          description={m.import_url_description()}
          value={draft.url}
          onChange={(url) => edit({ url })}
          isInvalid={invalid === "url"}
          isRequired
          inputMode="url"
          autoComplete="off"
          spellCheck="false"
          autoFocus={autoFocusField}
        />
      ) : (
        <TextArea
          key="text"
          name="text"
          label={m.import_text_label()}
          description={m.import_text_description()}
          value={draft.text}
          onChange={(text) => edit({ text })}
          isInvalid={invalid === "text"}
          isRequired
          rows={8}
          autoFocus={autoFocusField}
        />
      )}
      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onPress={onClose}>
          {m.confirm_cancel()}
        </Button>
        <Button type="submit" isDisabled={flow.isStarting}>
          {flow.isStarting ? m.import_submitting() : m.import_submit()}
        </Button>
      </div>
    </Form>
  );
}

interface StartProblemProps {
  error: ImportStartError | null;
  onPaste: () => void;
  onClose: () => void;
}

/** Why the start was refused, with what the user can do about it: paste the text, set up AI, or wait. */
function StartProblem({ error, onPaste, onClose }: StartProblemProps) {
  if (error === null) return null;
  const message = startErrorMessage(error);
  if (error.kind === "ai-not-configured")
    return (
      <Alert tone="error" title={m.import_error_title()}>
        <p>{message}</p>
        <TextLink to="/setup" search={{ step: "welcome" }} className="self-start" onPress={onClose}>
          {m.import_ai_setup_link()}
        </TextLink>
      </Alert>
    );
  return (
    <Alert tone="error" title={m.import_error_title()}>
      <p>{message}</p>
      {error.kind === "violation" && offersPasting(error.violation) ? (
        <Button variant="secondary" className="self-start" onPress={onPaste}>
          {m.import_use_text()}
        </Button>
      ) : null}
    </Alert>
  );
}
