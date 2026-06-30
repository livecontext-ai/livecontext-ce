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

const normalizePath = (key: string, path?: string | null) => {
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
      path: normalizePath(key, node.path),
      type: normalizeType(node.type),
      structure: normalizeStructure(node.structure),
      display: node.display,
    };
  }

  const path = typeof value === 'string' ? value : undefined;
  return {
    key,
    path: normalizePath(key, path),
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
