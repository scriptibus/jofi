// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { Dialog, Heading, Modal, ModalOverlay } from "react-aria-components";
import { Button } from "./Button";

export interface ConfirmDialogProps {
  isOpen: boolean;
  title: string;
  /** What will happen, in the user's language. */
  children: ReactNode;
  confirmLabel: string;
  cancelLabel: string;
  onConfirm: () => void;
  /** Also called for Escape; clicking outside does nothing, so a stray tap never decides. */
  onCancel: () => void;
}

/**
 * A modal `alertdialog` asking the user to confirm a destructive or outward-facing action. Focus
 * starts on the dialog and stays inside it; Cancel comes first, so Enter on the first button never
 * confirms by accident.
 */
export function ConfirmDialog({
  isOpen,
  title,
  children,
  confirmLabel,
  cancelLabel,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  return (
    <ModalOverlay
      isOpen={isOpen}
      onOpenChange={(open) => {
        if (!open) onCancel();
      }}
      className="fixed inset-0 z-50 flex items-center justify-center bg-bg/80 p-4"
    >
      <Modal className="w-full max-w-md rounded border border-line bg-surface p-6 text-fg shadow-card">
        <Dialog role="alertdialog" className="flex flex-col gap-4 outline-none">
          <Heading slot="title" className="text-h3">
            {title}
          </Heading>
          <div className="text-body">{children}</div>
          <div className="flex flex-wrap justify-end gap-3">
            <Button variant="secondary" onPress={onCancel}>
              {cancelLabel}
            </Button>
            <Button onPress={onConfirm}>{confirmLabel}</Button>
          </div>
        </Dialog>
      </Modal>
    </ModalOverlay>
  );
}
