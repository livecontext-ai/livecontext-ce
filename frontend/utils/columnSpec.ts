import { ColumnMappingSpecNode, ColumnStructure, ColumnVisualType } from '@/types/data-sources';

export type RawMappingSpecValue = string | ColumnMappingSpecNode | null | undefined;

export interface NormalizedColumnSpec {
  key: string;
  path: string;
  type: ColumnVisualType;
  structure: ColumnStructure;
  display?: ColumnMappingSpecNode['display'];
}

const DEFAULT_PATH_PREFIX = 'data.';

/**
 * The field a column is rendered and ranked under: its declared mapping-spec
 * path, or `data.<key>` when it declares none. Exported because anything that
 * ranks columns against a saved `column_order` has to ask about the same
 * spelling the grid does, or the two disagree about one column.
 */
export const columnPathOf = (key: string, path?: string | null): string => {
  if (path && path.trim().length > 0) {
    return path;
  }
  return `${DEFAULT_PATH_PREFIX}${key}`;
};

/** Resolve deprecated type aliases to canonical types. */
const ALIAS_MAP: Record<string, ColumnVisualType> = {
  boolean: 'checkbox',
  badge: 'select',
  tags: 'multi_select',
  link: 'url',
  json: 'text',
  code: 'text',
};

/** The 15 canonical column types. */
const CANONICAL_TYPES: ColumnVisualType[] = [
  'text','number','date','checkbox','select','multi_select',
  'rating','sentiment','progress','file','image','email','phone','url','vector',
];

const normalizeType = (type?: ColumnVisualType | string | null): ColumnVisualType => {
  if (!type) return 'text';
  const lower = type.toLowerCase();
  // Resolve alias first
  const resolved = ALIAS_MAP[lower];
  if (resolved) return resolved;
  // Check canonical types
  if (CANONICAL_TYPES.includes(lower as ColumnVisualType)) return lower as ColumnVisualType;
  return 'text';
};

/** Resolve a column type, handling deprecated aliases. Exported for cell rendering. */
export const resolveColumnType = (type: ColumnVisualType | string): ColumnVisualType => {
  return normalizeType(type);
};

const normalizeStructure = (structure?: ColumnStructure | string | null): ColumnStructure => {
  if (!structure) return 'scalar';
  const lower = structure.toLowerCase() as ColumnStructure;
  const allowed: ColumnStructure[] = ['scalar','object','array'];
  return allowed.includes(lower) ? lower : 'scalar';
};

export const normalizeMappingSpecValue = (key: string, value: RawMappingSpecValue): NormalizedColumnSpec => {
  if (value && typeof value === 'object' && 'path' in value) {
    const node = value as ColumnMappingSpecNode;
    return {
      key,
      path: columnPathOf(key, node.path),
      type: normalizeType(node.type),
      structure: normalizeStructure(node.structure),
      display: node.display,
    };
  }

  const path = typeof value === 'string' ? value : undefined;
  return {
    key,
    path: columnPathOf(key, path),
    type: 'text',
    structure: 'scalar',
  };
};

export const normalizeMappingSpec = (
  mappingSpec: Record<string, RawMappingSpecValue> | undefined | null
): Record<string, NormalizedColumnSpec> => {
  if (!mappingSpec) return {};
  return Object.entries(mappingSpec).reduce<Record<string, NormalizedColumnSpec>>((acc, [key, value]) => {
    acc[key] = normalizeMappingSpecValue(key, value);
    return acc;
  }, {});
};

/**
 * Strip the mapping-spec path prefix from a column identifier.
 *
 * A saved `column_order` array names its columns in TWO spellings, because two
 * different producers write it: the backend appends the BARE column name when a
 * column is created, while the grid saves the rendered field, which carries the
 * `data.` prefix of the mapping-spec path. Both spellings coexist inside the same
 * array in production (a table that was reordered by hand and then gained a
 * column has one of each), so a reader that compares them verbatim and stops
 * there matches nothing.
 */
export const columnOrderKey = (field: string): string => (
  field.startsWith(DEFAULT_PATH_PREFIX) ? field.slice(DEFAULT_PATH_PREFIX.length) : field
);

/**
 * The bare names under which a saved `column_order` entry is AMBIGUOUS: it can
 * be the grid's own lane, or a data column of the same name.
 *
 * This is exactly the backend seed's system list (`DataSourceDefaults
 * .SYSTEM_COLUMNS`), and the reason is that seed's skip rule. It writes these
 * five ahead of the data names AND omits any data field sharing one of them, so
 * a bare `id` entry there could be the lane or an imported column called `id`,
 * with nothing in the array to tell them apart. A data column really can be
 * called `id` or `index`: the reserved-name guard covers CRUD writes only, not
 * a CSV import, whose columns are derived from the data keys.
 *
 * The frontend's own lane list is WIDER (it adds `array_index` and `value`
 * while navigating nested JSON), and those two deliberately do NOT belong here.
 * `initializeColumnOrder` writes them as lanes but never reaches the database,
 * and even in memory it writes each data column's exact `data.<name>` spelling
 * into the same array, so the exact match settles it. The one path that does
 * STORE them as lanes is a drag performed during nested navigation, which PUTs
 * regardless of the path and thereby overwrites the root table's order with
 * nested column names: that array is already meaningless, so it is no reason to
 * treat the two names as ambiguous. Listing them would instead throw away the
 * unambiguous rank of a data column genuinely called `value`, which the backend
 * seed and the column append both write bare, and that column would land
 * wherever its name sorts instead of where it belongs.
 *
 * Known reach limit: this gates the BRIDGE between two spellings, so it cannot
 * help where the collision is EXACT. In nested navigation the seed writes the
 * root lane names beside bare nested keys, so a nested key called `priority`
 * shares a spelling with an entry that is not about it and inherits its index.
 * Narrow (it needs that exact name, and a nested order is state-only unless the
 * nested-drag PUT fires) and not addressed here.
 *
 * `columnSpec.systemOrderFields.test.ts` reads the Java seed and fails if the
 * two lists drift apart, so this stays a mirror rather than an opinion.
 */
export const SYSTEM_ORDER_FIELDS: ReadonlySet<string> = new Set([
  'checkbox', 'index', 'id', 'priority', 'created_at',
]);

/** The saved position of each column, as read from a `column_order` array. */
export interface ColumnOrderRank {
  /** How many distinct columns the saved order names. Zero means "no saved order". */
  size: number;
  /**
   * The saved position of the column rendered under `field`, or `undefined`
   * when the order says nothing about it.
   *
   * The exact spelling is tried first, then the two spellings are bridged,
   * because the grid writes `data.<name>` and the backend's append writes the
   * bare name for the same column. That bridge stops at
   * {@link SYSTEM_ORDER_FIELDS}, where it would be a guess in either direction:
   * a bare `id` entry can be the lane's or an imported data column's.
   */
  of(field: string): number | undefined;
}

/**
 * Rank the entries of a saved `column_order` by position.
 *
 * Rank comes from the ARRAY INDEX, not from an entry's own `order` value: every
 * producer writes the array already in order (the grid renumbers the whole list
 * on save, the backend appends at the end), and the index cannot be missing,
 * duplicated or non-numeric the way the stored field can. The first occurrence
 * of a spelling wins, so a duplicate keeps its earliest position.
 *
 * Entries are read defensively because the array comes straight from a JSONB
 * column: anything that is not an object carrying a non-blank string `field`
 * (or the legacy `name`) contributes no rank.
 */
export const buildColumnOrderRank = (
  order: readonly unknown[] | null | undefined
): ColumnOrderRank => {
  const byField = new Map<string, number>();
  const byKey = new Map<string, number>();

  (order ?? []).forEach((raw, index) => {
    if (!raw || typeof raw !== 'object') return;
    const entry = raw as { field?: unknown; name?: unknown };
    const rawField = entry.field ?? entry.name;
    if (typeof rawField !== 'string') return;
    const field = rawField.trim();
    if (!field) return;

    if (!byField.has(field)) byField.set(field, index);
    const key = columnOrderKey(field);
    if (!byKey.has(key)) byKey.set(key, index);
  });

  return {
    size: byKey.size,
    of: (field: string) => {
      const exact = byField.get(field);
      if (exact !== undefined) return exact;

      // No exact entry, so the two spellings have to be bridged. That bridge is
      // AMBIGUOUS for a lane name, in both directions: a bare `id` entry could
      // be the lane's or an imported data column's, and a `data.id` entry says
      // nothing about the lane. Answer nothing rather than move the wrong one.
      const key = columnOrderKey(field);
      if (SYSTEM_ORDER_FIELDS.has(key)) return undefined;

      return byKey.get(key);
    },
  };
};
