import type { ColumnStructure, ColumnVisualType, ColumnDisplayConfig } from '@/types/data-sources';

export interface DataSourceItemRow {
  id: number;
  data_source_id: number;
  tenant_id: string;
  data: Record<string, any>;
  priority: number;
  created_at: string;
  updated_at: string | null;
  row_index?: number;
  _jsonPath?: string;
  _isWorkflowStep?: boolean;
  _outputStorageId?: string;
}

export interface PaginationResponse<T> {
  rowData: T[];
  row_count: number;
  total_pages?: number;
  next_cursor: string | null;
  has_more: boolean;
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

export interface ColumnDefinition {
  col_id: string;
  header_name: string;
  field: string;
  type: ColumnVisualType;
  editable: boolean;
  sortable: boolean;
  filterable: boolean;
  width?: number;
  isNavigable?: boolean;
  displayConfig?: ColumnDisplayConfig;
  structure?: ColumnStructure;
  // Backend render type for node-specific display
  renderType?: string;
  // Whether this column supports expansion (e.g., for JSON or tables)
  expandable?: boolean;
}

export interface SnapshotTableData {
  rows: DataSourceItemRow[];
  columns: ColumnDefinition[];
  columnOrder?: ColumnOrder[];
}

export interface DataTableProps {
  /** Required for live mode; ignored when snapshotData is provided. */
  dataSourceId?: number;
  className?: string;
  onDataSourceChange?: (dataSource: any) => void;
  jsonPath?: string;
  workflowContext?: {
    workflowId: string;
    runId: string;
    stepId?: number;
    stepAlias?: string;
    isAggregated?: boolean;
  };
  onNavigate?: (path: string) => void;
  onRowClick?: (row: DataSourceItemRow) => void;
  onAddAnalyzeBadges?: (ids: string[], type: 'data' | 'workflow') => void;
  onAnalyzeClick?: () => void;
  showIdColumn?: boolean; // Afficher la colonne id (callId) uniquement dans la modal
  readOnly?: boolean;
  /** When true, navigation stays internal (state-based) with an in-table breadcrumb.
   *  Used when DataTable is embedded in a side panel or tab. */
  embedded?: boolean;
  /** Frozen snapshot data (marketplace preview). When provided the table skips every
   *  HTTP fetch and renders rows/columns directly from the snapshot. Forces readOnly. */
  snapshotData?: SnapshotTableData;
  /** Optional client-side row predicate. Applied to fetched rows just before rendering;
   *  pagination/sorting still come from the server. Pass null/undefined to disable. */
  rowFilter?: ((row: DataSourceItemRow) => boolean) | null;
  /** Optional server-side filters forwarded to the workflow stepAlias detailed endpoint
   *  so totals/pagination reflect the filtered set (instead of filtering only the current
   *  page client-side). Currently consumed by the workflow step table. */
  serverFilters?: ServerFilters | null;
  /** Replace the prev/next paginator with an IntersectionObserver-based "load more"
   *  sentinel that appends the next page when scrolled to the bottom of the grid.
   *  Used by the workflow step table where users want to scroll through every epoch. */
  infiniteScroll?: boolean;
}

/** Server-side filter inputs for the workflow stepAlias detailed endpoint. */
export interface ServerFilters {
  /** Canonical StatusType value (e.g. "completed"). null/empty = no filter. */
  status?: string | null;
  /** Epoch number. null = no filter. */
  epoch?: number | null;
}
