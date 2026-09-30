// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Alert } from "./Alert";
import { EmptyState } from "./EmptyState";

describe("Alert", () => {
  it("announces errors assertively and other tones politely", () => {
    const { rerender } = render(<Alert tone="error" title="Something went wrong" />);
    expect(screen.getByRole("alert")).toHaveTextContent("Something went wrong");

    rerender(<Alert tone="success">Saved</Alert>);
    expect(screen.getByRole("status")).toHaveTextContent("Saved");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows a warning as a standing note, not a live region", () => {
    render(
      <Alert tone="warning" title="Store it safely">
        A backup grants full access.
      </Alert>,
    );
    expect(screen.getByRole("note")).toHaveTextContent("Store it safely");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("has a labelled close button only when it can be dismissed", async () => {
    const onDismiss = vi.fn();
    const { rerender } = render(<Alert tone="info">Hello</Alert>);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();

    rerender(
      <Alert tone="info" dismissLabel="Dismiss" onDismiss={onDismiss}>
        Hello
      </Alert>,
    );
    await userEvent.setup().click(screen.getByRole("button", { name: "Dismiss" }));
    expect(onDismiss).toHaveBeenCalledOnce();
  });
});

describe("EmptyState", () => {
  it("is a titled section with the explanation", () => {
    render(<EmptyState title="Nothing here yet">Add your first application.</EmptyState>);
    expect(screen.getByRole("heading", { level: 2, name: "Nothing here yet" })).toBeVisible();
    expect(screen.getByText("Add your first application.")).toBeVisible();
  });
});
