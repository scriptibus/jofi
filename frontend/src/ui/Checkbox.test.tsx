// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { Checkbox } from "./Checkbox";

function Controlled() {
  const [done, setDone] = useState(false);
  return (
    <Checkbox isSelected={done} onChange={setDone}>
      Send the follow-up
    </Checkbox>
  );
}

describe("Checkbox", () => {
  it("is a checkbox named by its visible label that toggles on click and Space", async () => {
    const user = userEvent.setup();
    render(<Controlled />);
    const box = screen.getByRole("checkbox", { name: "Send the follow-up" });
    expect(box).not.toBeChecked();

    await user.click(screen.getByText("Send the follow-up"));
    expect(box).toBeChecked();

    await user.keyboard(" ");
    expect(box).not.toBeChecked();
  });

  it("can be disabled", () => {
    render(
      <Checkbox isSelected={false} onChange={() => undefined} isDisabled>
        Locked
      </Checkbox>,
    );
    expect(screen.getByRole("checkbox", { name: "Locked" })).toBeDisabled();
  });
});
