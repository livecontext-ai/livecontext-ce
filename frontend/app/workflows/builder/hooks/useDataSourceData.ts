import { useQuery } from '@tanstack/react-query';
import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { orchestratorApi } from '@/lib/api';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';

export interface DataSource {
  id: number;
  tenant_id: string;
  name: string;
  description: string;
  source_type: 'INLINE' | 'DATABASE' | 'API' | 'FILE';
  source_config: any;
  status: 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';
  created_at: string;
  updated_at: string;
  created_by: string;
}

export interface DataSourceTable {
  name: string;
  schema?: string;
  type?: string;
}

export interface DataSourceColumn {
  col_id: string;
  header_name: string;
  field: string;
  type: 'text' | 'number' | 'date' | 'boolean' | 'json';
  editable: boolean;
  sortable: boolean;
  filterable: boolean;
  width?: number;
}

const fetchDataSources = async (): Promise<DataSource[]> => {
  const data = await orchestratorApi.getDataSources();
  return (data || []) as unknown as DataSource[];
};

export const useDataSources = (enabled: boolean = true) => {
  // Same tenantId system as /app/data
  const { user, isLoading: authLoading } = useAuthGuard();
  const tenantId = user?.sub || user?.email || 'demo';
  // In a publication preview the publisher's datasource list is private -
  // resolution must come from the planSnapshot's _snapshot_ds_* fields via
  // PublicationSnapshotContext.getDataSourceSnapshot, never the live list.
  const inPreview = !!getActivePublicPreview();

  // Phase 4 (2026-05-18) - org-scoped: data sources are workspace-bound.
  return useOrgScopedQuery({
    queryKey: ['data-sources'] as const,
    queryFn: () => fetchDataSources(),
    enabled: enabled && !authLoading && !inPreview,
    staleTime: 5 * 60 * 1000, // 5 minutes - same as tools/API
    gcTime: 10 * 60 * 1000, // 10 minutes
  });
};

const fetchDataSourceTables = async (dataSourceId: number): Promise<DataSourceTable[]> => {
  // Skip API call for invalid IDs (e.g., -1 placeholder for invalid datasources)
  if (!dataSourceId || dataSourceId <= 0) {
    console.warn(`[fetchDataSourceTables] Skipping fetch for invalid dataSourceId: ${dataSourceId}`);
    return [];
  }

  // Use cached data sources to avoid duplicate request
  const dataSources = await fetchDataSources();
  const dataSource = dataSources.find(ds => ds.id === dataSourceId);

  if (!dataSource) {
    console.warn('[useDataSourceTables] Datasource not found:', dataSourceId);
    return [];
  }
  
  // For DATABASE type, try to fetch tables from the database
  if (dataSource.source_type === 'DATABASE') {
    // First, try to use the table from source_config if it exists
    if (dataSource.source_config?.table) {
      const tableName = dataSource.source_config.table;
      const schema = dataSource.source_config.schema || dataSource.source_config.connection?.split('/').pop()?.split('?')[0] || 'public';
      return [
        {
          name: tableName,
          schema: schema,
          type: 'table'
        }
      ];
    } else {
      // If no table in config, try to fetch tables from API endpoint
      try {
        const tables = await orchestratorApi.getTables(String(dataSourceId));
        if (Array.isArray(tables) && tables.length > 0) {
          return tables.map((t: any) => ({
            name: typeof t === 'string' ? t : t.name || t.table_name,
            schema: typeof t === 'string' ? 'public' : t.schema || t.table_schema || 'public',
            type: typeof t === 'string' ? 'table' : t.type || 'table'
          }));
        } else {
          // Fallback: use datasource name as table name
          return [
            {
              name: dataSource.name,
              schema: 'public',
              type: 'table'
            }
          ];
        }
      } catch (apiErr) {
        // If API call fails, use datasource name as table name
        return [
          {
            name: dataSource.name,
            schema: 'public',
            type: 'table'
          }
        ];
      }
    }
  } else {
    // For other types (INLINE, API, FILE), return empty array as they don't have tables
    return [];
  }
};

export const useDataSourceTables = (dataSourceId: number | null) => {
  // Same tenantId system as /app/data
  const { user, isLoading: authLoading } = useAuthGuard();
  const tenantId = user?.sub || user?.email || 'demo';

  // Use the cached data sources query to avoid duplicate requests
  const { data: dataSources } = useDataSources(true);

  // Only enable query for valid positive IDs (skip -1 placeholder for invalid datasources)
  const isValidId = dataSourceId !== null && dataSourceId > 0;
  const inPreview = !!getActivePublicPreview();

  return useQuery({
    queryKey: ['data-source-tables', dataSourceId, tenantId],
    queryFn: () => fetchDataSourceTables(dataSourceId!),
    enabled: isValidId && !authLoading && !!dataSources && !inPreview, // Wait for dataSources to be loaded
    staleTime: 5 * 60 * 1000, // 5 minutes - same as tools/API
    gcTime: 10 * 60 * 1000, // 10 minutes
  });
};

export const fetchDataSourceColumns = async (dataSourceId: number): Promise<DataSourceColumn[]> => {
  // Skip API call for invalid IDs (e.g., -1 placeholder for invalid datasources)
  if (!dataSourceId || dataSourceId <= 0) {
    console.warn(`[fetchDataSourceColumns] Skipping fetch for invalid dataSourceId: ${dataSourceId}`);
    return [];
  }
  const data = await orchestratorApi.getColumns(String(dataSourceId));
  return (data || []) as unknown as DataSourceColumn[];
};

export const useDataSourceColumns = (dataSourceId: number | null) => {
  // Same tenantId system as /app/data
  const { user, isLoading: authLoading } = useAuthGuard();
  const tenantId = user?.sub || user?.email || 'demo';

  // Only enable query for valid positive IDs (skip -1 placeholder for invalid datasources)
  const isValidId = dataSourceId !== null && dataSourceId > 0;
  const inPreview = !!getActivePublicPreview();

  return useQuery({
    queryKey: ['data-source-columns', dataSourceId, tenantId],
    queryFn: () => fetchDataSourceColumns(dataSourceId!),
    enabled: isValidId && !authLoading && !inPreview,
    staleTime: 5 * 60 * 1000, // 5 minutes - same as tools/API
    gcTime: 10 * 60 * 1000, // 10 minutes
  });
};

