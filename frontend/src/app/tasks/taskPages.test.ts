// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import type { TaskGroupListResponse } from "../../api/generated/jofi";
import { aTask, summaryOfTask } from "../../test/fakeTaskBackend";
import { mergeGroups } from "./taskPages";

const page = (
  index: number,
  groups: Record<string, ReturnType<typeof summaryOfTask>[]>,
): TaskGroupListResponse => ({
  groups: ["OVERDUE", "TODAY", "THIS_WEEK"].map((group) => ({
    group: group as "OVERDUE" | "TODAY" | "THIS_WEEK",
    tasks: groups[group] ?? [],
  })),
  page: { page: index, size: 50, total: 3, hasMore: false },
});

describe("mergeGroups", () => {
  it("continues a group over the pages, in order", () => {
    const [a, b] = [summaryOfTask(aTask({ title: "A" })), summaryOfTask(aTask({ title: "B" }))];

    const merged = mergeGroups([page(0, { TODAY: [a] }), page(1, { TODAY: [b] })]);

    expect(merged.find((group) => group.group === "TODAY")?.tasks.map((task) => task.title)).toEqual([
      "A",
      "B",
    ]);
    expect(merged.map((group) => group.group)).toEqual(["OVERDUE", "TODAY", "THIS_WEEK"]);
  });

  it("lists a task once even when a later page has it in another group (its due time passed meanwhile)", () => {
    const moved = summaryOfTask(aTask({ title: "Moved" }));

    const merged = mergeGroups([page(0, { TODAY: [moved] }), page(1, { OVERDUE: [moved] })]);

    const holding = merged.filter((group) => group.tasks.some((task) => task.id === moved.id));
    expect(holding.map((group) => group.group)).toEqual(["TODAY"]);
    expect(merged.flatMap((group) => group.tasks)).toHaveLength(1);
  });

  it("lists a reopened task once within one group", () => {
    const again = summaryOfTask(aTask({ title: "Again" }));

    const merged = mergeGroups([page(0, { TODAY: [again] }), page(1, { TODAY: [again] })]);

    expect(merged.flatMap((group) => group.tasks)).toHaveLength(1);
  });
});
