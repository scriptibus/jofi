// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from "@tanstack/react-router";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { AppLink, ExternalLink, NavItem, TextLink } from "./Link";

// A throwaway route tree: the links are typed against the app's router, so `to` is cast here.
function renderAt(path: string) {
  const root = createRootRoute({
    component: () => (
      <>
        <nav aria-label="Main">
          <NavItem to={"/" as "/"} activeOptions={{ exact: true }}>
            Home
          </NavItem>
          <NavItem to={"/tasks" as "/"} variant="tab">
            Tasks
          </NavItem>
        </nav>
        <TextLink to={"/tasks" as "/"}>Open tasks</TextLink>
        <Outlet />
      </>
    ),
  });
  const page = (routePath: string, text: string) =>
    createRoute({ getParentRoute: () => root, path: routePath, component: () => <h1>{text}</h1> });
  const router = createRouter({
    routeTree: root.addChildren([page("/", "Home page"), page("tasks", "Tasks page")]),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  render(<RouterProvider router={router as never} />);
  return router;
}

describe("NavItem and TextLink", () => {
  it("mark the current page with aria-current", async () => {
    renderAt("/tasks");
    expect(await screen.findByRole("heading", { name: "Tasks page" })).toBeVisible();
    expect(screen.getByRole("link", { name: "Tasks" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Home" })).not.toHaveAttribute("aria-current");
  });

  it("navigate on the client", async () => {
    const router = renderAt("/");
    await screen.findByRole("heading", { name: "Home page" });

    await userEvent.setup().click(screen.getByRole("link", { name: "Open tasks" }));

    expect(await screen.findByRole("heading", { name: "Tasks page" })).toBeVisible();
    expect(router.state.location.pathname).toBe("/tasks");
    expect(screen.getByRole("link", { name: "Tasks" })).toHaveAttribute("href", "/tasks");
  });
});

describe("ExternalLink and AppLink", () => {
  it("open other sites in a new tab without referrer or endorsement", () => {
    render(<ExternalLink href="https://example.org/">Example</ExternalLink>);
    const link = screen.getByRole("link", { name: "Example" });
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer nofollow");
  });

  it("hand mailto: and tel: links to the device's apps in the same tab", () => {
    render(<AppLink href="tel:+4930123">+49 30 123</AppLink>);
    const link = screen.getByRole("link", { name: "+49 30 123" });
    expect(link).toHaveAttribute("href", "tel:+4930123");
    expect(link).not.toHaveAttribute("target");
  });
});
