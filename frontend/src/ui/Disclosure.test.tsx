// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { Disclosure } from "./index";

describe("Disclosure", () => {
  it("opens and closes its panel with the trigger button", async () => {
    const user = userEvent.setup();
    render(
      <Disclosure label="More filters">
        <input aria-label="Language" />
      </Disclosure>,
    );
    const trigger = screen.getByRole("button", { name: "More filters" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("textbox", { name: "Language" })).toBeNull();
    await user.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("textbox", { name: "Language" })).toBeVisible();
  });

  it("can be controlled by the caller", async () => {
    const user = userEvent.setup();
    const changes: boolean[] = [];
    const { rerender } = render(
      <Disclosure label="Ended" isExpanded={false} onExpandedChange={(open) => changes.push(open)}>
        <input aria-label="Language" />
      </Disclosure>,
    );
    const trigger = screen.getByRole("button", { name: "Ended" });
    await user.click(trigger);
    expect(changes).toEqual([true]);
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    rerender(
      <Disclosure label="Ended" isExpanded onExpandedChange={(open) => changes.push(open)}>
        <input aria-label="Language" />
      </Disclosure>,
    );
    expect(trigger).toHaveAttribute("aria-expanded", "true");
  });

  it("can start open", () => {
    render(
      <Disclosure label="More filters" defaultExpanded>
        <input aria-label="Language" />
      </Disclosure>,
    );
    expect(screen.getByRole("button", { name: "More filters" })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("textbox", { name: "Language" })).toBeVisible();
  });
});
