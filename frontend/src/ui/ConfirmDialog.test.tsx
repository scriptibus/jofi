// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { CONFIRM_ARM_DELAY_MS, ConfirmDialog } from "./ConfirmDialog";

function renderDialog(isOpen = true) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  render(
    <ConfirmDialog
      isOpen={isOpen}
      title="Delete application?"
      confirmLabel="Delete"
      cancelLabel="Cancel"
      onConfirm={onConfirm}
      onCancel={onCancel}
    >
      The application at ACME and its 3 documents will be deleted.
    </ConfirmDialog>,
  );
  return { onConfirm, onCancel };
}

describe("ConfirmDialog", () => {
  it("is a labelled alert dialog that says what will happen", () => {
    renderDialog();

    const dialog = screen.getByRole("alertdialog", { name: "Delete application?" });
    expect(dialog).toHaveTextContent("its 3 documents will be deleted");
  });

  it("keeps Confirm disabled right after opening, so the opening tap cannot confirm", async () => {
    const opened = performance.now();
    const { onConfirm } = renderDialog();
    const confirm = screen.getByRole("button", { name: "Delete" });

    expect(confirm).toBeDisabled();
    await userEvent.click(confirm);
    expect(onConfirm).not.toHaveBeenCalled();

    await vi.waitFor(() => expect(confirm).toBeEnabled());
    expect(performance.now() - opened).toBeGreaterThanOrEqual(CONFIRM_ARM_DELAY_MS - 1);
    await userEvent.click(confirm);
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it("confirms only on the confirm button", async () => {
    const { onConfirm, onCancel } = renderDialog();
    const confirm = screen.getByRole("button", { name: "Delete" });

    await vi.waitFor(() => expect(confirm).toBeEnabled());
    await userEvent.click(confirm);

    expect(onConfirm).toHaveBeenCalledOnce();
    expect(onCancel).not.toHaveBeenCalled();
  });

  it("cancels on the cancel button and on Escape", async () => {
    const { onConfirm, onCancel } = renderDialog();

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await userEvent.keyboard("{Escape}");

    expect(onCancel).toHaveBeenCalledTimes(2);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it("renders nothing while closed", () => {
    renderDialog(false);

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });
});
