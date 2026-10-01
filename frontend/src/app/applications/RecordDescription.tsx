// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  type DescriptionSnapshotRecordedResponse,
  getGetApplicationTimelineQueryKey,
  getListDescriptionSnapshotsQueryKey,
  useRecordDescriptionSnapshot,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, Disclosure, Form, TextArea } from "../../ui";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { fieldErrorsOf } from "../companies/company";
import { describeApplicationError } from "./applicationProblems";

type Outcome = { added: true; number: number } | { added: false };

/**
 * Records the posting's current text for a source (spec §6.1): the server keeps it as a new version, or
 * answers "unchanged" when it matches the latest one, which the user is told either way.
 */
export function RecordDescription({
  applicationId,
  sourceId,
  versionCount,
  onRecorded,
}: {
  applicationId: string;
  sourceId: string;
  /** How many versions the source has, to name the new one. */
  versionCount: number;
  onRecorded: (snapshotId: string) => void;
}) {
  const queryClient = useQueryClient();
  const [text, setText] = useState("");
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const record = useRecordDescriptionSnapshot({ mutation: { meta: { errorHandledLocally: true } } });
  const fieldErrors = fieldErrorsOf(record.error);

  const recorded = (answer: DescriptionSnapshotRecordedResponse) => {
    if (!answer.added) {
      setOutcome({ added: false });
      return;
    }
    setOutcome({ added: true, number: versionCount + 1 });
    setText("");
    void queryClient.invalidateQueries({
      queryKey: getListDescriptionSnapshotsQueryKey(applicationId, sourceId),
    });
    void queryClient.invalidateQueries({ queryKey: getGetApplicationTimelineQueryKey(applicationId) });
    onRecorded(answer.snapshot.id);
  };
  const submit = () => {
    setOutcome(null);
    record.mutate({ id: applicationId, sourceId, data: { description: text } }, { onSuccess: recorded });
  };

  return (
    <Disclosure label={m.application_description_record()}>
      <Form
        className="flex flex-col gap-4"
        validationErrors={fieldErrors ?? {}}
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <TextArea
          name="description"
          label={m.application_description_record_field()}
          description={m.application_description_record_help()}
          rows={8}
          value={text}
          onChange={setText}
        />
        {record.isError && fieldErrors === undefined ? (
          <FailureMessage failure={describeApplicationError(record.error)} />
        ) : null}
        {outcome ? <RecordOutcome outcome={outcome} /> : null}
        <Button type="submit" className="self-start" isDisabled={record.isPending || text.trim() === ""}>
          {m.application_description_record_action()}
        </Button>
      </Form>
    </Disclosure>
  );
}

function RecordOutcome({ outcome }: { outcome: Outcome }) {
  return outcome.added ? (
    <Alert tone="success">{m.application_description_recorded_new({ number: outcome.number })}</Alert>
  ) : (
    <Alert tone="info">{m.application_description_recorded_unchanged()}</Alert>
  );
}
