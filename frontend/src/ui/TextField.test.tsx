// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Button } from "./Button";
import { Form, TextField } from "./index";

describe("TextField", () => {
  it("labels the input and links the description", () => {
    render(<TextField label="Setup token" description="Read it on the server" />);
    const input = screen.getByRole("textbox", { name: "Setup token" });
    expect(input).toHaveAccessibleDescription("Read it on the server");
  });

  it("shows the validate error after submit and blocks the submit", async () => {
    const onSubmit = vi.fn((event: { preventDefault(): void }) => event.preventDefault());
    const user = userEvent.setup();
    render(
      <Form onSubmit={onSubmit}>
        <TextField label="Name" validate={(value) => (value.length < 3 ? "Too short" : null)} />
        <Button type="submit">Save</Button>
      </Form>,
    );

    await user.type(screen.getByLabelText("Name"), "ab");
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(screen.getByText("Too short")).toBeVisible();
    expect(screen.getByLabelText("Name")).toHaveAttribute("aria-invalid", "true");
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("shows server errors from the form by field name", () => {
    render(
      <Form validationErrors={{ password: "Wrong password" }}>
        <TextField name="password" type="password" label="Password" />
      </Form>,
    );
    expect(screen.getByText("Wrong password")).toBeVisible();
    expect(screen.getByLabelText("Password")).toHaveAccessibleDescription("Wrong password");
  });

  it("uses the data font for mono fields", () => {
    render(<TextField label="Token" mono />);
    expect(screen.getByLabelText("Token")).toHaveClass("font-data");
  });
});
