// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useGetSystemInfo } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { PasswordChangeForm } from "../auth/PasswordChangeForm";
import { BackupSection } from "../backup/BackupSection";
import { AccentSwitch, LanguageSwitch, ThemeSwitch } from "../preferences";
import { LogoutButton } from "../shell/LogoutButton";
import { PageHeader } from "./PlaceholderPage";

const card = "flex flex-col gap-6 rounded border border-line bg-surface p-6 shadow-card";

export function SettingsPage() {
  const info = useGetSystemInfo();
  return (
    <>
      <PageHeader title={m.nav_settings()} />
      <div className="grid gap-6 lg:grid-cols-2">
        <section aria-labelledby="appearance-heading" className={card}>
          <h2 id="appearance-heading" className="text-h2">
            {m.appearance_heading()}
          </h2>
          <ThemeSwitch />
          <AccentSwitch />
          <LanguageSwitch />
        </section>
        <div className={card}>
          <PasswordChangeForm />
        </div>
        <div className={`${card} lg:col-span-2`}>
          <BackupSection />
        </div>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <p className="font-data text-eyebrow text-muted">
          {info.data ? m.settings_version({ version: info.data.version }) : null}
        </p>
        {/* The sidebar has logout from `md` on; phones find it here. */}
        <LogoutButton className="md:hidden" />
      </div>
    </>
  );
}
