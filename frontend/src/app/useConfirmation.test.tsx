// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiProblemError } from "../api/fetcher";
import { overwriteGetLocale } from "../paraglide/runtime.js";
import { useConfirmation } from "./useConfirmation";

const required = new ApiProblemError(428, {
  type: "urn:jofi:problem:shared:confirmation-required",
  status: 428,
  confirmationToken: "t0k3n",
  expiresAt: "2026-09-30T10:05:00Z",
  operation: "things.delete",
  targets: ["42"],
} as never);

/** A page with one destructive button, the way a feature uses the hook. */
function DeletePage({ call }: { call: (options?: RequestInit) => Promise<void> }) {
  const { confirmed, dialog } = useConfirmation();
  const [outcome, setOutcome] = useState("idle");
  return (
    <>
      <button
        type="button"
        onClick={async () => {
          const result = await confirmed(call, { message: "Thing 42 will be deleted." });
          setOutcome(result.status);
        }}
      >
        Delete thing
      </button>
      <output>{outcome}</output>
      {dialog}
    </>
  );
}

/** Answers like the backend: 428 without a token, success with it. */
function serverCall() {
  return vi.fn(async (options?: RequestInit) => {
    if (new Headers(options?.headers).get("Jofi-Confirmation") !== "t0k3n") throw required;
  });
}

afterEach(() => {
  overwriteGetLocale(() => "en");
});

describe("useConfirmation", () => {
  it("asks in a dialog and repeats the call with the token on confirm", async () => {
    const call = serverCall();
    render(<DeletePage call={call} />);

    await userEvent.click(screen.getByRole("button", { name: "Delete thing" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Please confirm" });
    expect(dialog).toHaveTextContent("Thing 42 will be deleted.");
    await userEvent.click(screen.getByRole("button", { name: "Confirm" }));

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("done"));
    expect(call).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("does not repeat the call when the user cancels", async () => {
    const call = serverCall();
    render(<DeletePage call={call} />);

    await userEvent.click(screen.getByRole("button", { name: "Delete thing" }));
    await userEvent.click(await screen.findByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("cancelled"));
    expect(call).toHaveBeenCalledOnce();
  });

  it("speaks German", async () => {
    overwriteGetLocale(() => "de");
    render(<DeletePage call={serverCall()} />);

    await userEvent.click(screen.getByRole("button", { name: "Delete thing" }));

    expect(await screen.findByRole("alertdialog", { name: "Bitte bestätigen" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Bestätigen" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Abbrechen" })).toBeInTheDocument();
  });
});
