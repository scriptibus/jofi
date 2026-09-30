// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// The component library. Feature code imports from here only; react-aria-components
// is an implementation detail of src/ui (enforced by Biome noRestrictedImports).

export { I18nProvider as LocaleProvider } from "react-aria-components";
export {
  ACCENTS,
  type Accent,
  initAppearance,
  THEMES,
  type Theme,
  useAccent,
  useTheme,
} from "./appearance";
export { Button, type ButtonProps, type ButtonVariant } from "./Button";
export { DonkeyLogo, type DonkeyLogoProps } from "./DonkeyLogo";
export {
  AccentSwatch,
  SegmentedControl,
  type SegmentedControlProps,
  type SegmentedOption,
} from "./SegmentedControl";
