// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useCallback, useState } from "react";

export interface FieldErrors {
  /** Server errors by field name, for the form's `validationErrors`. */
  errors: Record<string, string>;
  set: (errors: Record<string, string>) => void;
  /** Wraps a field's change handler so editing the field drops its server error. */
  clearing: <T>(name: string, onChange: (value: T) => void) => (value: T) => void;
}

/**
 * Server-side field errors for a React Aria `Form`. With native validation an error from
 * `validationErrors` stays the input's custom validity until the prop changes, which blocks the
 * next submit even after the user fixed the value; so editing a field removes its entry here.
 */
export function useFieldErrors(): FieldErrors {
  const [errors, setErrors] = useState<Record<string, string>>({});
  const clearing = useCallback(
    <T>(name: string, onChange: (value: T) => void) =>
      (value: T) => {
        onChange(value);
        setErrors((current) => {
          if (!(name in current)) return current;
          const { [name]: _removed, ...rest } = current;
          return rest;
        });
      },
    [],
  );
  return { errors, set: setErrors, clearing };
}
