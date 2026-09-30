// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { Tabs as AriaTabs, Tab, TabList, TabPanel } from "react-aria-components";

export interface TabDefinition<K extends string> {
  id: K;
  label: string;
  /** Shown but not selectable, e.g. a section that is not there yet. */
  isDisabled?: boolean;
}

export interface TabsProps<K extends string> {
  /** Accessible name of the tab list. */
  label: string;
  tabs: readonly TabDefinition<K>[];
  /** The selected tab, e.g. from the URL. */
  selected: K;
  onSelect: (id: K) => void;
  /** The selected tab's content. */
  children: ReactNode;
}

/**
 * A row of tabs over one panel (React Aria Tabs): arrow keys move between tabs, Tab moves into the panel.
 * Controlled, so the page can keep the selected tab in its URL. The selected tab is underlined and bold,
 * never marked by colour alone; the row scrolls sideways when it does not fit.
 */
export function Tabs<K extends string>({ label, tabs, selected, onSelect, children }: TabsProps<K>) {
  return (
    <AriaTabs
      selectedKey={selected}
      onSelectionChange={(key) => {
        const tab = tabs.find((candidate) => candidate.id === key);
        if (tab) onSelect(tab.id);
      }}
      disabledKeys={tabs.filter((tab) => tab.isDisabled).map((tab) => tab.id)}
      className="flex flex-col gap-6"
    >
      <TabList aria-label={label} className="flex overflow-x-auto border-line border-b">
        {tabs.map((tab) => (
          <Tab
            key={tab.id}
            id={tab.id}
            className={
              "-mb-px shrink-0 cursor-default whitespace-nowrap rounded-t border-transparent border-b-2 px-4 py-2 " +
              "font-medium text-body text-muted outline-none " +
              "data-hovered:text-fg data-selected:border-accent data-selected:font-semibold data-selected:text-fg " +
              "data-disabled:opacity-50 data-focus-visible:outline-2 data-focus-visible:outline-accent " +
              "data-focus-visible:-outline-offset-2"
            }
          >
            {tab.label}
          </Tab>
        ))}
      </TabList>
      <TabPanel
        id={selected}
        className="flex flex-col gap-6 rounded outline-none data-focus-visible:outline-2 data-focus-visible:outline-accent data-focus-visible:outline-offset-4"
      >
        {children}
      </TabPanel>
    </AriaTabs>
  );
}
