// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useState } from "react";
import { type TaskResponse, useGetTaskDashboard } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { OverdueIcon, TextLink } from "../../ui";
import { describeTiming, taskTitle, viewerTimeZone } from "../tasks/task";
import { formatCount, TASKS_SHOWN } from "./dashboard";
import { Widget, WidgetContent, widgetQuery } from "./Widget";

/** Overdue tasks and those due in the next seven days on the viewer's calendar (ADR-0052), soonest first. */
export function TasksWidget() {
  const [viewerZone] = useState(viewerTimeZone);
  const query = useGetTaskDashboard({ timeZone: viewerZone }, { query: widgetQuery });
  return (
    <Widget
      id="dashboard-tasks"
      title={m.dashboard_tasks_heading()}
      footer={<TextLink to="/tasks">{m.dashboard_tasks_all()}</TextLink>}
    >
      <WidgetContent query={query} failed={m.dashboard_tasks_failed()}>
        {({ overdue, upcoming }) =>
          overdue.length === 0 && upcoming.length === 0 ? (
            <p className="text-muted">{m.dashboard_tasks_empty()}</p>
          ) : (
            <>
              <TaskList id="dashboard-tasks-overdue" tasks={overdue} overdue viewerZone={viewerZone} />
              <TaskList
                id="dashboard-tasks-upcoming"
                tasks={upcoming}
                overdue={false}
                viewerZone={viewerZone}
              />
            </>
          )
        }
      </WidgetContent>
    </Widget>
  );
}

interface TaskListProps {
  id: string;
  tasks: TaskResponse[];
  overdue: boolean;
  viewerZone: string;
}

/** The first few tasks of one list with its count; an empty list is left out. */
function TaskList({ id, tasks, overdue, viewerZone }: TaskListProps) {
  if (tasks.length === 0) return null;
  const count = formatCount(tasks.length);
  const hidden = tasks.length - TASKS_SHOWN;
  return (
    <div className="flex flex-col gap-2">
      <h3 id={id} className={`flex items-center gap-2 font-semibold ${overdue ? "text-bad" : ""}`}>
        {overdue ? <OverdueIcon className="size-4" aria-hidden="true" /> : null}
        {overdue ? m.dashboard_tasks_overdue({ count }) : m.dashboard_tasks_upcoming({ count })}
      </h3>
      <ul aria-labelledby={id} className="flex flex-col gap-2">
        {tasks.slice(0, TASKS_SHOWN).map((task) => (
          <li key={task.id} className="flex flex-col">
            <span className="font-medium">{taskTitle(task)}</span>
            <span className="text-muted">{describeTiming(task.timing, viewerZone)}</span>
          </li>
        ))}
      </ul>
      {hidden > 0 ? (
        <p className="text-muted">{m.dashboard_tasks_more({ count: formatCount(hidden) })}</p>
      ) : null}
    </div>
  );
}
