// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type ReactNode, useId } from "react";
import { Button, GridList, GridListItem, isTextDropItem, useDragAndDrop } from "react-aria-components";
import { DragHandleIcon } from "./icons";

/** The types a drag carries, as a drop target sees them before the drop (their values stay hidden). */
export interface DragTypesView {
  has(type: string): boolean;
}

export interface BoardColumnProps<T extends { id: string }> {
  /** The column's heading; also the name of its list. */
  title: string;
  /** Shown after the heading, e.g. the number of cards ("3"). */
  count: string;
  items: readonly T[];
  /** The card's text for type-ahead and for announcing it. */
  textValue: (item: T) => string;
  /** The name of the card's drag handle ("Drag …"). */
  dragLabel: (item: T) => string;
  /** The data a dragged card carries; `dragType` holds what `onDrop` receives. */
  dragData: (item: T) => Record<string, string>;
  dragType: string;
  /** Whether a drag with these types may be dropped here; anything else is refused while dragging. */
  canDrop: (types: DragTypesView) => boolean;
  /** A card was dropped here: its `dragType` value. */
  onDrop: (value: string) => void;
  renderItem: (item: T) => ReactNode;
  /** What an empty column says. */
  emptyText: string;
}

/**
 * One column of a Kanban board: a heading and a list of cards (React Aria GridList with drag and drop).
 * Cards move by mouse, touch, or keyboard: the drag handle starts a drag with Enter, Tab walks the columns
 * that accept it, Enter drops, Escape cancels. A column only lights up and accepts a drop when `canDrop`
 * says so. Give each card another way to move as well (such as a menu): dragging is not for everyone.
 */
export function BoardColumn<T extends { id: string }>({
  title,
  count,
  items,
  textValue,
  dragLabel,
  dragData,
  dragType,
  canDrop,
  onDrop,
  renderItem,
  emptyText,
}: BoardColumnProps<T>) {
  const headingId = useId();
  const { dragAndDropHooks } = useDragAndDrop<T>({
    getItems: (_keys, dragged) => dragged.map(dragData),
    acceptedDragTypes: [dragType],
    getDropOperation: (target, types) => (target.type === "root" && canDrop(types) ? "move" : "cancel"),
    onRootDrop: async (event) => {
      for (const item of event.items.filter(isTextDropItem)) onDrop(await item.getText(dragType));
    },
  });
  return (
    <div className="flex w-64 shrink-0 snap-start flex-col gap-2">
      <h3 className="flex items-baseline justify-between gap-2 font-display text-h3">
        <span id={headingId}>{title}</span>
        <span className="font-data text-body text-muted">{count}</span>
      </h3>
      <GridList
        aria-labelledby={headingId}
        items={items}
        dragAndDropHooks={dragAndDropHooks}
        renderEmptyState={() => <p className="p-2 text-muted">{emptyText}</p>}
        className={
          "flex min-h-32 grow flex-col gap-2 rounded border border-line bg-sunken p-2 outline-none " +
          "data-focus-visible:outline-2 data-focus-visible:outline-accent " +
          "data-drop-target:outline-2 data-drop-target:outline-offset-2 data-drop-target:outline-accent"
        }
      >
        {(item) => (
          <GridListItem
            id={item.id}
            textValue={textValue(item)}
            className={
              "cursor-grab rounded border border-line bg-surface p-3 text-fg shadow-card outline-none " +
              "data-dragging:opacity-50 " +
              "data-focus-visible:outline-2 data-focus-visible:outline-offset-2 data-focus-visible:outline-accent"
            }
          >
            <div className="flex items-start gap-2">
              <Button
                slot="drag"
                aria-label={dragLabel(item)}
                className={
                  "-ml-1 rounded p-1 text-muted data-hovered:text-fg " +
                  "data-focus-visible:outline-2 data-focus-visible:outline-accent"
                }
              >
                <DragHandleIcon className="size-4" aria-hidden="true" />
              </Button>
              <div className="flex min-w-0 grow flex-col gap-2">{renderItem(item)}</div>
            </div>
          </GridListItem>
        )}
      </GridList>
    </div>
  );
}
