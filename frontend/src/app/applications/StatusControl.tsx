// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  type ApplicationResponse,
  getGetApplicationQueryKey,
  getGetApplicationStatusHistoryQueryKey,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Button, EditIcon, Select, type SelectGroup } from "../../ui";
import { type Status, statusLabels } from "./labels";
import { StatusChangeDialog } from "./StatusChangeDialog";
import { nextStatuses, takesDeclineReason } from "./statusMatrix";

/** The statuses the application may move to (ADR-0044), the pipeline and the ended ones under headings. */
function nextStatusGroups(status: Status): SelectGroup[] {
  const { pipeline, ended } = nextStatuses(status);
  const options = (statuses: Status[]) => statuses.map((id) => ({ id, label: statusLabels[id]() }));
  return [
    { id: "pipeline", title: m.application_status_group_pipeline(), options: options(pipeline) },
    { id: "ended", title: m.application_status_group_ended(), options: options(ended) },
  ].filter((group) => group.options.length > 0);
}

/**
 * Changes the status: a list of only the moves the matrix allows, then a dialog for the reason. A Declined or
 * Rejected application can also have its reason corrected. After a change the detail, the lists and the
 * history load again (an open details form then gets 409 on save and offers to load the latest version).
 */
export function StatusControl({ application }: { application: ApplicationResponse }) {
  const queryClient = useQueryClient();
  const [target, setTarget] = useState<Status | null>(null);
  const reload = () => {
    void queryClient.invalidateQueries({ queryKey: getGetApplicationQueryKey(application.id), exact: true });
    void queryClient.invalidateQueries({ queryKey: getGetApplicationStatusHistoryQueryKey(application.id) });
  };
  return (
    <div className="flex flex-wrap items-end gap-3">
      <Select
        label={m.application_status_change_label()}
        placeholder={m.application_status_change_placeholder()}
        groups={nextStatusGroups(application.status)}
        value={null}
        onChange={(id) => setTarget(id as Status)}
        className="min-w-56 grow sm:grow-0"
      />
      {takesDeclineReason(application.status) ? (
        <Button variant="secondary" onPress={() => setTarget(application.status)}>
          <EditIcon className="size-4" aria-hidden="true" />
          {m.application_status_correct()}
        </Button>
      ) : null}
      <StatusChangeDialog
        application={application}
        target={target}
        onClose={() => setTarget(null)}
        onReload={reload}
      />
    </div>
  );
}
