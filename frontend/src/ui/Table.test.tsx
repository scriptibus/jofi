// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { MultiSelect, Table, TableCell } from "./index";

describe("Table", () => {
  const columns = [
    { id: "title", label: "Title", sortable: true },
    { id: "due", label: "Due", sortable: true },
    { id: "actions", label: "Actions", hideLabel: true },
  ] as const;

  it("scrolls inside a named, keyboard-focusable region, so a wide table never needs the mouse", async () => {
    const user = userEvent.setup();
    render(
      <Table label="Jobs" columns={columns}>
        <tr>
          <TableCell>Backend</TableCell>
          <TableCell>Monday</TableCell>
          <TableCell>–</TableCell>
        </tr>
      </Table>,
    );
    const region = screen.getByRole("region", { name: "Jobs" });
    expect(within(region).getByRole("table", { name: "Jobs" })).toBeVisible();
    await user.tab();
    expect(region).toHaveFocus();
  });

  it("is a captioned table whose sorted column carries aria-sort, with sort buttons in the headings", async () => {
    const onSort = vi.fn();
    const user = userEvent.setup();
    render(
      <Table label="Jobs" columns={columns} sort={{ column: "due", direction: "descending" }} onSort={onSort}>
        <tr>
          <TableCell>Backend</TableCell>
          <TableCell>Monday</TableCell>
          <TableCell>–</TableCell>
        </tr>
      </Table>,
    );
    const table = screen.getByRole("table", { name: "Jobs" });
    expect(within(table).getByRole("columnheader", { name: "Due" })).toHaveAttribute(
      "aria-sort",
      "descending",
    );
    expect(within(table).getByRole("columnheader", { name: "Title" })).not.toHaveAttribute("aria-sort");
    expect(within(table).getByRole("columnheader", { name: "Actions" })).toBeInTheDocument();
    expect(within(table).getByRole("cell", { name: "Backend" })).toBeVisible();
    await user.click(within(table).getByRole("button", { name: "Title" }));
    expect(onSort).toHaveBeenCalledWith("title");
    expect(within(table).queryByRole("button", { name: "Actions" })).toBeNull();
  });

  it("offers no sort buttons without onSort", () => {
    render(
      <Table label="Jobs" columns={columns}>
        <tr>
          <TableCell>Backend</TableCell>
        </tr>
      </Table>,
    );
    expect(screen.queryByRole("button")).toBeNull();
  });
});

describe("MultiSelect", () => {
  const groups = [
    {
      id: "all",
      options: [
        { id: "a", label: "Applied" },
        { id: "o", label: "Offer" },
      ],
    },
  ];

  it("toggles options and reports every chosen id", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(
      <MultiSelect
        label="Status"
        placeholder="Any status"
        groups={groups}
        value={["a"]}
        onChange={onChange}
      />,
    );
    await user.click(screen.getByRole("button", { name: /Applied.*Status/ }));
    const list = await screen.findByRole("listbox", { name: "Status" });
    expect(list).toHaveAttribute("aria-multiselectable", "true");
    expect(within(list).getByRole("option", { name: "Applied" })).toHaveAttribute("aria-selected", "true");
    await user.click(within(list).getByRole("option", { name: "Offer" }));
    expect(onChange).toHaveBeenLastCalledWith(["a", "o"]);
  });

  it("shows the placeholder without a choice", () => {
    render(
      <MultiSelect
        label="Status"
        placeholder="Any status"
        groups={groups}
        value={[]}
        onChange={() => undefined}
      />,
    );
    expect(screen.getByRole("button", { name: /Any status.*Status/ })).toBeVisible();
  });
});
