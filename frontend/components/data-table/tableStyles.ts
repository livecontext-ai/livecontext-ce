// Shared table style constants for DataTable components

// Fixed column width for "Add Column" button
export const FIXED_COLUMN_WIDTH = '32px'; // 2rem = w-8

// Minimum width for checkbox column (button size)
export const MIN_CHECKBOX_COLUMN_WIDTH = 32; // px

// Maximum width for checkbox column
export const MAX_CHECKBOX_COLUMN_WIDTH = 120; // px

// Minimum width for standalone ID column (workflow tables without checkbox)
export const MIN_ID_COLUMN_WIDTH = 48; // px

// Minimum width for the run-logs ID lane: 16 padding + 14 type icon + 8 gap + "ID" + 8 gap + 14 hint icon
export const MIN_WORKFLOW_ID_LANE_WIDTH = 88; // px

// Common cell padding classes
export const CELL_PADDING = {
  checkbox: 'px-1',
  id: 'px-2',
  default: 'px-3',
} as const;

/**
 * The cue that shows a user where the column they just created landed.
 *
 * `addNewColumn` refetches the whole grid, so a new column simply appears - appended to the right
 * of a table that is usually wider than the viewport, which is to say: off screen. The grid scrolls
 * it into view and flags it with these two classes for {@link TABLE_REVEAL_WINDOW_MS}; what the
 * cue looks like and how long it plays lives entirely in globals.css.
 */
export const COLUMN_REVEAL_HEAD_CLASS = 'table-column-reveal-head';
export const COLUMN_REVEAL_CELL_CLASS = 'table-column-reveal-cell';

/**
 * The same cue for a whole row, worn by every copy a duplicate just produced.
 *
 * Duplicates are written server-side and land wherever the table's order puts them (by default at
 * the top, since rows are newest-first), so without this a "3 rows duplicated" toast is the only
 * evidence anything happened.
 */
export const ROW_REVEAL_CLASS = 'table-row-reveal';

/**
 * How long the grid keeps either flag on, in ms.
 *
 * A WINDOW, not the animation's duration: the classes above are applied from React state, so a CSS
 * animation that has already finished costs nothing while the flag stays up. What the window buys
 * is that the flag comes OFF - otherwise a row that mounts much later (an infinite-scroll page, a
 * row the user adds) would animate its cell in that column long after the column stopped being new.
 * It must stay >= the animation duration in globals.css, which `tableReveal.css.test.ts` pins.
 */
export const TABLE_REVEAL_WINDOW_MS = 2000;

// Row height constraints
export const ROW_HEIGHT = {
  min: '62px',
  default: '62px',
} as const;

// Fixed column style generators
export const getCheckboxColumnStyle = (width: string) => ({
  width,
  minWidth: width,
  maxWidth: width,
});

export const getIdColumnStyle = (leftOffset: string | number, width?: string) => ({
  left: leftOffset,
  zIndex: 35,
  ...(width ? { width, minWidth: width, maxWidth: width } : { minWidth: '100px', maxWidth: '150px' }),
});

export const getDefaultColumnStyle = () => ({
  minWidth: '150px',
});

export const getFixedAddColumnStyle = () => ({
  width: FIXED_COLUMN_WIDTH,
  minWidth: FIXED_COLUMN_WIDTH,
  maxWidth: FIXED_COLUMN_WIDTH,
});

// Z-index values for sticky elements
export const Z_INDEX = {
  headerCheckbox: 40,
  headerId: 35,
  headerDefault: 20,
  bodyCheckbox: 30,
  bodyId: 35,
  addColumn: 40,
  addRowForm: 30,
} as const;

// Sticky position classes
export const STICKY_CLASSES = {
  checkbox: 'sticky left-0',
  id: 'sticky',
  headerTop: 'sticky top-0',
  addColumnRight: 'sticky right-0',
  addRowBottom: 'sticky bottom-0',
} as const;

// Background classes for theme
export const BG_CLASSES = {
  header: 'bg-theme-secondary',
  row: 'bg-theme-primary',
  rowHover: 'hover-row-item',
  selected: 'bg-theme-tertiary',
} as const;

// Calculate optimal checkbox column width based on row IDs
export function calculateCheckboxColumnWidth(
  rows: Array<{ id: number | string }>,
  displayRows?: Array<{ type?: string; parentId?: number }>
): string {
  if (!rows || rows.length === 0) {
    return `${MIN_CHECKBOX_COLUMN_WIDTH}px`;
  }

  let maxIdLength = 0;

  // Check normal row IDs
  rows.forEach((row) => {
    const idString = String(row.id);
    if (idString.length > maxIdLength) {
      maxIdLength = idString.length;
    }
  });

  // Check parent row IDs in displayRows
  if (displayRows) {
    displayRows.forEach((rowOrGroup) => {
      if (rowOrGroup.type === 'parent' && rowOrGroup.parentId !== undefined) {
        const idString = String(rowOrGroup.parentId);
        if (idString.length > maxIdLength) {
          maxIdLength = idString.length;
        }
      }
    });
  }

  // Calculate required width (~8px per character for font-mono text-sm)
  // + padding (px-1 = 4px each side = 8px total) + safety margin
  const estimatedWidth = maxIdLength * 8 + 16;

  // Apply min/max constraints
  const width = Math.max(MIN_CHECKBOX_COLUMN_WIDTH, Math.min(MAX_CHECKBOX_COLUMN_WIDTH, estimatedWidth));

  return `${width}px`;
}
