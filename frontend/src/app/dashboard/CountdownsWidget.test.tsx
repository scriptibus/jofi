// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { getLocale, overwriteGetLocale } from "../../paraglide/runtime.js";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import {
  aCountdown,
  aDashboardCountdown,
  type FakeCountdownState,
  fakeCountdownBackend,
} from "../../test/fakeCountdownBackend";
import { App, createApp } from "../App";
import { dismissSetupGuide } from "../ai/setupGuide";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

// Noon UTC on Wednesday, 30 September 2026: the same day in every zone a test machine runs in.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-09-30T12:00:00Z"));
});
const originalLocale = getLocale;
afterEach(() => {
  vi.useRealTimers();
  overwriteGetLocale(originalLocale);
});

const deadline = aDashboardCountdown({ title: "Platform Engineer", targetDate: "2026-10-10" });
const offer = aDashboardCountdown({
  source: "OFFER_ANSWER_DEADLINE",
  title: "Site Reliability Engineer",
  targetDate: "2026-10-01",
});
const today = aDashboardCountdown({ title: "Data Engineer", targetDate: "2026-09-30" });

/** The dashboard, as the app routes to it for a logged-in user who skipped the setup guide. */
function start(data: Partial<FakeCountdownState> = {}) {
  dismissSetupGuide();
  const countdowns = fakeCountdownBackend(data);
  server.use(...countdowns.handlers, ...fakeAuthBackend({ authenticated: true }).handlers);
  render(<App app={createApp(createMemoryHistory({ initialEntries: ["/"] }))} />);
  return { state: countdowns.state, user: userEvent.setup() };
}

const widget = () => screen.findByRole("region", { name: "Countdowns" });

async function rows() {
  const list = await within(await widget()).findByRole("list", { name: "Countdowns" });
  return within(list).getAllByRole("listitem");
}

describe("countdowns widget", () => {
  it("shows each countdown on the dashboard with its kind, the days left and its date", async () => {
    const notice = aCountdown({ title: "End of notice period", targetDate: "2026-12-31" });
    const passed = aCountdown({ title: "Probation ends", targetDate: "2026-09-01" });
    start({ derived: [today, offer, deadline], countdowns: [notice, passed] });

    const [first, second, third, fourth, fifth] = await rows();
    expect(first).toHaveTextContent(/^TodayApplication deadlineData EngineerSep 30, 2026$/);
    expect(second).toHaveTextContent(/^TomorrowOffer answer deadlineSite Reliability EngineerOct 1, 2026$/);
    expect(third).toHaveTextContent(/^In 10 daysApplication deadlinePlatform EngineerOct 10, 2026$/);
    expect(fourth).toHaveTextContent(/^In 92 daysYour countdownEnd of notice periodDec 31, 2026Delete$/);
    expect(fifth).toHaveTextContent(/^ReachedYour countdownProbation endsSep 1, 2026Delete$/);
    // A deadline opens its application; only the user's own countdowns can be deleted.
    expect(within(third as HTMLElement).getByRole("link", { name: "Platform Engineer" })).toHaveAttribute(
      "href",
      `/applications/${deadline.subjectId}`,
    );
    expect(within(third as HTMLElement).queryByRole("button")).toBeNull();
  });

  it("shows the next interview at its time, without a link it could not follow", async () => {
    const interview = aDashboardCountdown({
      source: "NEXT_INTERVIEW",
      title: "Backend Engineer",
      targetDate: null,
      targetAt: "2026-10-05T12:00:00Z",
      localTarget: "2026-10-05T12:00:00",
      timeZone: "UTC",
      subjectType: "interview",
    });
    start({ derived: [interview] });
    const [row] = await rows();
    expect(row).toHaveTextContent(/^In 5 daysNext interviewBackend Engineer/);
    expect(within(row as HTMLElement).queryByRole("link")).toBeNull();
  });

  it("speaks German, with German dates", async () => {
    overwriteGetLocale(() => "de");
    start({ derived: [deadline] });
    const section = await screen.findByRole("region", { name: "Countdowns" });
    expect(await within(section).findByText("In 10 Tagen")).toBeVisible();
    expect(within(section).getByText("Bewerbungsfrist")).toBeVisible();
    expect(within(section).getByText("10.10.2026")).toBeVisible();
  });

  it("explains the empty state of a fresh instance and still offers to add one", async () => {
    start();
    const section = await widget();
    expect(await within(section).findByText(/^No countdowns yet\./)).toBeVisible();
    expect(within(section).queryByRole("list")).toBeNull();
    expect(within(section).getByRole("form", { name: "Add a countdown" })).toBeVisible();
  });

  it("offers to try again when the countdowns cannot be loaded", { timeout: 10_000 }, async () => {
    const { state, user } = start({ unavailable: true, derived: [deadline] });
    const section = await widget();
    const alert = await within(section).findByRole("alert", {}, { timeout: 6000 });
    expect(alert).toHaveTextContent("The countdowns could not be loaded.");

    state.unavailable = false;
    await user.click(within(alert).getByRole("button", { name: "Try again" }));
    expect(await within(section).findByText("Platform Engineer")).toBeVisible();
  });

  it("asks for the countdowns on the viewer's calendar", async () => {
    const { state } = start();
    await within(await widget()).findByText(/^No countdowns yet\./);
    expect(state.listZones).toEqual([Intl.DateTimeFormat().resolvedOptions().timeZone]);
  });

  it("adds a custom countdown: it shows in the list and the form is empty again", async () => {
    const { state, user } = start();
    const form = within(await widget()).getByRole("form", { name: "Add a countdown" });
    await user.type(within(form).getByLabelText("Counting down to (required)"), "  End of notice period ");
    await user.type(within(form).getByLabelText("Date (required)"), "2026-12-31");
    await user.click(within(form).getByRole("button", { name: "Add countdown" }));

    expect(await screen.findByRole("status")).toHaveTextContent("Countdown “End of notice period” added.");
    expect(state.creates).toEqual([{ title: "End of notice period", targetDate: "2026-12-31" }]);
    const [row] = await rows();
    expect(row).toHaveTextContent(/^In 92 daysYour countdownEnd of notice periodDec 31, 2026Delete$/);
    expect(within(form).getByLabelText("Counting down to (required)")).toHaveValue("");
    expect(within(form).getByLabelText("Date (required)")).toHaveValue("");
  });

  it("asks for a title and a date before it sends anything, and shows the server's objections", async () => {
    const { state, user } = start();
    const form = within(await widget()).getByRole("form", { name: "Add a countdown" });
    await user.click(within(form).getByRole("button", { name: "Add countdown" }));
    expect(await within(form).findAllByText("Enter a value.")).toHaveLength(2);
    expect(state.creates).toEqual([]);

    await user.type(within(form).getByLabelText("Counting down to (required)"), "Too early");
    await user.type(within(form).getByLabelText("Date (required)"), "1999-01-01");
    await user.click(within(form).getByRole("button", { name: "Add countdown" }));
    expect(await within(form).findByText("Enter a date between 2000 and 2099.")).toBeVisible();
    expect(state.creates).toEqual([]);
  });

  it("deletes a custom countdown only after confirming", async () => {
    const notice = aCountdown({ title: "End of notice period" });
    const { state, user } = start({ countdowns: [notice] });
    const deleteButton = await within(await widget()).findByRole("button", {
      name: "Delete countdown: End of notice period",
    });

    await user.click(deleteButton);
    let dialog = await screen.findByRole("alertdialog", { name: "Delete this countdown?" });
    expect(dialog).toHaveTextContent("The countdown “End of notice period” will be deleted.");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(state.deleteCalls).toEqual(["first"]);
    expect(state.countdowns).toHaveLength(1);

    await user.click(deleteButton);
    dialog = await screen.findByRole("alertdialog", { name: "Delete this countdown?" });
    const confirm = within(dialog).getByRole("button", { name: "Delete countdown" });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);

    expect(await screen.findByRole("status")).toHaveTextContent("Countdown “End of notice period” deleted.");
    expect(state.deleteCalls).toEqual(["first", "first", "confirmed"]);
    expect(await within(await widget()).findByText(/^No countdowns yet\./)).toBeVisible();
    expect(screen.getByRole("heading", { level: 2, name: "Countdowns" })).toHaveFocus();
  });

  it("says so when the countdown was deleted elsewhere meanwhile", async () => {
    const notice = aCountdown({ title: "End of notice period" });
    const { state, user } = start({ countdowns: [notice] });
    const deleteButton = await within(await widget()).findByRole("button", {
      name: "Delete countdown: End of notice period",
    });
    state.countdowns = [];
    await user.click(deleteButton);
    expect(await screen.findByText("This countdown does not exist (any more).")).toBeVisible();
  });
});
