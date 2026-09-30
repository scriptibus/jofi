// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Form, NumberField, Select } from "./index";

const groups = [
  { id: "a", title: "Provider A", options: [{ id: "a1", label: "model-one" }] },
  { id: "b", title: "Provider B", options: [{ id: "b1", label: "model-two" }] },
];

describe("Select", () => {
  it("names the button by value and label and offers grouped options", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(
      <Select
        label="Chat"
        placeholder="Choose a model"
        groups={groups}
        value={null}
        onChange={onChange}
        description="Pick one"
      />,
    );
    const button = screen.getByRole("button", { name: /Choose a model.*Chat/ });
    await user.click(button);
    const list = await screen.findByRole("listbox", { name: "Chat" });
    expect(within(list).getByText("Provider B")).toBeVisible();
    await user.click(within(list).getByRole("option", { name: "model-two" }));
    expect(onChange).toHaveBeenCalledWith("b1");
  });

  it("offers options without a group heading and shows a form's server error", async () => {
    const user = userEvent.setup();
    render(
      <Form validationErrors={{ company: "This company does not exist." }}>
        <Select
          name="company"
          label="Company"
          placeholder="Choose"
          value="none"
          onChange={() => undefined}
          groups={[
            { id: "none", options: [{ id: "none", label: "No company" }] },
            { id: "companies", title: "Companies", options: [] },
          ]}
        />
      </Form>,
    );
    expect(screen.getByText("This company does not exist.")).toBeVisible();
    await user.click(screen.getByRole("button", { name: /No company.*Company/ }));
    const list = await screen.findByRole("listbox", { name: "Company" });
    expect(within(list).getAllByRole("option")).toHaveLength(1);
  });

  it("shows the chosen option", () => {
    render(
      <Select label="Chat" placeholder="Choose" groups={groups} value="a1" onChange={() => undefined} />,
    );
    expect(screen.getByRole("button", { name: /model-one.*Chat/ })).toBeVisible();
  });
});

describe("NumberField", () => {
  it("reads a currency amount and reports the number", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(
      <NumberField
        label="Cap"
        description="Monthly"
        formatOptions={{ style: "currency", currency: "USD" }}
        onChange={onChange}
      />,
    );
    const input = screen.getByRole("textbox", { name: "Cap" });
    expect(input).toHaveAccessibleDescription("Monthly");
    await user.type(input, "1234.5");
    await user.tab();
    expect(input).toHaveValue("$1,234.50");
    expect(onChange).toHaveBeenLastCalledWith(1234.5);
  });
});
