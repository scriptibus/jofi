// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type ReactNode, useCallback, useRef, useState } from "react";
import { type ConfirmedOutcome, runConfirmed } from "../api/confirmation";
import { m } from "../paraglide/messages.js";
import { ConfirmDialog } from "../ui";

export interface ConfirmationPrompt {
  /** What will happen, in the user's language (e.g. "Delete the application at ACME?"). */
  message: string;
  /** Defaults to a generic "Please confirm". */
  title?: string;
  /** Defaults to "Confirm"; name the action where you can ("Delete"). */
  confirmLabel?: string;
}

export interface Confirmation {
  /**
   * Runs `call` through the server's two-step confirmation (ADR-0039), showing `prompt` when the
   * server asks. Resolves to `cancelled` if the user declines; errors are thrown as usual.
   */
  confirmed: <T>(
    call: (options?: RequestInit) => Promise<T>,
    prompt: ConfirmationPrompt,
  ) => Promise<ConfirmedOutcome<T>>;
  /** Render this once in the component that uses the hook. */
  dialog: ReactNode;
}

/** The confirmation dialog for destructive and outward-facing actions, wired to the server's flow. */
export function useConfirmation(): Confirmation {
  const [prompt, setPrompt] = useState<ConfirmationPrompt | undefined>(undefined);
  const answer = useRef<((confirmed: boolean) => void) | undefined>(undefined);

  const close = useCallback((confirmed: boolean) => {
    answer.current?.(confirmed);
    answer.current = undefined;
    setPrompt(undefined);
  }, []);

  const confirmed = useCallback(
    <T,>(call: (options?: RequestInit) => Promise<T>, next: ConfirmationPrompt) =>
      runConfirmed(
        call,
        () =>
          new Promise<boolean>((resolve) => {
            answer.current = resolve;
            setPrompt(next);
          }),
      ),
    [],
  );

  const dialog = (
    <ConfirmDialog
      isOpen={prompt !== undefined}
      title={prompt?.title ?? m.confirm_title()}
      confirmLabel={prompt?.confirmLabel ?? m.confirm_action()}
      cancelLabel={m.confirm_cancel()}
      onConfirm={() => close(true)}
      onCancel={() => close(false)}
    >
      {prompt?.message}
    </ConfirmDialog>
  );

  return { confirmed, dialog };
}
