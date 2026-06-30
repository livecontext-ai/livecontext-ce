// Shared table style constants for DataTable components

// Fixed column width for "Add Column" button
export const FIXED_COLUMN_WIDTH = '32px'; // 2rem = w-8

// Minimum width for checkbox column (button size)
export const MIN_CHECKBOX_COLUMN_WIDTH = 32; // px

// Maximum width for checkbox column
export const MAX_CHECKBOX_COLUMN_WIDTH = 120; // px

// Minimum width for standalone ID column (workflow tables without checkbox)
export const MIN_ID_COLUMN_WIDTH = 48; // px

// Common cell padding classes
export const CELL_PADDING = {
  checkbox: 'px-1',
  id: 'px-2',
  default: 'px-3',
} as const;

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
