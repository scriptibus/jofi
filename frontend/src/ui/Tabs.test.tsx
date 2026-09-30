// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { Tabs } from "./index";

type Section = "one" | "two" | "later" | "three";

function Example({ initial = "one" }: { initial?: Section }) {
  const [selected, setSelected] = useState<Section>(initial);
  return (
    <Tabs<Section>
      label="Sections"
      tabs={[
        { id: "one", label: "One" },
        { id: "two", label: "Two" },
        { id: "later", label: "Later", isDisabled: true },
        { id: "three", label: "Three" },
      ]}
      selected={selected}
      onSelect={setSelected}
    >
      <p>Content of {selected}</p>
    </Tabs>
  );
}

describe("Tabs", () => {
  it("shows the selected tab's panel, labelled by its tab", () => {
    render(<Example initial="two" />);
    expect(screen.getByRole("tablist", { name: "Sections" })).toBeVisible();
    expect(screen.getByRole("tab", { name: "Two" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tabpanel", { name: "Two" })).toHaveTextContent("Content of two");
  });

  it("moves with the arrow keys and skips disabled tabs", async () => {
    const user = userEvent.setup();
    render(<Example />);
    await user.click(screen.getByRole("tab", { name: "One" }));
    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("tabpanel")).toHaveTextContent("Content of two");
    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "Three" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "Later" })).toHaveAttribute("aria-disabled", "true");
  });

  it("does not select a disabled tab on click", async () => {
    const user = userEvent.setup();
    render(<Example />);
    await user.click(screen.getByRole("tab", { name: "Later" }));
    expect(screen.getByRole("tabpanel")).toHaveTextContent("Content of one");
  });
});
