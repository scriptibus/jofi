// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// The component library. Feature code imports from here only; react-aria-components
// is an implementation detail of src/ui (enforced by Biome noRestrictedImports).

export { Form, type FormProps, I18nProvider as LocaleProvider } from "react-aria-components";
export { Alert, type AlertProps, type AlertTone } from "./Alert";
export {
  ACCENTS,
  type Accent,
  initAppearance,
  THEMES,
  type Theme,
  useAccent,
  useTheme,
} from "./appearance";
export { BoardColumn, type BoardColumnProps, type DragTypesView } from "./BoardColumn";
export { Button, type ButtonProps, type ButtonVariant } from "./Button";
export { Checkbox, type CheckboxProps } from "./Checkbox";
export { ConfirmDialog, type ConfirmDialogProps } from "./ConfirmDialog";
export { DateTimeField, type DateTimeFieldProps } from "./DateTimeField";
export { Dialog, type DialogProps } from "./Dialog";
export { Disclosure, type DisclosureProps } from "./Disclosure";
export { DonkeyLogo, type DonkeyLogoProps } from "./DonkeyLogo";
export { EmptyState, type EmptyStateProps } from "./EmptyState";
export { FilePicker, type FilePickerProps } from "./FilePicker";
export * from "./icons";
export { AppLink, ChipLink, ExternalLink, type ExternalLinkProps, NavItem, TextLink } from "./Link";
export { MARKDOWN_SCHEMA, Markdown, type MarkdownProps, safeHref } from "./Markdown";
export { type MenuAction, MenuButton, type MenuButtonProps, type MenuGroup } from "./Menu";
export { NumberField, type NumberFieldProps } from "./NumberField";
export { ProgressBar, type ProgressBarProps } from "./ProgressBar";
export { RadioList, type RadioListOption, type RadioListProps } from "./RadioList";
export {
  AccentSwatch,
  SegmentedControl,
  type SegmentedControlProps,
  type SegmentedOption,
} from "./SegmentedControl";
export {
  MultiSelect,
  type MultiSelectProps,
  Select,
  type SelectGroup,
  type SelectOption,
  type SelectProps,
} from "./Select";
export { ShareBar, type ShareBarProps, type ShareBarTone, sharePercent } from "./ShareBar";
export {
  type SortDirection,
  Table,
  TableCell,
  type TableColumn,
  type TableProps,
  type TableSort,
} from "./Table";
export { type TabDefinition, Tabs, type TabsProps } from "./Tabs";
export { TextArea, type TextAreaProps } from "./TextArea";
export { TextField, type TextFieldProps } from "./TextField";
