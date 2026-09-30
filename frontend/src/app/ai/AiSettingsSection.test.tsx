// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { type FakeSetupState, fakeSetupBackend, model } from "../../test/fakeSetupBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const compatible = {
  id: "00000000-0000-4000-8000-0000000000c1",
  kind: "OPENAI_COMPATIBLE" as const,
  displayName: "Home server",
  baseUrl: "https://ai.example.com/v1",
  apiKeySet: true,
};

function start(setup: Partial<FakeSetupState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const fake = fakeSetupBackend({
    providers: [{ ...compatible }],
    keys: new Map([[compatible.id, "old-key"]]),
    ...setup,
  });
  server.use(...fake.handlers, ...auth.handlers);
  render(<App app={createApp(createMemoryHistory({ initialEntries: ["/settings"] }))} />);
  return { setup: fake.state, user: userEvent.setup() };
}

const card = () => screen.findByRole("article", { name: "Home server" });

describe("Settings > AI: providers", () => {
  it("shows that a key is stored, never the key itself", async () => {
    start();
    const provider = await card();
    expect(within(provider).getByText(/API key stored/)).toBeVisible();
    expect(within(provider).getByText("https://ai.example.com/v1")).toBeVisible();
    expect(document.body.innerHTML).not.toContain("old-key");
  });

  it("wants the key again when the base URL moves to another server, then keeps it", async () => {
    const { user, setup } = start();
    const provider = await card();
    await user.click(within(provider).getByRole("button", { name: "Edit" }));
    const key = within(provider).getByLabelText("API key");
    expect(key).toHaveValue("");
    expect(key).toHaveAccessibleDescription(/Leave this empty to keep it/);

    const url = within(provider).getByLabelText("Base URL");
    await user.clear(url);
    await user.type(url, "https://other.example.org/v1");
    expect(key).toHaveAccessibleDescription(/points to another server. Enter the API key again/);
    await user.click(within(provider).getByRole("button", { name: "Save changes" }));
    expect(within(provider).getByLabelText("API key")).toHaveAttribute("aria-invalid", "true");
    expect(setup.providers[0]?.baseUrl).toBe("https://ai.example.com/v1");

    await user.type(key, "new-key");
    await user.click(within(provider).getByRole("button", { name: "Save changes" }));
    expect(await within(provider).findByText("https://other.example.org/v1")).toBeVisible();
    expect(setup.keys.get(compatible.id)).toBe("new-key");
  });

  it("keeps the stored key when only the path changes", async () => {
    const { user, setup } = start();
    const provider = await card();
    await user.click(within(provider).getByRole("button", { name: "Edit" }));
    const url = within(provider).getByLabelText("Base URL");
    await user.clear(url);
    await user.type(url, "https://ai.example.com/v2");
    await user.click(within(provider).getByRole("button", { name: "Save changes" }));
    expect(await within(provider).findByText("https://ai.example.com/v2")).toBeVisible();
    expect(setup.keys.get(compatible.id)).toBe("old-key");
  });

  it("says why a connection test failed", async () => {
    const { user } = start({ refreshFails: "provider-authentication-failed" });
    const provider = await card();
    await user.click(within(provider).getByRole("button", { name: "Test connection" }));
    expect(await within(provider).findByText(/The provider refused the API key/)).toBeVisible();
  });

  it("removes a provider only after the server's confirmation", async () => {
    const { user, setup } = start();
    const provider = await card();
    await user.click(within(provider).getByRole("button", { name: "Remove" }));
    let dialog = await screen.findByRole("alertdialog", { name: "Remove this provider?" });
    expect(dialog).toHaveTextContent('Jofi removes "Home server", its stored API key');
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(setup.providers).toHaveLength(1);

    await user.click(within(provider).getByRole("button", { name: "Remove" }));
    dialog = await screen.findByRole("alertdialog", { name: "Remove this provider?" });
    const confirm = within(dialog).getByRole("button", { name: "Remove provider" });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);
    await waitFor(() =>
      expect(screen.queryByRole("article", { name: "Home server" })).not.toBeInTheDocument(),
    );
    expect(setup.deleteCalls).toEqual(["first", "first", "confirmed"]);
    expect(screen.getByRole("heading", { name: "Add a provider" })).toBeVisible();
  });

  it("explains that a provider in use cannot be removed, without asking", async () => {
    const { user, setup } = start({
      models: new Map([[compatible.id, [model("m1")]]]),
      assignments: new Map([["CHAT", { providerId: compatible.id, model: "m1" }]]),
    });
    const provider = await card();
    await user.click(within(provider).getByRole("button", { name: "Remove" }));
    expect(await within(provider).findByText(/Tasks still use this provider/)).toBeVisible();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(setup.deleteCalls).toEqual([]);
  });
});

describe("Settings > AI: budget", () => {
  it("shows this month's spending against the cap", async () => {
    start({ capMicros: 20_000_000, spentMicros: 5_125_000 });
    expect(await screen.findByText("Spent this month: $5.13 of $20.00 ($14.88 left).")).toBeVisible();
    expect(screen.getByLabelText("Monthly cap in US dollars")).toHaveValue("$20.00");
  });

  it("warns when the budget is reached", async () => {
    start({ capMicros: 1_000_000, spentMicros: 1_000_000 });
    expect(await screen.findByText("Budget reached")).toBeVisible();
    expect(screen.getByText(/Non-essential AI jobs are paused until/)).toBeVisible();
  });
});
