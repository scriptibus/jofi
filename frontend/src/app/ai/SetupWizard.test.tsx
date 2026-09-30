// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { type FakeSetupState, fakeSetupBackend, model, privacyInfo } from "../../test/fakeSetupBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

type User = ReturnType<typeof userEvent.setup>;

function start(path: string, setup: Partial<FakeSetupState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const fake = fakeSetupBackend(setup);
  server.use(...fake.handlers, ...auth.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: [path] }));
  render(<App app={app} />);
  return { setup: fake.state, router: app.router, user: userEvent.setup() };
}

/** Picks an option in one of our Selects: the button is named by its value and its label. */
async function pick(user: User, label: string, option: string) {
  await user.click(screen.getByRole("button", { name: (name) => name.endsWith(label) }));
  await user.click(await screen.findByRole("option", { name: option }));
}

const existing = {
  id: "00000000-0000-4000-8000-0000000000aa",
  kind: "ANTHROPIC" as const,
  displayName: "Claude",
  baseUrl: null,
  apiKeySet: true,
};

describe("setup guide: when it opens", () => {
  it("opens after login while no provider is configured; skipping keeps it closed in this browser", async () => {
    const { user, router } = start("/");
    expect(await screen.findByRole("heading", { level: 1, name: "Set up AI" })).toBeVisible();
    expect(router.state.location.pathname).toBe("/setup");
    expect(screen.getByText(/I need an AI to think with/)).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Skip for now" }));
    expect(await screen.findByRole("heading", { level: 1, name: /donkey work/ })).toBeVisible();

    await router.navigate({ to: "/settings" });
    await router.navigate({ to: "/" });
    expect(await screen.findByRole("heading", { level: 1, name: /donkey work/ })).toBeVisible();
    expect(router.state.location.pathname).toBe("/");
  });

  it("does not open once a provider exists", async () => {
    const { router } = start("/", { providers: [existing] });
    expect(await screen.findByRole("heading", { level: 1, name: /donkey work/ })).toBeVisible();
    expect(router.state.location.pathname).toBe("/");
  });

  it("can be resumed from Settings > AI", async () => {
    const { user, router } = start("/settings", { providers: [existing] });
    await user.click(await screen.findByRole("button", { name: "Open the setup guide" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Set up AI" })).toBeVisible();
    expect(router.state.location.search).toEqual({ step: "welcome" });
  });
});

describe("setup guide: a full run", () => {
  it("adds a provider, tests it, assigns models with warnings, sets a budget and sums up", async () => {
    const listed = [model("big-model", ["TOOL_USE", "STREAMING"], 200_000), model("tiny-local", [], 4_096)];
    const { user, setup, router } = start("/setup", { listed });
    await user.click(await screen.findByRole("button", { name: "Let's start" }));

    // Providers: privacy first, then base URL and key.
    expect(await screen.findByRole("heading", { level: 2, name: "Choose a provider" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
    await user.click(screen.getByText("OpenAI-compatible endpoint", { exact: true }));
    const privacy = screen.getByRole("region", { name: "Privacy with OpenAI-compatible endpoint" });
    expect(await within(privacy).findByText("ZDR for OPENAI_COMPATIBLE on request.")).toBeVisible();
    expect(within(privacy).getByText("On request")).toBeVisible();
    expect(
      within(privacy).getByText("Checked on September 30, 2026 against the provider's own pages."),
    ).toBeVisible();
    const source = within(privacy).getAllByRole("link", {
      name: "docs.example.com/openai_compatible/privacy",
    })[0];
    expect(source).toHaveAttribute("href", "https://docs.example.com/openai_compatible/privacy");
    expect(source).toHaveAttribute("rel", "noopener noreferrer nofollow");
    expect(source).toHaveAttribute("target", "_blank");
    expect(within(privacy).getByRole("note")).toHaveTextContent(
      "Check the terms yourself" + "This is a summary of the provider's published terms",
    );

    await user.type(screen.getByLabelText("Base URL"), "http://ollama.lan:11434/v1");
    expect(screen.getByText("This connection is not encrypted")).toBeVisible();
    const key = screen.getByLabelText("API key");
    expect(key).toHaveAttribute("type", "password");
    await user.type(key, "sk-secret-key");
    await user.click(screen.getByRole("button", { name: "Add provider" }));

    const card = await screen.findByRole("article", { name: "OpenAI-compatible endpoint" });
    expect(within(card).getByText(/API key stored/)).toBeVisible();
    expect(setup.keys.get(setup.providers[0]?.id ?? "")).toBe("sk-secret-key");
    expect(document.body.innerHTML).not.toContain("sk-secret-key");
    expect(JSON.stringify({ ...window.localStorage, ...window.sessionStorage })).not.toContain(
      "sk-secret-key",
    );

    await user.click(within(card).getByRole("button", { name: "Test connection" }));
    expect(await within(card).findByText("Connected. The provider lists 2 models.")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Continue" }));

    // Tasks: suggestions for what fits, a warning for a weak model.
    expect(await screen.findByRole("heading", { level: 2, name: "Models per task" })).toBeVisible();
    await user.click(await screen.findByRole("button", { name: /Use suggested models for open tasks/ }));
    await waitFor(() => expect(setup.assignments.get("CHAT")?.model).toBe("big-model"));
    expect(setup.assignments.get("CLASSIFICATION")?.model).toBe("big-model");
    await pick(user, "Classification", "tiny-local");
    expect(await screen.findByText(/it lacks a context of at least 8,192 tokens/)).toBeVisible();
    expect(setup.assignments.get("CLASSIFICATION")?.model).toBe("tiny-local");
    await user.click(screen.getByRole("button", { name: "Continue" }));

    // Budget: dollars in, micros out; removing sends null explicitly.
    const cap = await screen.findByLabelText("Monthly cap in US dollars");
    await user.type(cap, "12.5");
    await user.click(screen.getByRole("button", { name: "Save cap" }));
    expect(await screen.findByText("Monthly cap set to $12.50.")).toBeVisible();
    expect(setup.capMicros).toBe(12_500_000);
    await user.click(screen.getByRole("button", { name: "Remove cap" }));
    expect(await screen.findByText("The monthly cap is removed.")).toBeVisible();
    expect(setup.budgetBodies).toEqual([{ capMicros: 12_500_000 }, { capMicros: null }]);
    await user.click(screen.getByRole("button", { name: "Continue" }));

    // Summary.
    const summary = await screen.findByRole("region", { name: "All set" });
    expect(within(summary).getByText("OpenAI-compatible endpoint")).toBeVisible();
    expect(within(summary).getByText("8 of 11")).toBeVisible();
    expect(within(summary).getByText("None")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Go to the dashboard" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
  });

  it("refuses a base URL with a query before sending anything", async () => {
    const { user, setup } = start("/setup?step=providers");
    await user.click(await screen.findByText("OpenAI-compatible endpoint", { exact: true }));
    await user.type(screen.getByLabelText("Base URL"), "https://example.com/v1?key=abc");
    await user.click(screen.getByRole("button", { name: "Add provider" }));
    expect(await screen.findByText(/Leave out user names, passwords/)).toBeVisible();
    expect(setup.providers).toEqual([]);
  });
});

describe("setup guide: provider privacy info", () => {
  it("shows each claim with its status and summary for the chosen kind", async () => {
    const { user } = start("/setup?step=providers");
    const privacy = await screen.findByRole("region", { name: "Privacy with Anthropic Claude" });
    expect(await within(privacy).findByText("ZDR for ANTHROPIC on request.")).toBeVisible();
    expect(within(privacy).getByText("Zero data retention")).toBeVisible();
    expect(within(privacy).getByText("No training on your data")).toBeVisible();
    expect(within(privacy).getByText("Not stated")).toBeVisible();
    expect(
      within(privacy).getByText("Quote from https://docs.example.com/anthropic/privacy/regions"),
    ).toHaveAttribute("lang", "en");
    await user.click(screen.getByText("Mistral", { exact: true }));
    expect(await screen.findByText("ZDR for MISTRAL on request.")).toBeVisible();
  });

  it("warns when the details were checked too long ago", async () => {
    start("/setup?step=providers", { privacy: privacyInfo(["ANTHROPIC"]) });
    const privacy = await screen.findByRole("region", { name: "Privacy with Anthropic Claude" });
    expect(await within(privacy).findByText("These details may be out of date")).toBeVisible();
    expect(within(privacy).getByText(/checked more than 6 months ago/)).toBeVisible();
    expect(within(privacy).getByText(/Checked on January 15, 2025/)).toBeVisible();
  });

  it("falls back to what is certain, with the disclaimer, when the info cannot be loaded", async () => {
    start("/setup?step=providers", { privacy: null });
    const privacy = await screen.findByRole("region", { name: "Privacy with Anthropic Claude" });
    expect(await within(privacy).findByText(/goes to this provider's servers/)).toBeVisible();
    expect(within(privacy).getByRole("note")).toHaveTextContent(
      "This is a summary of the provider's published terms",
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
