// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { BlacklistedIcon, FavouriteIcon } from "../../ui";
import { type Preference, preferenceLabel } from "./company";

/** Favourite or blacklisted, as icon and word (never colour alone); nothing for no preference. */
export function PreferenceBadge({ preference }: { preference: Preference }) {
  if (preference === "NONE") return null;
  const Icon = preference === "FAVOURITE" ? FavouriteIcon : BlacklistedIcon;
  const tone = preference === "FAVOURITE" ? "text-good" : "text-bad";
  return (
    <span className="inline-flex items-center gap-1.5 rounded border border-line bg-sunken px-2 py-0.5 font-semibold text-body">
      <Icon className={`size-4 ${tone}`} aria-hidden="true" />
      {preferenceLabel(preference)}
    </span>
  );
}
