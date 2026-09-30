// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import type { ApplicationResponse } from "../../api/generated/jofi";
import {
  anApplication,
  type FakeApplicationState,
  fakeApplicationBackend,
} from "../../test/fakeApplicationBackend";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { aCompany, fakeCompanyBackend } from "../../test/fakeCompanyBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const acme = aCompany({ name: "ACME GmbH" });

async function start(application: ApplicationResponse, data: Partial<FakeApplicationState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const applications = fakeApplicationBackend({ applications: [application], ...data });
  const companies = fakeCompanyBackend({ companies: [acme] });
  server.use(...applications.handlers, ...companies.handlers, ...auth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: [`/applications/${application.id}`] }));
  render(<App app={app} />);
  await screen.findByRole("region", { name: "Status history" });
  return { state: applications.state, user: userEvent.setup() };
}

type User = ReturnType<typeof userEvent.setup>;

const statusCard = () => screen.getByRole("region", { name: "Status" });
const historyCard = () => screen.getByRole("region", { name: "Status history" });

/** Opens the status control's list and returns the offered statuses. */
async function openMoves(user: User) {
  await user.click(await within(statusCard()).findByRole("button", { name: /Change status$/ }));
  return screen.findByRole("listbox", { name: "Change status" });
}

async function moveTo(user: User, status: string) {
  const list = await openMoves(user);
  await user.click(within(list).getByRole("option", { name: status }));
  return screen.findByRole("dialog");
}

describe("status control", () => {
  it("offers only the moves the matrix allows, pipeline and ended under headings", async () => {
    const { user } = await start(anApplication(acme.id, { status: "APPLIED" }));
    const list = await openMoves(user);
    const offered = within(list)
      .getAllByRole("option")
      .map((option) => option.textContent);
    expect(offered).toEqual([
      "Discovered",
      "Shortlisted",
      "Preparing",
      "Interviewing",
      "Offer",
      "Rejected",
      "Withdrawn",
      "Ghosted",
    ]);
    expect(within(list).getByText("Pipeline")).toBeVisible();
    expect(within(list).getByText("Ended")).toBeVisible();
  });

  it("moves with an optional reason, then shows the new status and its history entry", async () => {
    const application = anApplication(acme.id, { status: "APPLIED", version: 3 });
    const { state, user } = await start(application);
    const dialog = await moveTo(user, "Interviewing");
    expect(within(dialog).getByRole("heading", { name: "Move to Interviewing" })).toBeVisible();
    expect(within(dialog).queryByRole("button", { name: /Reason$/ })).toBeNull();
    await user.type(within(dialog).getByLabelText("Why? (optional)"), "Recruiter **called**");
    await user.click(within(dialog).getByRole("button", { name: "Change status" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(state.statusChanges).toEqual([
      { status: "INTERVIEWING", reason: "Recruiter **called**", declineCategory: null, basedOnVersion: 3 },
    ]);
    expect(within(statusCard()).getByText("Interviewing")).toBeVisible();
    const entries = await within(historyCard()).findAllByRole("listitem");
    expect(entries).toHaveLength(2);
    const latest = entries[1] as HTMLElement;
    expect(latest).toHaveTextContent(/Applied\s*→\s*to\s*Interviewing/);
    expect(latest).toHaveTextContent("by you");
    expect(within(latest).getByText("called").tagName).toBe("STRONG");
  });

  it("asks for a decline category before declining, and the reason can be corrected afterwards", async () => {
    const { state, user } = await start(anApplication(acme.id, { status: "SHORTLISTED" }));
    let dialog = await moveTo(user, "Declined");
    await user.click(within(dialog).getByRole("button", { name: "Change status" }));
    expect(await within(dialog).findByText("Choose a reason.")).toBeVisible();
    expect(state.statusChanges).toEqual([]);

    await user.click(within(dialog).getByRole("button", { name: /Reason$/ }));
    await user.click(await screen.findByRole("option", { name: "Salary" }));
    await user.type(within(dialog).getByLabelText("In detail (optional)"), "Too low");
    await user.click(within(dialog).getByRole("button", { name: "Change status" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(state.statusChanges.at(-1)).toMatchObject({ status: "DECLINED", declineCategory: "SALARY" });
    expect(within(statusCard()).getByText("Salary")).toBeVisible();

    await user.click(within(statusCard()).getByRole("button", { name: "Correct reason" }));
    dialog = await screen.findByRole("dialog", { name: "Correct the reason" });
    expect(within(dialog).getByLabelText("In detail (optional)")).toHaveValue("Too low");
    await user.click(within(dialog).getByRole("button", { name: /Reason$/ }));
    await user.click(await screen.findByRole("option", { name: "Location" }));
    await user.click(within(dialog).getByRole("button", { name: "Save reason" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(state.statusChanges.at(-1)).toEqual({
      status: "DECLINED",
      reason: "Too low",
      declineCategory: "LOCATION",
      basedOnVersion: 1,
    });
    const entries = await within(historyCard()).findAllByRole("listitem");
    expect(entries.at(-1)).toHaveTextContent(/Declined\s*→\s*to\s*Declined/);
    expect(entries.at(-1)).toHaveTextContent("Reason: Location");
  });

  it("after a change elsewhere, says so and loads the latest version", async () => {
    const application = anApplication(acme.id, { status: "APPLIED" });
    const { state, user } = await start(application);
    await within(statusCard()).findByText("Applied");
    state.applications = [{ ...application, status: "INTERVIEWING", version: 1 }];

    const dialog = await moveTo(user, "Offer");
    await user.click(within(dialog).getByRole("button", { name: "Change status" }));
    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent("Changed meanwhile");
    expect(alert).toHaveTextContent("the status was not changed");
    await user.click(within(alert).getByRole("button", { name: "Load latest version" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(await within(statusCard()).findByText("Interviewing")).toBeVisible();
    expect(state.statusChanges).toEqual([]);
  });

  it("names a move the server refuses", async () => {
    const application = anApplication(acme.id, { status: "APPLIED" });
    const { state, user } = await start(application);
    await within(statusCard()).findByText("Applied");
    // Declined elsewhere without a new version reaching this page: the server refuses Withdrawn.
    state.applications = [{ ...application, status: "DECLINED" }];

    const dialog = await moveTo(user, "Withdrawn");
    await user.click(within(dialog).getByRole("button", { name: "Change status" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "An application cannot move from Applied to Withdrawn.",
    );
  });
});

describe("status history", () => {
  it("shows every change with its actor, time and sanitised reason", async () => {
    const application = anApplication(acme.id, { status: "REJECTED" });
    await start(application, {
      history: {
        [application.id]: [
          {
            from: null,
            to: "DISCOVERED",
            actor: { kind: "SCANNER", name: "Bundesagentur" },
            at: "2026-09-01T08:00:00Z",
          },
          {
            from: "DISCOVERED",
            to: "APPLIED",
            actor: { kind: "EXTERNAL_CLIENT", name: "Claude Desktop" },
            at: "2026-09-02T08:00:00Z",
          },
          {
            from: "APPLIED",
            to: "REJECTED",
            actor: { kind: "AI" },
            at: "2026-09-03T08:00:00Z",
            declineCategory: "POSITION_FILLED",
            reason: 'Filled *internally* <img src="https://tracker.example/p.gif">',
          },
        ],
      },
    });
    const entries = await within(historyCard()).findAllByRole("listitem");
    expect(entries).toHaveLength(3);
    const [first, second, third] = entries as [HTMLElement, HTMLElement, HTMLElement];
    expect(first).toHaveTextContent(/Created as\s*Discovered/);
    expect(first).toHaveTextContent("by a scanner (Bundesagentur)");
    expect(second).toHaveTextContent("by an external client (Claude Desktop)");
    expect(third).toHaveTextContent("by the AI");
    expect(third).toHaveTextContent("Reason: Position filled");
    expect(within(third).getByText("internally").tagName).toBe("EM");
    expect(third.querySelector("img")).toBeNull();
    const time = third.querySelector("time");
    expect(time).toHaveAttribute("dateTime", "2026-09-03T08:00:00Z");
    expect(third).toHaveTextContent(/Sep 3, 2026/);
  });

  // A 5xx is retried twice (1 s, then 2 s) before the page gives up.
  it("says when the history cannot be loaded, and tries again", { timeout: 10_000 }, async () => {
    const { state, user } = await start(anApplication(acme.id), { historyFails: true });
    const failed = await within(historyCard()).findByText(
      "The status history could not be loaded.",
      {},
      { timeout: 5_000 },
    );
    expect(failed).toBeVisible();
    state.historyFails = false;
    await user.click(within(historyCard()).getByRole("button", { name: "Try again" }));
    expect(await within(historyCard()).findAllByRole("listitem")).toHaveLength(1);
  });
});
