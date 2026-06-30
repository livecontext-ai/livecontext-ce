export type ColumnStructure = 'scalar' | 'object' | 'array';

export type ColumnVisualType =
  // 15 canonical types
  | 'text'
  | 'number'
  | 'date'
  | 'checkbox'
  | 'select'
  | 'multi_select'
  | 'rating'
  | 'sentiment'
  | 'progress'
  | 'file'
  | 'image'
  | 'email'
  | 'phone'
  | 'url'
  | 'vector'
  // Deprecated aliases (resolved at render time)
  | 'boolean'
  | 'badge'
  | 'tags'
  | 'link'
  | 'json'
  | 'code';

export interface ColumnDisplayConfig {
  label?: string;
  color?: string;
  icon?: string;
  max?: number;
  min?: number;
  options?: Array<{ label: string; value: string; color?: string }> | string[];
  imageFit?: 'cover' | 'contain';
  ratio?: string;
  palette?: Record<string, string>;
  format?: 'plain' | 'currency' | 'percentage';
  decimals?: number;
  currencySymbol?: string;
  dateFormat?: 'date' | 'datetime' | 'time';
  labels?: Record<string, string>;
  [key: string]: unknown;
}

export interface ColumnMappingSpecNode {
  path: string;
  type?: ColumnVisualType;
  structure?: ColumnStructure;
  display?: ColumnDisplayConfig;
  children?: Record<string, ColumnMappingSpecNode>;
}

// DataSource Manager types
export interface DataSource {
  id: number;
  tenant_id: string;
  name: string;
  description: string;
  source_type: 'INLINE' | 'DATABASE' | 'API' | 'FILE';
  source_config: Record<string, unknown>;
  created_at: string;
  updated_at: string;
  mapping_spec?: Record<string, unknown>;
  column_order?: ColumnOrder[];
}

export interface DataSourceItemRow {
  id: number;
  data_source_id: number;
  tenant_id: string;
  data: Record<string, unknown>;
  priority: number;
  created_at: string;
  updated_at: string;
}

export interface PaginationResponse<T> {
  rowData: T[];
  row_count: number;
  next_cursor: string | null;
  has_more: boolean;
  total_pages: number;
}

export interface ColumnDefinition {
  col_id: string;
  header_name: string;
  field: string;
  type: ColumnVisualType;
  editable: boolean;
  sortable?: boolean;
  filterable?: boolean;
  width?: number;
}

export interface PaginationState {
  currentPage: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
  nextCursor: string | null;
  hasMore: boolean;
}

export interface ColumnOrder {
  field: string;
  order: number;
}

export interface SortConfig {
  key: string;
  direction: 'asc' | 'desc';
}
