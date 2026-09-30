// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DonkeyLogo } from "./DonkeyLogo";

describe("DonkeyLogo", () => {
  it("is an image named by its label", () => {
    render(<DonkeyLogo label="Jofi" />);
    expect(screen.getByRole("img", { name: "Jofi" })).toBeInTheDocument();
  });

  it("is hidden from assistive technology without a label", () => {
    const { container } = render(<DonkeyLogo />);
    expect(screen.queryByRole("img")).toBeNull();
    expect(container.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });

  it("bobs only in the loading variant, and only when motion is allowed", () => {
    const { container, rerender } = render(<DonkeyLogo label="Jofi" />);
    const svg = () => container.querySelector("svg");
    expect(svg()).not.toHaveAttribute("data-loading");
    expect(container.querySelector(".motion-safe\\:animate-bob")).toBeNull();

    rerender(<DonkeyLogo label="Loading" loading />);
    expect(svg()).toHaveAttribute("data-loading", "true");
    expect(container.querySelector(".motion-safe\\:animate-bob")).not.toBeNull();
  });
});
