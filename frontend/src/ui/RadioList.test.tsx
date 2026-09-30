// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { RadioList } from "./index";

type Version = "v1" | "v2" | "v3";

function Example() {
  const [value, setValue] = useState<Version>("v3");
  return (
    <>
      <RadioList<Version>
        label="Versions"
        options={[
          { value: "v3", label: "Version 3", details: "Today" },
          { value: "v2", label: "Version 2", details: <strong>Frozen</strong> },
          { value: "v1", label: "Version 1" },
        ]}
        value={value}
        onChange={setValue}
      />
      <p>Chosen: {value}</p>
    </>
  );
}

describe("RadioList", () => {
  it("is a labelled radio group whose options carry their details", () => {
    render(<Example />);
    const group = screen.getByRole("radiogroup", { name: "Versions" });
    expect(group).toBeVisible();
    expect(screen.getByRole("radio", { name: /Version 3\s*Today/ })).toBeChecked();
    expect(screen.getByRole("radio", { name: /Version 2\s*Frozen/ })).not.toBeChecked();
  });

  it("can keep its label for assistive technology only", () => {
    render(
      <RadioList
        label="Hidden"
        options={[{ value: "a", label: "A" }]}
        value="a"
        onChange={() => {}}
        hideLabel
      />,
    );
    expect(screen.getByRole("radiogroup", { name: "Hidden" })).toBeVisible();
    expect(screen.getByText("Hidden")).toHaveClass("sr-only");
  });

  it("chooses by click and by arrow keys", async () => {
    const user = userEvent.setup();
    render(<Example />);
    await user.click(screen.getByText("Version 2"));
    expect(screen.getByText("Chosen: v2")).toBeVisible();
    expect(screen.getByRole("radio", { name: /Version 2/ })).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByText("Chosen: v1")).toBeVisible();
    expect(screen.getByRole("radio", { name: /Version 1/ })).toBeChecked();
  });
});
