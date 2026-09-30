// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { type FakeAuthState, fakeAuthBackend } from "../test/fakeAuthBackend";
import { fakeTaskBackend } from "../test/fakeTaskBackend";
import { App, createApp } from "./App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const GOOD_PASSWORD = "correct horse battery staple";

function start(path: string, backend: Partial<FakeAuthState> = {}) {
  const fake = fakeAuthBackend(backend);
  server.use(...fake.handlers);
  const app = createApp(createMemoryHistory({ initialEntries: [path] }));
  render(<App app={app} />);
  return { ...fake, router: app.router, user: userEvent.setup() };
}

const pathname = (router: ReturnType<typeof createApp>["router"]) => router.state.location.pathname;

describe("auth guard", () => {
  it("sends a fresh instance to first run", async () => {
    const { router } = start("/applications", { setUp: false });
    expect(await screen.findByRole("heading", { level: 1, name: "Set up Jofi" })).toBeVisible();
    expect(pathname(router)).toBe("/first-run");
    expect(screen.getByText("docker compose exec app cat /data/secrets/setup-token")).toBeVisible();
  });

  it("sends a logged-out user to login and back to the page they wanted", async () => {
    const { router, user } = start("/companies");
    expect(await screen.findByRole("heading", { level: 1, name: "Welcome back" })).toBeVisible();
    expect(router.state.location.search).toEqual({ redirect: "/companies" });

    await user.type(screen.getByLabelText("Password"), GOOD_PASSWORD);
    await user.click(screen.getByRole("button", { name: "Log in" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Companies" })).toBeVisible();
    expect(pathname(router)).toBe("/companies");
  });

  it("lets a logged-in user straight in, and keeps them away from login", async () => {
    const { router } = start("/login", { authenticated: true });
    expect(
      await screen.findByRole("heading", { level: 1, name: "Let the donkey do the donkey work." }),
    ).toBeVisible();
    expect(pathname(router)).toBe("/");
    expect(screen.getByRole("navigation", { name: "Main" })).toBeVisible();
  });

  it("ignores a redirect target outside the app", async () => {
    const { router, user } = start("/login?redirect=%2F%2Fevil.example%2Fphish");
    await screen.findByRole("heading", { level: 1, name: "Welcome back" });
    await user.type(screen.getByLabelText("Password"), GOOD_PASSWORD);
    await user.click(screen.getByRole("button", { name: "Log in" }));
    await waitFor(() => expect(pathname(router)).toBe("/"));
  });

  it("returns to login when the session expires", async () => {
    const { state, router, user } = start("/", { authenticated: true });
    await screen.findByRole("navigation", { name: "Main" });
    state.sessionExpired = true;

    const nav = screen.getByRole("navigation", { name: "Main" });
    await user.click(within(nav).getByRole("link", { name: "Settings" }));

    expect(await screen.findByText("Your session has ended. Please log in again.")).toBeVisible();
    expect(pathname(router)).toBe("/login");
    expect(router.state.location.search).toMatchObject({ reason: "expired", redirect: "/settings" });
  });
});

describe("login", () => {
  it("shows a wrong password at the field", async () => {
    const { user } = start("/login");
    await user.type(await screen.findByLabelText("Password"), "not the password");
    await user.click(screen.getByRole("button", { name: "Log in" }));
    expect(await screen.findByText("Wrong password. Please try again.")).toBeVisible();
    expect(screen.getByLabelText("Password")).toHaveAttribute("aria-invalid", "true");
  });

  it("sends again after a wrong password once the field is edited", async () => {
    const { router, user } = start("/login");
    const field = await screen.findByLabelText("Password");
    await user.type(field, "not the password");
    await user.click(screen.getByRole("button", { name: "Log in" }));
    await screen.findByText("Wrong password. Please try again.");

    fireEvent.change(field, { target: { value: GOOD_PASSWORD } });
    fireEvent.click(screen.getByRole("button", { name: "Log in" }));

    await waitFor(() => expect(pathname(router)).toBe("/"));
  });

  it("asks for the password before sending anything", async () => {
    const { user } = start("/login");
    await user.click(await screen.findByRole("button", { name: "Log in" }));
    expect(await screen.findByText("Enter your password.")).toBeVisible();
  });

  it("explains the backoff and blocks the button while it lasts", async () => {
    const { user } = start("/login", { throttleSeconds: 42 });
    await user.type(await screen.findByLabelText("Password"), "guess number six");
    await user.click(screen.getByRole("button", { name: "Log in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Too many failed attempts. Please wait 42 seconds and try again.",
    );
    expect(screen.getByRole("button", { name: "Log in" })).toBeDisabled();
  });
});

describe("first run", () => {
  it("refuses a wrong setup token, then sets up with the right one", async () => {
    const { router, user } = start("/", { setUp: false });
    await user.type(await screen.findByLabelText("Password"), GOOD_PASSWORD);
    await user.type(screen.getByLabelText("Repeat the password"), GOOD_PASSWORD);
    await user.type(screen.getByLabelText("Setup token"), "wrong-token");
    await user.click(screen.getByRole("button", { name: "Create password" }));

    expect(await screen.findByText("The setup token is wrong. Copy it again from the server.")).toBeVisible();

    await user.clear(screen.getByLabelText("Setup token"));
    await user.type(screen.getByLabelText("Setup token"), "the-setup-token");
    await user.click(screen.getByRole("button", { name: "Create password" }));

    expect(await screen.findByRole("navigation", { name: "Main" })).toBeVisible();
    expect(pathname(router)).toBe("/");
  });

  it("checks the password rules before sending", async () => {
    const { user } = start("/first-run", { setUp: false });
    await user.type(await screen.findByLabelText("Password"), "too short");
    await user.type(screen.getByLabelText("Repeat the password"), "different");
    await user.type(screen.getByLabelText("Setup token"), "the-setup-token");
    await user.click(screen.getByRole("button", { name: "Create password" }));

    expect(await screen.findByText("Use at least 15 characters.")).toBeVisible();
    expect(screen.getByText("The passwords do not match.")).toBeVisible();
  });
});

describe("the shell", () => {
  it("navigates between the areas and marks the current one", async () => {
    server.use(...fakeTaskBackend().handlers);
    const { router, user } = start("/", { authenticated: true });
    const nav = await screen.findByRole("navigation", { name: "Main" });
    for (const name of ["Dashboard", "Applications", "Companies", "Tasks", "Chat", "Settings"]) {
      expect(within(nav).getByRole("link", { name })).toBeVisible();
    }

    await user.click(within(nav).getByRole("link", { name: "Tasks" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Tasks" })).toBeVisible();
    expect(within(nav).getByRole("link", { name: "Tasks" })).toHaveAttribute("aria-current", "page");
    expect(within(nav).getByRole("link", { name: "Dashboard" })).not.toHaveAttribute("aria-current");
    expect(pathname(router)).toBe("/tasks");
    expect(document.title).toBe("Tasks · Jofi");
    expect(await screen.findByRole("heading", { name: "No open tasks" })).toBeVisible();
  });

  it("logs out and says so", async () => {
    const { router, user } = start("/settings", { authenticated: true });
    const buttons = await screen.findAllByRole("button", { name: "Log out" });
    await user.click(buttons[0] as HTMLElement);

    expect(await screen.findByText("You are logged out.")).toBeVisible();
    expect(pathname(router)).toBe("/login");
  });

  it("shows unhandled problem details globally", async () => {
    start("/settings", { authenticated: true, systemInfoStatus: 409 });
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Jofi could not complete this request (HTTP 409). Please try again.");
    expect(alert).toHaveTextContent("Server message: Database on fire");
  });

  it("shows what was shared with the share target", async () => {
    start("/share?title=Kotlin%20developer&url=https%3A%2F%2Fjobs.example%2F42&text=", {
      authenticated: true,
    });
    expect(await screen.findByRole("heading", { level: 1, name: "Shared with Jofi" })).toBeVisible();
    expect(screen.getByText("Kotlin developer")).toBeVisible();
    expect(screen.getByText("https://jobs.example/42")).toBeVisible();
    expect(screen.queryByText("Text")).not.toBeInTheDocument();
  });

  it("has a not-found page inside the shell", async () => {
    start("/nope", { authenticated: true });
    expect(await screen.findByRole("heading", { level: 1, name: "Page not found" })).toBeVisible();
    expect(screen.getByRole("navigation", { name: "Main" })).toBeVisible();
  });
});

describe("password change", () => {
  async function fill(user: ReturnType<typeof userEvent.setup>, current: string, next: string) {
    await user.type(await screen.findByLabelText("Current password"), current);
    await user.type(screen.getByLabelText("New password"), next);
    await user.type(screen.getByLabelText("Repeat the password"), next);
    await user.click(screen.getByRole("button", { name: "Change password" }));
  }

  it("changes the password and clears the form", async () => {
    const { state, user } = start("/settings", { authenticated: true });
    await fill(user, GOOD_PASSWORD, "an even longer new passphrase");

    expect(await screen.findByText("Your password was changed. Other devices are logged out.")).toBeVisible();
    expect(state.password).toBe("an even longer new passphrase");
    expect(screen.getByLabelText("Current password")).toHaveValue("");
  });

  it("names a wrong current password", async () => {
    const { user } = start("/settings", { authenticated: true });
    await fill(user, "not my password", "an even longer new passphrase");
    expect(await screen.findByText("The current password is wrong.")).toBeVisible();
  });
});
