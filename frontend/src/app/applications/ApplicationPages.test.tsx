// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
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

function start(path: string, data: Partial<FakeApplicationState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const applications = fakeApplicationBackend(data);
  const companies = fakeCompanyBackend({ companies: [acme] });
  server.use(...applications.handlers, ...companies.handlers, ...auth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: [path] }));
  render(<App app={app} />);
  return { state: applications.state, router: app.router, user: userEvent.setup() };
}

type User = ReturnType<typeof userEvent.setup>;

/** Replaces a field's value at once, then leaves it (number fields take the value on blur). */
async function fill(user: User, input: HTMLElement, text: string) {
  await user.clear(input);
  await user.click(input);
  await user.paste(text);
  await user.tab();
}

const full = anApplication(acme.id, {
  title: "Platform Engineer",
  location: "Berlin",
  remoteShare: 60,
  employmentType: "FULL_TIME",
  seniority: "SENIOR",
  deadline: "2026-10-15",
  howApplied: "PORTAL",
  portalNotes: 'Login via **Workday**. <img src="https://tracker.example/p.gif">',
  payBand: {
    min: 70000,
    max: 85000.5,
    currency: "EUR",
    period: "YEAR",
    source: "ESTIMATED",
    estimateBasis: "Salary survey, Berlin, senior",
    estimateConfidence: "MEDIUM",
  },
  languageAndTone: {
    postingLanguage: "de-CH",
    applicationLanguage: null,
    formOfAddress: "SIE",
    tone: "PROFESSIONAL",
  },
  status: "DECLINED",
  declineReason: { category: "SALARY", text: "Too **low**" },
  sources: [
    {
      id: crypto.randomUUID(),
      kind: "URL",
      originalUrl: "https://jobs.example/platform?ref=a&b=c",
      discoveredAt: "2026-09-01T08:00:00Z",
      online: true,
    },
    {
      id: crypto.randomUUID(),
      kind: "SCANNER",
      originalUrl: "javascript:alert(1)",
      discoveredAt: "2026-09-02T08:00:00Z",
      offlineSince: "2026-09-20T08:00:00Z",
      online: false,
    },
  ],
  offer: {
    salary: { amount: 80000, currency: "CHF", period: "YEAR" },
    vacationDays: 30,
    answerBy: "2026-10-01",
  },
});

describe("Application detail", () => {
  it("shows every detail on the overview, formatted for the user and rendered safely", async () => {
    start(`/applications/${full.id}`, { applications: [full] });
    expect(await screen.findByRole("heading", { level: 1, name: "Platform Engineer" })).toBeVisible();

    const facts = screen.getByRole("region", { name: "Details" });
    expect(await within(facts).findByRole("link", { name: "ACME GmbH" })).toHaveAttribute(
      "href",
      `/companies/${acme.id}`,
    );
    expect(within(facts).getByText("60%")).toBeVisible();
    expect(within(facts).getByText("Full-time")).toBeVisible();
    expect(within(facts).getByText("Senior")).toBeVisible();

    const status = screen.getByRole("region", { name: "Status" });
    expect(within(status).getByText("Declined")).toBeVisible();
    expect(within(status).getByText("Salary")).toBeVisible();
    expect(within(status).getByText("low").tagName).toBe("STRONG");

    const pay = screen.getByRole("region", { name: "Pay band" });
    expect(within(pay).getByText("€70,000 – €85,000.50 per year")).toBeVisible();
    expect(within(pay).getByText("Estimated")).toBeVisible();
    expect(within(pay).getByText("Medium")).toBeVisible();
    expect(within(pay).getByText("Salary survey, Berlin, senior")).toBeVisible();

    const language = screen.getByRole("region", { name: "Language & tone" });
    expect(within(language).getByText(/Swiss High German/)).toHaveTextContent("Swiss High German (de-CH)");
    expect(within(language).getByText("Same as the posting")).toBeVisible();
    expect(within(language).getByText("Sie")).toBeVisible();
    expect(within(language).getByText("Professional")).toBeVisible();

    const scores = screen.getByRole("region", { name: "Scores" });
    expect(within(scores).getAllByText("Available with scoring")).toHaveLength(2);

    const sources = screen.getByRole("region", { name: "Sources" });
    const link = within(sources).getByRole("link", { name: "https://jobs.example/platform?ref=a&b=c" });
    expect(link).toHaveAttribute("rel", "noopener noreferrer nofollow");
    expect(within(sources).getByText("javascript:alert(1)").closest("a")).toBeNull();
    expect(within(sources).getByText(/Found on Sep 2, 2026 · offline since Sep 20, 2026/)).toBeVisible();

    const applying = screen.getByRole("region", { name: "Applying" });
    expect(within(applying).getByText("Oct 15, 2026")).toBeVisible();
    expect(within(applying).getByText("Workday").tagName).toBe("STRONG");
    expect(document.body.innerHTML).not.toContain("tracker.example");

    const offer = screen.getByRole("region", { name: "Offer" });
    expect(within(offer).getByText("CHF 80,000 per year")).toBeVisible();
    expect(within(offer).getByText("30")).toBeVisible();
  });

  it("has keyboard tabs with the later sections disabled, and ignores an unknown tab in the URL", async () => {
    const application = anApplication(acme.id);
    const { user, router } = start(`/applications/${application.id}?tab=timeline`, {
      applications: [application],
    });
    const overview = await screen.findByRole("tab", { name: "Overview" });
    expect(overview).toHaveAttribute("aria-selected", "true");
    for (const name of ["Description", "Contacts"])
      expect(screen.getByRole("tab", { name })).not.toHaveAttribute("aria-disabled");
    expect(screen.getByRole("tab", { name: "Timeline" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("tabpanel", { name: "Overview" })).toBeVisible();

    await user.click(overview);
    await user.keyboard("{ArrowRight}");
    const description = screen.getByRole("tab", { name: "Description" });
    expect(description).toHaveFocus();
    expect(description).toHaveAttribute("aria-selected", "true");
    await user.keyboard("{ArrowRight}");
    const contacts = screen.getByRole("tab", { name: "Contacts" });
    expect(contacts).toHaveFocus();
    expect(contacts).toHaveAttribute("aria-selected", "true");
    expect(await screen.findByRole("tabpanel", { name: "Contacts" })).toBeVisible();
    expect(router.state.location.search).toEqual({ tab: "contacts" });
    // The disabled tab is skipped: the next one is Overview again.
    await user.keyboard("{ArrowRight}");
    expect(overview).toHaveFocus();
  });

  it("marks an unread application read once when opened, and lets the user mark it unread again", async () => {
    const application = anApplication(acme.id, { unread: true, version: 4 });
    const { user, state } = start(`/applications/${application.id}`, { applications: [application] });
    const toggle = await screen.findByRole("button", { name: "Mark as unread" });
    expect(state.unreadCalls).toEqual([false]);
    expect(screen.queryByText("Unread")).toBeNull();

    await user.click(toggle);
    expect(await screen.findByText("Unread")).toBeVisible();
    expect(screen.getByRole("button", { name: "Mark as read" })).toBeVisible();
    expect(state.unreadCalls).toEqual([false, true]);
    expect(state.applications[0]).toMatchObject({ unread: true, version: 4 });
  });

  it("leaves a read application alone when opened", async () => {
    const application = anApplication(acme.id);
    const { state } = start(`/applications/${application.id}`, { applications: [application] });
    expect(await screen.findByRole("button", { name: "Mark as unread" })).toBeVisible();
    expect(state.unreadCalls).toEqual([]);
  });

  it("says when the application does not exist", async () => {
    start(`/applications/${crypto.randomUUID()}`);
    expect(await screen.findByRole("heading", { level: 1, name: "Application not found" })).toBeVisible();
  });

  it("deletes only after the confirmation, which names what goes with the application", async () => {
    const application = anApplication(acme.id, { title: "Data Engineer" });
    const { user, state, router } = start(`/applications/${application.id}`, {
      applications: [application],
      cascade: {
        [application.id]: { contactLinks: 1, statusChanges: 3, sources: 2, snapshots: 0, interviews: 2 },
      },
    });
    await user.click(await screen.findByRole("button", { name: "Delete…" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Delete this application?" });
    expect(dialog).toHaveTextContent(
      "Data Engineer will be deleted with its link to 1 contact, 3 status changes, 2 sources, and 2 interviews and calls. This cannot be undone.",
    );
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(state.applications).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Delete…" }));
    const again = await screen.findByRole("alertdialog", { name: "Delete this application?" });
    const confirm = within(again).getByRole("button", { name: "Delete application" });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);
    await waitFor(() => expect(router.state.location.pathname).toBe("/applications"));
    expect(state.applications).toEqual([]);
    expect(state.deleteCalls).toEqual(["first", "first", "confirmed"]);
  });
});

describe("Edit", () => {
  it("saves every group of fields with the version it was opened with", async () => {
    const application = anApplication(acme.id, { title: "Engineer", version: 2 });
    const { user, state } = start(`/applications/${application.id}/edit`, { applications: [application] });
    await fill(user, await screen.findByLabelText("Job title (required)"), "  Staff Engineer ");
    await fill(
      user,
      within(screen.getByRole("group", { name: "Job" })).getByLabelText("Remote share in percent"),
      "40",
    );
    const pay = screen.getByRole("group", { name: "Pay band" });
    await fill(user, within(pay).getByLabelText("Minimum"), "65000");
    await fill(user, within(pay).getByLabelText("Maximum"), "80000.5");
    await fill(user, within(pay).getByLabelText(/^Currency/), "chf");
    await user.click(within(pay).getByText("Month"));
    await user.click(within(pay).getByText("Estimated"));
    await fill(user, within(pay).getByLabelText(/^Basis of the estimate/), "Friends at similar companies");
    await user.click(within(pay).getByRole("button", { name: /Confidence$/ }));
    await user.click(await screen.findByRole("option", { name: "High" }));
    const language = screen.getByRole("group", { name: "Language & tone" });
    await fill(user, within(language).getByLabelText(/^Posting language/), "de");
    expect(within(language).getByLabelText(/^Posting language/)).toHaveAccessibleDescription(
      "Recognised: German",
    );
    await user.click(within(language).getByText("Du"));
    const offer = screen.getByRole("group", { name: "Offer" });
    await fill(user, within(offer).getByLabelText("Vacation days"), "28");

    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Staff Engineer" })).toBeVisible();
    expect(state.updates).toHaveLength(1);
    expect(state.updates[0]).toMatchObject({
      basedOnVersion: 2,
      details: {
        title: "Staff Engineer",
        companyId: acme.id,
        remoteShare: 40,
        payBand: {
          min: 65000,
          max: 80000.5,
          currency: "CHF",
          period: "MONTH",
          source: "ESTIMATED",
          estimateBasis: "Friends at similar companies",
          estimateConfidence: "HIGH",
        },
        languageAndTone: {
          postingLanguage: "de",
          applicationLanguage: null,
          formOfAddress: "DU",
          tone: null,
        },
        offer: { salary: null, vacationDays: 28, bonus: null },
      },
    });
  });

  it("shows the server's error next to the nested field it names", async () => {
    const application = anApplication(acme.id, {
      payBand: { min: 50000, max: null, currency: "EUR", period: "YEAR", source: "POSTING" },
    });
    const { user, state } = start(`/applications/${application.id}/edit`, {
      applications: [application],
      refusePayMax: { value: 60000.25, problem: "TOO_PRECISE" },
    });
    const pay = await screen.findByRole("group", { name: "Pay band" });
    await fill(user, within(pay).getByLabelText("Maximum"), "60000.25");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await within(pay).findByText("Use at most two decimals.")).toBeVisible();
    expect(within(pay).getByLabelText("Maximum")).toHaveAttribute("aria-invalid", "true");
    expect(state.updates).toEqual([]);
  });

  it("checks the maximum against the minimum before sending", async () => {
    const application = anApplication(acme.id);
    const { user, state } = start(`/applications/${application.id}/edit`, { applications: [application] });
    const pay = await screen.findByRole("group", { name: "Pay band" });
    await fill(user, within(pay).getByLabelText("Minimum"), "70000");
    await fill(user, within(pay).getByLabelText("Maximum"), "60000");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await within(pay).findByText("The maximum cannot be below the minimum.")).toBeVisible();
    expect(state.updates).toEqual([]);
  });

  it("refuses a save based on an old version, then loads the latest one to edit again", async () => {
    const application = anApplication(acme.id, { title: "Engineer", version: 3 });
    const { user, state } = start(`/applications/${application.id}/edit`, { applications: [application] });
    const location = await screen.findByLabelText("Location");
    // Meanwhile, another tab renames the application.
    state.applications = [{ ...application, title: "Lead Engineer", version: 4 }];

    await fill(user, location, "Hamburg");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Changed meanwhile");
    expect(state.updates).toEqual([]);

    await user.click(within(alert).getByRole("button", { name: "Load latest version" }));
    expect(await screen.findByDisplayValue("Lead Engineer")).toBeVisible();
    await fill(user, screen.getByLabelText("Location"), "Hamburg");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Lead Engineer" })).toBeVisible();
    expect(state.updates[0]).toMatchObject({ basedOnVersion: 4, details: { location: "Hamburg" } });
  });

  it("sends no pay band or offer when their amounts are empty", async () => {
    const application = anApplication(acme.id, {
      payBand: { min: 50000, max: null, currency: "EUR", period: "YEAR", source: "POSTING" },
      offer: { vacationDays: 25 },
    });
    const { user, state } = start(`/applications/${application.id}/edit`, { applications: [application] });
    await user.clear(await screen.findByLabelText("Minimum"));
    await user.clear(screen.getByLabelText("Vacation days"));
    await user.tab();
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(state.updates).toHaveLength(1));
    expect(state.updates[0]?.details).toMatchObject({ payBand: null, offer: null });
  });
});
