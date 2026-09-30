// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Form, TextArea } from "./index";

describe("TextArea", () => {
  it("labels the text area, links the description and keeps line breaks", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<TextArea label="Locations" description="One per line" onChange={onChange} />);
    const area = screen.getByRole("textbox", { name: "Locations" });
    expect(area.tagName).toBe("TEXTAREA");
    expect(area).toHaveAccessibleDescription("One per line");
    await user.type(area, "Berlin{Enter}Remote");
    expect(onChange).toHaveBeenLastCalledWith("Berlin\nRemote");
  });

  it("shows server errors from the form by field name", () => {
    render(
      <Form validationErrors={{ notes: "Too long" }}>
        <TextArea name="notes" label="Notes" />
      </Form>,
    );
    expect(screen.getByText("Too long")).toBeVisible();
    expect(screen.getByRole("textbox", { name: "Notes" })).toHaveAttribute("aria-invalid", "true");
  });
});
