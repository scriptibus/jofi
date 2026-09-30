// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { BoardColumn, type BoardColumnProps } from "./index";

interface Card {
  id: string;
  title: string;
  lane: string;
}

const TYPE = "application/x-test-card";

function Column(props: Partial<BoardColumnProps<Card>> & { title: string; items: Card[] }) {
  return (
    <BoardColumn<Card>
      count={String(props.items.length)}
      textValue={(card) => card.title}
      dragLabel={(card) => `Drag ${card.title}`}
      dragData={(card) => ({
        [TYPE]: card.id,
        [`${TYPE}-${card.lane}`]: card.lane,
        "text/plain": card.title,
      })}
      dragType={TYPE}
      canDrop={() => true}
      onDrop={() => {}}
      renderItem={(card) => <span>{card.title}</span>}
      emptyText="Nothing here"
      {...props}
    />
  );
}

/** During a keyboard drag, focus sits on a "Drop on" target labelled by the list it drops on. */
function droppingOn(): HTMLElement | null {
  const target = document.activeElement;
  if (target?.getAttribute("aria-roledescription") !== "drop indicator") return null;
  const listId = target.getAttribute("aria-labelledby")?.split(" ")[1];
  return listId ? document.getElementById(listId) : null;
}

describe("BoardColumn", () => {
  it("shows a heading with the count, a list named by the title, and a drag handle per card", () => {
    render(<Column title="Doing" items={[{ id: "1", title: "Write tests", lane: "doing" }]} />);
    expect(screen.getByRole("heading", { name: /^Doing\s*1$/ })).toBeVisible();
    const list = screen.getByRole("grid", { name: "Doing" });
    expect(within(list).getByRole("row", { name: "Write tests" })).toBeVisible();
    expect(within(list).getByRole("button", { name: "Drag Write tests" })).toBeVisible();
  });

  it("says when a column is empty", () => {
    render(<Column title="Done" items={[]} />);
    expect(within(screen.getByRole("grid", { name: "Done" })).getByText("Nothing here")).toBeVisible();
  });

  it("moves a card with the keyboard only to a column that accepts it", async () => {
    const user = userEvent.setup();
    const onDoneDrop = vi.fn();
    const onBlockedDrop = vi.fn();
    render(
      <>
        <Column
          title="Doing"
          items={[{ id: "1", title: "Write tests", lane: "doing" }]}
          canDrop={() => false}
        />
        <Column
          title="Blocked"
          items={[]}
          canDrop={(types) => types.has(`${TYPE}-review`)}
          onDrop={onBlockedDrop}
        />
        <Column title="Done" items={[]} canDrop={(types) => types.has(`${TYPE}-doing`)} onDrop={onDoneDrop} />
      </>,
    );
    const handle = screen.getByRole("button", { name: "Drag Write tests" });
    await user.click(screen.getByRole("row", { name: "Write tests" }));
    handle.focus();
    await user.keyboard("{Enter}");
    // The only column that accepts this card gets focus as the drop target; Enter drops.
    const done = screen.getByRole("grid", { name: "Done" });
    // "Blocked" comes first in the page but refuses this card, so the drag skips it.
    await waitFor(() => expect(droppingOn()).toBe(done));
    await user.keyboard("{Enter}");
    await waitFor(() => expect(onDoneDrop).toHaveBeenCalledWith("1"));
    expect(onBlockedDrop).not.toHaveBeenCalled();
  });
});
