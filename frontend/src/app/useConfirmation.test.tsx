// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ConfirmationEffect, ConfirmedOutcome } from "../api/confirmation";
import { ApiProblemError } from "../api/fetcher";
import { overwriteGetLocale } from "../paraglide/runtime.js";
import { type ConfirmationPrompt, useConfirmation } from "./useConfirmation";

const required = (id: string) =>
  new ApiProblemError(428, {
    type: "urn:jofi:problem:shared:confirmation-required",
    status: 428,
    confirmationToken: `t0k3n-${id}`,
    expiresAt: "2026-09-30T10:05:00Z",
    operation: "things.delete",
    targets: [id],
    effect: { kind: "thing", name: `Thing ${id}`, counts: { parts: 2 } },
  } as never);

const prompt = (id: string): ConfirmationPrompt => ({
  expect: { operation: "things.delete", targets: [id] },
  describe: (effect: ConfirmationEffect) =>
    `${effect.name} and its ${effect.counts.parts} parts will be deleted.`,
});

/** Answers like the backend: 428 without the right token, success with it. */
function serverCall(id = "42") {
  return vi.fn(async (options?: RequestInit) => {
    if (new Headers(options?.headers).get("Jofi-Confirmation") !== `t0k3n-${id}`) throw required(id);
  });
}

type Outcomes = Record<string, string>;

/** A page with destructive buttons, the way a feature uses the hook. */
function DeletePage({ calls }: { calls: Record<string, (options?: RequestInit) => Promise<void>> }) {
  const { confirmed, dialog } = useConfirmation();
  const [outcomes, setOutcomes] = useState<Outcomes>({});
  const run = async (id: string) => {
    const call = calls[id];
    if (call === undefined) return;
    const result: ConfirmedOutcome<void> = await confirmed(call, prompt(id));
    setOutcomes((previous) => ({ ...previous, [id]: result.status }));
  };
  return (
    <>
      {Object.keys(calls).map((id) => (
        <button key={id} type="button" onClick={() => run(id)}>
          Delete thing {id}
        </button>
      ))}
      <output>{JSON.stringify(outcomes)}</output>
      {dialog}
    </>
  );
}

async function confirmWhenArmed() {
  const confirm = await screen.findByRole("button", { name: "Confirm" });
  await waitFor(() => expect(confirm).toBeEnabled());
  await userEvent.click(confirm);
}

afterEach(() => {
  overwriteGetLocale(() => "en");
});

describe("useConfirmation", () => {
  it("shows the server's effect and repeats the call with the token on confirm", async () => {
    const call = serverCall();
    render(<DeletePage calls={{ "42": call }} />);

    await userEvent.click(screen.getByRole("button", { name: "Delete thing 42" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Please confirm" });
    expect(dialog).toHaveTextContent("Thing 42 and its 2 parts will be deleted.");
    await confirmWhenArmed();

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent('{"42":"done"}'));
    expect(call).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("does not repeat the call when the user cancels", async () => {
    const call = serverCall();
    render(<DeletePage calls={{ "42": call }} />);

    await userEvent.click(screen.getByRole("button", { name: "Delete thing 42" }));
    await userEvent.click(await screen.findByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent('{"42":"cancelled"}'));
    expect(call).toHaveBeenCalledOnce();
  });

  it("cancels an earlier question when a second one replaces it, so nothing hangs", async () => {
    const first = serverCall("1");
    const second = serverCall("2");
    render(<DeletePage calls={{ "1": first, "2": second }} />);

    await userEvent.click(screen.getByRole("button", { name: "Delete thing 1" }));
    await screen.findByText("Thing 1 and its 2 parts will be deleted.");
    // The dialog is modal, so a second question comes from code, not from the page behind it.
    screen.getByRole("button", { name: "Delete thing 2", hidden: true }).click();

    await waitFor(() =>
      expect(screen.getByRole("status", { hidden: true })).toHaveTextContent('"1":"cancelled"'),
    );
    expect(await screen.findByText("Thing 2 and its 2 parts will be deleted.")).toBeInTheDocument();
    await confirmWhenArmed();

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent('{"1":"cancelled","2":"done"}'));
    expect(first).toHaveBeenCalledOnce();
    expect(second).toHaveBeenCalledTimes(2);
  });

  it("resolves a pending question as cancelled when the page goes away", async () => {
    const call = serverCall();
    let outcome: Promise<ConfirmedOutcome<void>> | undefined;
    function Page() {
      const { confirmed, dialog } = useConfirmation();
      return (
        <>
          <button type="button" onClick={() => (outcome = confirmed(call, prompt("42")))}>
            Delete
          </button>
          {dialog}
        </>
      );
    }
    const { unmount } = render(<Page />);

    await userEvent.click(screen.getByRole("button", { name: "Delete" }));
    await screen.findByRole("alertdialog");
    unmount();

    await expect(outcome).resolves.toEqual({ status: "cancelled" });
    expect(call).toHaveBeenCalledOnce();
  });

  it("speaks German", async () => {
    overwriteGetLocale(() => "de");
    render(<DeletePage calls={{ "42": serverCall() }} />);

    await userEvent.click(screen.getByRole("button", { name: "Delete thing 42" }));

    expect(await screen.findByRole("alertdialog", { name: "Bitte bestätigen" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Bestätigen" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Abbrechen" })).toBeInTheDocument();
  });
});
