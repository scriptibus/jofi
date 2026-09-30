// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type ReactNode, useCallback, useEffect, useRef, useState } from "react";
import {
  type ConfirmationEffect,
  type ConfirmedOutcome,
  type ExpectedAction,
  runConfirmed,
} from "../api/confirmation";
import { m } from "../paraglide/messages.js";
import { ConfirmDialog } from "../ui";

export interface ConfirmationPrompt {
  /** The operation and targets the caller means to run; a 428 for anything else is refused unasked. */
  expect: ExpectedAction;
  /**
   * The dialog text from the server's effect, in the user's language, e.g.
   * `(effect) => m.application_delete_confirm({ name: effect.name, documents: effect.counts.documents ?? 0 })`.
   * The server derives the effect from what it would really run, so the dialog shows that.
   */
  describe: (effect: ConfirmationEffect) => string;
  /** Defaults to a generic "Please confirm". */
  title?: string;
  /** Defaults to "Confirm"; name the action where you can ("Delete"). */
  confirmLabel?: string;
}

export interface Confirmation {
  /**
   * Runs `call` through the server's two-step confirmation (ADR-0039), showing the server's effect
   * through `prompt.describe` when the server asks. Resolves to `cancelled` if the user declines (or
   * another confirmation replaces this one, or the component unmounts); errors are thrown as usual.
   */
  confirmed: <T>(
    call: (options?: RequestInit) => Promise<T>,
    prompt: ConfirmationPrompt,
  ) => Promise<ConfirmedOutcome<T>>;
  /** Render this once in the component that uses the hook. */
  dialog: ReactNode;
}

interface OpenPrompt {
  prompt: ConfirmationPrompt;
  effect: ConfirmationEffect;
}

/** The confirmation dialog for destructive and outward-facing actions, wired to the server's flow. */
export function useConfirmation(): Confirmation {
  const [open, setOpen] = useState<OpenPrompt | undefined>(undefined);
  const answer = useRef<((confirmed: boolean) => void) | undefined>(undefined);

  /** Settles the pending question (if any); a question never stays unanswered. */
  const settle = useCallback((confirmed: boolean) => {
    const resolve = answer.current;
    answer.current = undefined;
    resolve?.(confirmed);
  }, []);

  useEffect(() => () => settle(false), [settle]);

  const close = useCallback(
    (confirmed: boolean) => {
      settle(confirmed);
      setOpen(undefined);
    },
    [settle],
  );

  const confirmed = useCallback(
    <T,>(call: (options?: RequestInit) => Promise<T>, prompt: ConfirmationPrompt) =>
      runConfirmed(
        call,
        prompt.expect,
        (request) =>
          new Promise<boolean>((resolve) => {
            settle(false);
            answer.current = resolve;
            setOpen({ prompt, effect: request.effect });
          }),
      ),
    [settle],
  );

  const dialog = (
    <ConfirmDialog
      isOpen={open !== undefined}
      title={open?.prompt.title ?? m.confirm_title()}
      confirmLabel={open?.prompt.confirmLabel ?? m.confirm_action()}
      cancelLabel={m.confirm_cancel()}
      onConfirm={() => close(true)}
      onCancel={() => close(false)}
    >
      {open ? open.prompt.describe(open.effect) : null}
    </ConfirmDialog>
  );

  return { confirmed, dialog };
}
