// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Markdown, safeHref } from "./index";

function renderMarkdown(markdown: string) {
  const { container } = render(<Markdown>{markdown}</Markdown>);
  return container;
}

describe("Markdown", () => {
  it("renders text structure: headings below the page's sections, lists, emphasis, code", () => {
    renderMarkdown("# Culture\n\n- **Remote** first\n- *Flat* teams\n\n`code`");
    expect(screen.getByRole("heading", { level: 3, name: "Culture" })).toBeVisible();
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    expect(screen.getByText("Remote").tagName).toBe("STRONG");
    expect(screen.getByText("code").tagName).toBe("CODE");
  });

  it("drops raw HTML, script tags included (inline tag contents stay as inert text)", () => {
    const container = renderMarkdown(
      'Hello <script>alert("x")</script>\n\n<script>alert("block")</script>\n\n<b onclick="x()">bold</b> <iframe src="https://evil.example"></iframe>',
    );
    expect(container.querySelector("script, iframe, b, [onclick]")).toBeNull();
    expect(container.textContent).not.toContain("block");
    expect(screen.getByText(/Hello/)).toBeVisible();
  });

  it("opens http(s) and mailto links as external links without referrer or endorsement", () => {
    renderMarkdown("[Site](https://acme.example/jobs) and [Mail](mailto:jobs@acme.example)");
    const site = screen.getByRole("link", { name: "Site" });
    expect(site).toHaveAttribute("href", "https://acme.example/jobs");
    expect(site).toHaveAttribute("rel", "noopener noreferrer nofollow");
    expect(site).toHaveAttribute("target", "_blank");
    expect(screen.getByRole("link", { name: "Mail" })).toHaveAttribute("href", "mailto:jobs@acme.example");
  });

  it("keeps the text of javascript:, data: and relative links but not the link", () => {
    renderMarkdown(
      "[one](javascript:alert(1)) [two](JaVaScRiPt:alert(1)) [three](data:text/html,<b>x</b>) [four](/api/companies) [five](vbscript:x)",
    );
    expect(screen.queryAllByRole("link")).toEqual([]);
    for (const text of ["one", "two", "three", "four", "five"]) expect(screen.getByText(text)).toBeVisible();
  });

  it("never loads images: remote, inline HTML or reference-style", () => {
    const container = renderMarkdown(
      '![pixel](https://tracker.example/p.gif)\n\n<img src="https://tracker.example/q.gif">\n\n![ref][r]\n\n[r]: https://tracker.example/r.gif',
    );
    expect(container.querySelector("img")).toBeNull();
    expect(container.innerHTML).not.toContain("tracker.example");
  });
});

describe("safeHref", () => {
  it("allows only absolute http, https and mailto URLs", () => {
    expect(safeHref("https://bücher.example/x")).toBe("https://bücher.example/x");
    expect(safeHref("HTTP://acme.example")).toBe("HTTP://acme.example");
    expect(safeHref("mailto:a@b.example")).toBe("mailto:a@b.example");
    expect(safeHref("javascript:alert(1)")).toBe("");
    expect(safeHref(" javascript:alert(1)")).toBe("");
    expect(safeHref("//evil.example")).toBe("");
    expect(safeHref("/settings")).toBe("");
    expect(safeHref("ftp://files.example")).toBe("");
  });
});
