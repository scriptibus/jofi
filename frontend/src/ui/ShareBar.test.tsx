// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ShareBar, sharePercent } from "./index";

describe("sharePercent", () => {
  it("is the whole percentage of the part, capped at 100", () => {
    expect(sharePercent(1, 4)).toBe(25);
    expect(sharePercent(2, 3)).toBe(67);
    expect(sharePercent(12, 10)).toBe(100);
  });

  it("shows a tiny part as at least 1 % and nothing as 0", () => {
    expect(sharePercent(1, 1000)).toBe(1);
    expect(sharePercent(0, 10)).toBe(0);
    expect(sharePercent(5, 0)).toBe(0);
    expect(sharePercent(-1, 10)).toBe(0);
  });
});

describe("ShareBar", () => {
  it("is hidden from assistive technology and fills its share in the tone's colour", () => {
    const { container } = render(<ShareBar value={3} max={4} tone="bad" />);
    const track = container.firstElementChild as HTMLElement;
    expect(track).toHaveAttribute("aria-hidden", "true");
    const fill = track.firstElementChild as HTMLElement;
    expect(fill.style.inlineSize).toBe("75%");
    expect(fill).toHaveClass("bg-bad");
  });

  it("uses the accent by default", () => {
    const { container } = render(<ShareBar value={1} max={2} />);
    expect(container.querySelector(".bg-accent")).not.toBeNull();
  });
});
