// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";
import {
  ApplicationsIcon,
  ChatIcon,
  CompaniesIcon,
  ContactsIcon,
  DashboardIcon,
  type Icon,
  SettingsIcon,
  TasksIcon,
} from "../../ui";

export interface NavigationEntry {
  to: "/" | "/applications" | "/companies" | "/contacts" | "/tasks" | "/chat" | "/settings";
  label: () => string;
  icon: Icon;
  /** In the phone's bottom tab bar (five fit); settings sits in the top bar there instead. */
  phone: boolean;
}

/** The M1 areas, in navigation order. */
export const NAVIGATION: readonly NavigationEntry[] = [
  { to: "/", label: m.nav_dashboard, icon: DashboardIcon, phone: true },
  { to: "/applications", label: m.nav_applications, icon: ApplicationsIcon, phone: true },
  { to: "/companies", label: m.nav_companies, icon: CompaniesIcon, phone: true },
  // Phones reach contacts from the companies page; the tab bar has room for five areas only.
  { to: "/contacts", label: m.nav_contacts, icon: ContactsIcon, phone: false },
  { to: "/tasks", label: m.nav_tasks, icon: TasksIcon, phone: true },
  { to: "/chat", label: m.nav_chat, icon: ChatIcon, phone: true },
  { to: "/settings", label: m.nav_settings, icon: SettingsIcon, phone: false },
];
