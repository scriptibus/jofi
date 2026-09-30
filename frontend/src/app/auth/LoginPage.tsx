// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { getRouteApi, useRouter } from "@tanstack/react-router";
import { type SyntheticEvent, useState } from "react";
import { useLogIn } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, Form, TextField } from "../../ui";
import { describeError, type ErrorDescription, isProblem, ProblemType, throttledFor } from "../problems";
import { AuthLayout } from "./AuthLayout";
import { FormFeedback } from "./FormFeedback";
import { refreshSession, safeRedirect } from "./session";
import { useFieldErrors } from "./useFieldErrors";
import { useThrottle } from "./useThrottle";

const route = getRouteApi("/login");

/** Why the user sees the login screen, when it is not the first visit. */
export type LoginReason = "expired" | "logged-out" | "restored";

export function LoginPage() {
  const { redirect, reason } = route.useSearch();
  const queryClient = useQueryClient();
  const router = useRouter();
  const throttle = useThrottle();
  const [password, setPassword] = useState("");
  const fieldErrors = useFieldErrors();
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const [attempted, setAttempted] = useState(false);
  const login = useLogIn({ mutation: { meta: { errorHandledLocally: true } } });

  const onError = (error: unknown) => {
    const wait = throttledFor(error);
    if (wait !== undefined) throttle.start(wait);
    else if (isProblem(error, ProblemType.invalidCredentials))
      fieldErrors.set({ password: m.login_wrong_password() });
    else if (isProblem(error, ProblemType.notSetUp)) void router.navigate({ to: "/first-run" });
    else setFailure(describeError(error));
  };

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    throttle.reset();
    setAttempted(true);
    setFailure(null);
    fieldErrors.set({});
    login.mutate(
      { data: { password } },
      {
        onSuccess: async () => {
          await refreshSession(queryClient);
          await router.navigate({ href: safeRedirect(redirect) ?? "/" });
        },
        onError,
      },
    );
  };

  const waiting = throttle.waitSeconds !== undefined;
  return (
    <AuthLayout title={m.login_heading()} intro={m.login_intro()}>
      {reason === "expired" && !attempted ? <Alert tone="info">{m.session_expired()}</Alert> : null}
      {reason === "logged-out" && !attempted ? <Alert tone="success">{m.logged_out()}</Alert> : null}
      {reason === "restored" && !attempted ? <Alert tone="success">{m.login_restored()}</Alert> : null}
      <FormFeedback throttle={throttle} failure={failure} />
      <Form onSubmit={submit} validationErrors={fieldErrors.errors} className="flex flex-col gap-5">
        <TextField
          name="password"
          type="password"
          label={m.password_label()}
          autoComplete="current-password"
          value={password}
          onChange={fieldErrors.clearing("password", setPassword)}
          validate={(value) => (value === "" ? m.password_required() : null)}
        />
        <Button type="submit" isDisabled={waiting || login.isPending} className="self-start">
          {login.isPending ? m.login_pending() : m.login_submit()}
        </Button>
      </Form>
    </AuthLayout>
  );
}
