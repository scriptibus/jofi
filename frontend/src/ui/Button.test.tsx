// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Button } from "./Button";

describe("Button", () => {
  it("renders an accessible button that fires onPress", async () => {
    const onPress = vi.fn();
    render(<Button onPress={onPress}>Save</Button>);
    const button = screen.getByRole("button", { name: "Save" });
    await userEvent.click(button);
    expect(onPress).toHaveBeenCalledOnce();
  });

  it("is keyboard operable", async () => {
    const onPress = vi.fn();
    render(<Button onPress={onPress}>Save</Button>);
    await userEvent.tab();
    expect(screen.getByRole("button", { name: "Save" })).toHaveFocus();
    await userEvent.keyboard("{Enter}");
    expect(onPress).toHaveBeenCalledOnce();
  });

  it("uses the accent fill for primary and a quiet outline for secondary", () => {
    render(
      <>
        <Button>Primary</Button>
        <Button variant="secondary">Secondary</Button>
      </>,
    );
    expect(screen.getByRole("button", { name: "Primary" })).toHaveClass("bg-accent", "text-accent-fg");
    expect(screen.getByRole("button", { name: "Secondary" })).toHaveClass("bg-surface", "border-line");
  });

  it("does not fire when disabled", async () => {
    const onPress = vi.fn();
    render(
      <Button isDisabled onPress={onPress}>
        Save
      </Button>,
    );
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(onPress).not.toHaveBeenCalled();
  });
});
