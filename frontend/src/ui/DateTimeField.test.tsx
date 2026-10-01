// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { parseDateTime } from "@internationalized/date";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { toMinutes } from "./DateTimeField";
import { DateTimeField, Form, LocaleProvider } from "./index";

function Controlled({
  initial,
  onChange,
}: {
  initial: string | null;
  onChange: (value: string | null) => void;
}) {
  const [value, setValue] = useState(initial);
  return (
    <DateTimeField
      label="Starts"
      description="On the clock of the zone below"
      value={value}
      onChange={(next) => {
        setValue(next);
        onChange(next);
      }}
    />
  );
}

/**
 * The field's text without the Unicode isolates (U+2066 to U+2069) React Aria puts around the time. Built from
 * code points, so the source holds no bidirectional characters.
 */
const ISOLATES = new RegExp(`[${String.fromCodePoint(0x2066)}-${String.fromCodePoint(0x2069)}]`, "g");
const shown = () => screen.getByRole("group", { name: "Starts" }).textContent?.replace(ISOLATES, "");

describe("DateTimeField", () => {
  it("shows a wall-clock value in the user's locale, without converting it", () => {
    render(
      <LocaleProvider locale="de-DE">
        <Controlled initial="2026-10-05T10:00" onChange={() => undefined} />
      </LocaleProvider>,
    );
    expect(shown()).toBe("5.10.2026, 10:00");
    expect(screen.getByText("On the clock of the zone below")).toBeVisible();
  });

  it("reports the typed date and time to the minute", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Controlled initial={null} onChange={onChange} />);
    await user.click(screen.getByRole("spinbutton", { name: /^month/i }));
    await user.keyboard("10052026");
    await user.keyboard("0230P");
    expect(onChange).toHaveBeenLastCalledWith("2026-10-05T14:30");
    expect(shown()).toMatch(/^10\/5\/2026, 2:30\sPM$/);
  });

  it("picks a day from the calendar and keeps the time", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Controlled initial="2026-10-05T10:00" onChange={onChange} />);
    await user.click(screen.getByRole("button", { name: /Calendar/ }));
    const calendar = await screen.findByRole("application", { name: /October 2026/ });
    // The first one is ours; React Aria adds a hidden one for screen readers after the grid.
    const [next] = within(calendar).getAllByRole("button", { name: "Next" });
    if (!next) throw new Error("no next button");
    await user.click(next);
    await user.click(within(screen.getByRole("application", { name: /November 2026/ })).getByText("12"));
    expect(onChange).toHaveBeenLastCalledWith("2026-11-12T10:00");
  });

  it("shows a form's server error next to the field", () => {
    render(
      <Form validationErrors={{ localStart: "Choose a time between 2000 and 2099." }}>
        <DateTimeField name="localStart" label="Starts" value="2026-10-05T10:00" onChange={() => undefined} />
      </Form>,
    );
    expect(screen.getByText("Choose a time between 2000 and 2099.")).toBeVisible();
  });

  it("shows its own validation after submit", async () => {
    const user = userEvent.setup();
    render(
      <Form>
        <DateTimeField
          label="When"
          value="1999-12-31T23:00"
          onChange={() => undefined}
          validate={(value) => (value !== null && value < "2000" ? "Choose a later time." : null)}
        />
        <button type="submit">Save</button>
      </Form>,
    );
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Choose a later time.")).toBeVisible();
  });

  it("drops seconds", () => {
    expect(toMinutes(parseDateTime("2026-10-05T10:00:59.5"))).toBe("2026-10-05T10:00");
  });
});
