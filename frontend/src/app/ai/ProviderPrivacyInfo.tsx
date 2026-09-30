// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ProviderResponseKind } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert } from "../../ui";
import { PROVIDER_KIND_LABELS } from "./tasks";

/**
 * Privacy information for a provider kind (spec §3.2). The dated facts (data retention, training,
 * data location, links to the terms, `checkedOn`) come from the privacy info file of #138, which is not
 * merged yet: until then this card says so, and always carries the disclaimer. Replace the pending
 * paragraph with the entry from `GET /api/setup/providers/privacy` once #138 lands.
 */
export function ProviderPrivacyInfo({ kind }: { kind: ProviderResponseKind }) {
  return (
    <section aria-labelledby="privacy-heading" className="flex flex-col gap-2 rounded bg-sunken p-4">
      <h4 id="privacy-heading" className="font-semibold text-body">
        {m.ai_privacy_heading({ provider: PROVIDER_KIND_LABELS[kind]() })}
      </h4>
      <p>{kind === "OPENAI_COMPATIBLE" ? m.ai_privacy_compatible() : m.ai_privacy_cloud()}</p>
      <p className="text-muted">{m.ai_privacy_pending()}</p>
      <Alert tone="warning" title={m.ai_privacy_disclaimer_title()}>
        <p>{m.ai_privacy_disclaimer()}</p>
      </Alert>
    </section>
  );
}
