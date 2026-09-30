// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useState } from "react";
import { m } from "../../paraglide/messages.js";
import { TextField } from "../../ui";
import { newPasswordError, PASSWORD_MAX_LENGTH, repeatPasswordError } from "./password";

export interface NewPasswordFieldsProps {
  password: string;
  onPasswordChange: (password: string) => void;
  /** Label of the first field ("Password" at first run, "New password" when changing it). */
  label?: string;
  /** Field name, so a form's server errors can target it. */
  name?: string;
}

/** A new password and its repetition, checked against the backend's rules (15 to 256 characters). */
export function NewPasswordFields({
  password,
  onPasswordChange,
  label = m.password_label(),
  name = "password",
}: NewPasswordFieldsProps) {
  const [repeated, setRepeated] = useState("");
  return (
    <>
      <TextField
        name={name}
        type="password"
        label={label}
        autoComplete="new-password"
        maxLength={PASSWORD_MAX_LENGTH}
        value={password}
        onChange={onPasswordChange}
        validate={newPasswordError}
        description={m.password_rules()}
      />
      <TextField
        name={`${name}Repeat`}
        type="password"
        label={m.password_repeat_label()}
        autoComplete="new-password"
        value={repeated}
        onChange={setRepeated}
        validate={(value) => repeatPasswordError(password, value)}
      />
    </>
  );
}
