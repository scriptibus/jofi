// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type SyntheticEvent, useState } from "react";
import { useChangePassword } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, Form, TextField } from "../../ui";
import {
  describeError,
  type ErrorDescription,
  isProblem,
  isSessionEnded,
  ProblemType,
  throttledFor,
} from "../problems";
import { FormFeedback } from "./FormFeedback";
import { NewPasswordFields } from "./NewPasswordFields";
import { useThrottle } from "./useThrottle";

/** Settings: change the password. The server ends every other session of the user. */
export function PasswordChangeForm() {
  const throttle = useThrottle();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const [done, setDone] = useState(false);
  // Remounts the fields after a change, so the repeated password is cleared too.
  const [generation, setGeneration] = useState(0);
  // A 401 still reaches the global handler (back to login); every other error is shown here.
  const change = useChangePassword({ mutation: { meta: { errorHandledLocally: true } } });

  const onError = (error: unknown) => {
    const wait = throttledFor(error);
    if (wait !== undefined) throttle.start(wait);
    else if (isProblem(error, ProblemType.invalidCredentials))
      setFieldErrors({ currentPassword: m.password_change_wrong_current() });
    else if (isProblem(error, ProblemType.weakPassword))
      setFieldErrors({ newPassword: m.error_weak_password() });
    else if (!isSessionEnded(error)) setFailure(describeError(error));
  };

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    throttle.reset();
    setDone(false);
    setFailure(null);
    setFieldErrors({});
    change.mutate(
      { data: { currentPassword, newPassword } },
      {
        onSuccess: () => {
          setDone(true);
          setCurrentPassword("");
          setNewPassword("");
          setGeneration((value) => value + 1);
        },
        onError,
      },
    );
  };

  return (
    <section aria-labelledby="password-heading" className="flex flex-col gap-5">
      <div className="flex flex-col gap-1">
        <h2 id="password-heading" className="text-h2">
          {m.password_change_heading()}
        </h2>
        <p className="text-muted">{m.password_change_intro()}</p>
      </div>
      {done ? <Alert tone="success">{m.password_change_done()}</Alert> : null}
      <FormFeedback throttle={throttle} failure={failure} />
      <Form
        key={generation}
        onSubmit={submit}
        validationErrors={fieldErrors}
        className="flex max-w-md flex-col gap-5"
      >
        <TextField
          name="currentPassword"
          type="password"
          label={m.password_current_label()}
          autoComplete="current-password"
          value={currentPassword}
          onChange={setCurrentPassword}
          validate={(value) => (value === "" ? m.password_required() : null)}
        />
        <NewPasswordFields
          name="newPassword"
          label={m.password_new_label()}
          password={newPassword}
          onPasswordChange={setNewPassword}
        />
        <Button
          type="submit"
          variant="secondary"
          isDisabled={throttle.waitSeconds !== undefined}
          isPending={change.isPending}
          className="self-start"
        >
          {change.isPending ? m.password_change_pending() : m.password_change_submit()}
        </Button>
      </Form>
    </section>
  );
}
