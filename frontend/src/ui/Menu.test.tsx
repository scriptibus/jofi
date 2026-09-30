// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { MenuButton } from "./index";

const groups = [
  {
    id: "pipeline",
    title: "Pipeline",
    actions: [
      { id: "APPLIED", label: "Applied" },
      { id: "OFFER", label: "Offer" },
    ],
  },
  { id: "ended", title: "Ended", actions: [] },
  { id: "other", actions: [{ id: "ARCHIVE", label: "Archive" }] },
];

describe("MenuButton", () => {
  it("opens a menu of grouped actions from a labelled button, leaving out empty groups", async () => {
    const user = userEvent.setup();
    render(
      <MenuButton label="Move Backend Engineer to…" groups={groups} onAction={() => {}}>
        <span aria-hidden="true">⇄</span>
      </MenuButton>,
    );
    await user.click(screen.getByRole("button", { name: "Move Backend Engineer to…" }));
    const menu = await screen.findByRole("menu", { name: "Move Backend Engineer to…" });
    expect(
      within(menu)
        .getAllByRole("menuitem")
        .map((item) => item.textContent),
    ).toEqual(["Applied", "Offer", "Archive"]);
    expect(within(menu).getByText("Pipeline")).toBeVisible();
    expect(within(menu).queryByText("Ended")).toBeNull();
  });

  it("runs the chosen action with the keyboard and returns focus to the button", async () => {
    const user = userEvent.setup();
    const onAction = vi.fn();
    render(
      <MenuButton label="Move" groups={groups} onAction={onAction}>
        <span aria-hidden="true">⇄</span>
      </MenuButton>,
    );
    const button = screen.getByRole("button", { name: "Move" });
    button.focus();
    await user.keyboard("{Enter}");
    await screen.findByRole("menu");
    await user.keyboard("{ArrowDown}{Enter}");
    expect(onAction).toHaveBeenCalledWith("OFFER");
    expect(screen.queryByRole("menu")).toBeNull();
    await waitFor(() => expect(button).toHaveFocus());
  });
});
