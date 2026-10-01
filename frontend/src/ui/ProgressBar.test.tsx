// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ProgressBar } from "./index";

describe("ProgressBar", () => {
  it("is a named progress bar whose value text carries the figures", () => {
    render(<ProgressBar label="Spent" percent={30} valueText="$3.00 of $10.00 (30%)" />);
    const bar = screen.getByRole("progressbar", { name: "Spent" });
    expect(bar).toHaveAttribute("aria-valuenow", "30");
    expect(bar).toHaveAttribute("aria-valuetext", "$3.00 of $10.00 (30%)");
    expect(bar.firstElementChild).toHaveStyle({ width: "30%" });
  });

  it.each([
    [-5, "0"],
    [140, "100"],
  ])("clamps %d percent to %s", (percent, expected) => {
    render(<ProgressBar label="Spent" percent={percent} valueText="x" />);
    const bar = screen.getByRole("progressbar");
    expect(bar).toHaveAttribute("aria-valuenow", expected);
    expect(bar.firstElementChild).toHaveStyle({ width: `${expected}%` });
  });

  it("uses the critical colour only when asked", () => {
    const { rerender } = render(<ProgressBar label="Spent" percent={100} valueText="x" />);
    expect(screen.getByRole("progressbar").firstElementChild).toHaveClass("bg-accent");
    rerender(<ProgressBar label="Spent" percent={100} valueText="x" tone="critical" />);
    expect(screen.getByRole("progressbar").firstElementChild).toHaveClass("bg-bad");
  });
});
