// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Dialog } from "./Dialog";
import { FilePicker } from "./FilePicker";

describe("Dialog", () => {
  it("is a labelled modal dialog that closes on Escape", async () => {
    const onClose = vi.fn();
    render(
      <Dialog isOpen title="Download backup" onClose={onClose}>
        <input aria-label="Password" />
      </Dialog>,
    );
    const dialog = screen.getByRole("dialog", { name: "Download backup" });
    expect(dialog).toBeVisible();
    expect(screen.getByRole("heading", { name: "Download backup" })).toBeVisible();

    await userEvent.setup().keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("renders nothing while closed", () => {
    render(
      <Dialog isOpen={false} title="Download backup" onClose={() => undefined}>
        content
      </Dialog>,
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});

describe("FilePicker", () => {
  it("is a button that hands over the one chosen file, also the same file twice", async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();
    const { container } = render(
      <FilePicker acceptedFileTypes={[".zip"]} onSelect={onSelect}>
        Choose backup
      </FilePicker>,
    );
    expect(screen.getByRole("button", { name: "Choose backup" })).toBeEnabled();
    const input = container.ownerDocument.querySelector<HTMLInputElement>("input[type=file]");
    if (input === null) throw new Error("no file input");
    expect(input).toHaveAttribute("accept", ".zip");

    const file = new File(["PK"], "backup.zip", { type: "application/zip" });
    await user.upload(input, file);
    await user.upload(input, file);

    expect(onSelect).toHaveBeenCalledTimes(2);
    expect(onSelect).toHaveBeenLastCalledWith(file);
  });

  it("can be disabled", () => {
    render(
      <FilePicker onSelect={() => undefined} isDisabled>
        Choose backup
      </FilePicker>,
    );
    expect(screen.getByRole("button", { name: "Choose backup" })).toBeDisabled();
  });
});
