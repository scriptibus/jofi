// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { type SyntheticEvent, useState } from "react";
import { useCompleteFirstRun } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Button, Form, TextField } from "../../ui";
import { describeError, type ErrorDescription, isProblem, ProblemType, throttledFor } from "../problems";
import { AuthLayout } from "./AuthLayout";
import { FormFeedback } from "./FormFeedback";
import { NewPasswordFields } from "./NewPasswordFields";
import { refreshSession } from "./session";
import { useThrottle } from "./useThrottle";

/** Where the user reads the one-time setup token (ADR-0035). Shown verbatim, never translated. */
export const SETUP_TOKEN_COMMAND = "docker compose exec app cat /data/secrets/setup-token";

export function FirstRunPage() {
  const queryClient = useQueryClient();
  const router = useRouter();
  const throttle = useThrottle();
  const [password, setPassword] = useState("");
  const [setupToken, setSetupToken] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const firstRun = useCompleteFirstRun({ mutation: { meta: { errorHandledLocally: true } } });

  const onError = async (error: unknown) => {
    const wait = throttledFor(error);
    if (wait !== undefined) throttle.start(wait);
    else if (isProblem(error, ProblemType.invalidSetupToken))
      setFieldErrors({ setupToken: m.setup_token_wrong() });
    else if (isProblem(error, ProblemType.weakPassword))
      setFieldErrors({ password: m.error_weak_password() });
    else if (isProblem(error, ProblemType.alreadySetUp)) {
      await refreshSession(queryClient);
      await router.navigate({ to: "/login" });
    } else setFailure(describeError(error));
  };

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    throttle.reset();
    setFailure(null);
    setFieldErrors({});
    firstRun.mutate(
      { data: { password, setupToken: setupToken.trim() } },
      {
        onSuccess: async () => {
          await refreshSession(queryClient);
          await router.navigate({ to: "/" });
        },
        onError: (error) => void onError(error),
      },
    );
  };

  return (
    <AuthLayout title={m.first_run_heading()} intro={m.first_run_intro()}>
      <FormFeedback throttle={throttle} failure={failure} />
      <Form onSubmit={submit} validationErrors={fieldErrors} className="flex flex-col gap-5">
        <NewPasswordFields password={password} onPasswordChange={setPassword} />
        <TextField
          name="setupToken"
          label={m.setup_token_label()}
          autoComplete="off"
          spellCheck="false"
          mono
          value={setupToken}
          onChange={setSetupToken}
          validate={(value) => (value.trim() === "" ? m.setup_token_required() : null)}
          description={
            <>
              {m.setup_token_hint()}{" "}
              <code className="break-all rounded bg-sunken px-1 font-data text-fg">
                {SETUP_TOKEN_COMMAND}
              </code>
            </>
          }
        />
        <Button
          type="submit"
          isDisabled={throttle.waitSeconds !== undefined}
          isPending={firstRun.isPending}
          className="self-start"
        >
          {firstRun.isPending ? m.first_run_pending() : m.first_run_submit()}
        </Button>
      </Form>
    </AuthLayout>
  );
}
