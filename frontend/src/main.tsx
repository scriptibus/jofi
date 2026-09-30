// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import "./styles/app.css";

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App, createApp } from "./app/App";
import { getLocale } from "./paraglide/runtime.js";
import { initAppearance, LocaleProvider } from "./ui";

initAppearance();
document.documentElement.lang = getLocale();

const container = document.getElementById("root");
if (!container) throw new Error("#root element missing in index.html");

createRoot(container).render(
  <StrictMode>
    <LocaleProvider locale={getLocale()}>
      <App app={createApp()} />
    </LocaleProvider>
  </StrictMode>,
);
