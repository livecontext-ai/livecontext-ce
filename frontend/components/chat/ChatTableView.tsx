'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { ExternalLink, Table, X } from 'lucide-react';
import { PreviewActionMenu, ActionIcons } from './PreviewActionMenu';
import { ConfirmDeleteModal } from './ConfirmDeleteModal';
import { SimpleToast } from './SimpleToast';
import { useDeleteFlow } from '@/hooks/useDeleteFlow';
import { useRouter } from '@/i18n/navigation';
import { orchestratorApi } from '@/lib/api/orchestrator';
import { apiClient } from '@/lib/api';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { DataSourcePanelContent } from '@/components/app/DataSourcePanelContent';

interface ChatTableViewProps {
  dataSourceId: number;
  className?: string;
  maxRows?: number;
  onDelete?: () => void;
  readOnly?: boolean;
}

interface ColumnDef {
  field: string;
  header: string;
}

interface RowData {
  id: number;
  data: Record<string, any>;
}

export function ChatTableView({ dataSourceId, className = '', maxRows = 10, onDelete, readOnly = false }: ChatTableViewProps) {
  const t = useTranslations('chat');
  const router = useRouter();
  const sidePanel = useSidePanelSafe();

  const [dataSourceName, setDataSourceName] = useState<string>('');
  const [columns, setColumns] = useState<ColumnDef[]>([]);
  const [rows, setRows] = useState<RowData[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [totalCount, setTotalCount] = useState(0);
  const [notFound, setNotFound] = useState(false);

  const deleteFn = useCallback(async () => { await orchestratorApi.deleteDataSource(String(dataSourceId)); }, [dataSourceId]);
  const { isDeleted, showDeleteModal, isDeleting, toast, hideToast, handleDeleteClick, handleConfirmDelete, handleCancelDelete } = useDeleteFlow({
    deleteFn,
    successMessage: 'Table deleted successfully',
    errorMessage: 'Failed to delete table',
    onDeleted: onDelete,
  });

  // Fetch datasource data
  useEffect(() => {
    const fetchData = async () => {
      setLoading(true);
      setError(null);

      try {
        // Fetch all datasources to get name and mapping_spec
        const dataSources = await orchestratorApi.getDataSources();
        const currentDataSource = dataSources?.find((ds) => Number(ds.id) === dataSourceId) as any;

        let userColumns: ColumnDef[] = [];
        if (currentDataSource) {
          setDataSourceName(currentDataSource.name || 'Table');

          // Extract columns from mapping_spec
          if (currentDataSource.mapping_spec) {
            const mappingSpec = currentDataSource.mapping_spec as Record<string, any>;
            userColumns = Object.entries(mappingSpec)
              .filter(([key]) => !['id', 'priority', 'created_at', 'updated_at', 'checkbox', 'data_source_id', 'tenant_id'].includes(key))
              .slice(0, 6) // Max 6 columns for compact view
              .map(([key, spec]: [string, any]) => ({
                field: `data.${key}`,
                header: spec?.display?.label || spec?.label || key
              }));
          }
        } else {
          setDataSourceName('Table');
        }

        // The /data-sources list is org-strict: a table the caller owns but that is
        // tagged to another workspace (or whose mapping_spec is empty) is absent or
        // column-less above, even though its rows load below. Fall back to the per-id
        // columns endpoint (owner-permissive, same scope as the rows; infers from the
        // JSON data keys when mapping_spec is empty) so the card shows real columns
        // instead of its empty state.
        if (userColumns.length === 0) {
          try {
            const apiColumns = await apiClient.get<Array<{ field?: string; col_id?: string; header_name?: string }>>(
              `/data-sources/${dataSourceId}/columns`
            );
            if (Array.isArray(apiColumns)) {
              const systemFields = new Set(['id', 'priority', 'created_at']);
              userColumns = apiColumns
                .filter((col) => !systemFields.has(String(col?.field ?? col?.col_id)))
                .slice(0, 6)
                .map((col) => ({
                  field: String(col?.field ?? col?.col_id),
                  header: col?.header_name || String(col?.field ?? col?.col_id),
                }));
            }
          } catch {
            // Non-fatal - leave columns empty; the card keeps its empty state.
          }
        }
        setColumns(userColumns);

        // Fetch rows with limit
        const rowResponse = await apiClient.get<any>(`/data-sources/${dataSourceId}/items?limit=${maxRows}&page=1`);
        if (rowResponse) {
          const items = rowResponse.items || rowResponse.data || rowResponse.rowData || [];
          setRows(items.slice(0, maxRows));
          setTotalCount(rowResponse.totalItems || rowResponse.count || rowResponse.row_count || items.length);
        }
      } catch (err: any) {
        // Check for 404 (resource not found)
        const errorMessage = err.message || '';
        if (errorMessage.includes('404') || errorMessage.toLowerCase().includes('not found')) {
          setNotFound(true);
        } else {
          setError(err.message || 'Failed to load table');
        }
        setDataSourceName('Table');
      } finally {
        setLoading(false);
      }
    };

    if (!isDeleted) {
      fetchData();
    }
  }, [dataSourceId, maxRows, isDeleted]);

  const handleExpandTable = () => {
    router.push(`/app/tables/${dataSourceId}`);
  };

  const tabId = `datasource-${dataSourceId}`;
  const isTabActive = sidePanel?.isOpen && sidePanel?.activeTabId === tabId;

  const handleCardClick = () => {
    if (sidePanel) {
      if (isTabActive) {
        sidePanel.removeTab(tabId);
        sidePanel.close();
        return;
      }
      sidePanel.openTab({
        id: tabId,
        label: dataSourceName || 'Table',
        icon: <Table className="h-4 w-4" />,
        content: <DataSourcePanelContent dataSourceId={String(dataSourceId)} readOnly={readOnly} />,
        preferredWidth: 0.35,
        onDelete: handleDeleteClick,
      });
    } else {
      handleExpandTable();
    }
  };

  const getValue = (row: RowData, field: string): string => {
    const value = field.startsWith('data.')
      ? row.data?.[field.replace('data.', '')]
      : (row as any)[field] ?? row.data?.[field];

    if (value === null || value === undefined) return '-';
    if (typeof value === 'object') return JSON.stringify(value).slice(0, 50) + '...';
    if (typeof value === 'boolean') return value ? 'Yes' : 'No';
    return String(value).slice(0, 100);
  };

  // Don't render if deleted
  if (isDeleted) {
    return null;
  }

  // Not found state
  if (notFound) {
    return (
      <div className={`my-6 rounded-[18px] border border-theme overflow-hidden bg-theme-primary ${className}`}>
        <div className="flex flex-col items-center justify-center min-h-[120px] text-theme-muted">
          <Table className="w-8 h-8 mb-2 opacity-50" />
          <span className="text-sm">{t('tableView.notFound')}</span>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-32 bg-red-50 dark:bg-red-900/20 rounded-xl border border-red-200 dark:border-red-800 text-red-600 dark:text-red-400 text-sm">
        {error}
      </div>
    );
  }

  return (
    <div className={`relative my-6 isolate ${className}`} onClick={handleCardClick}>
      {/* Active tab overlay */}
      {isTabActive && (
        <div className="absolute inset-0 z-20 bg-black/5 backdrop-blur-[3px] flex items-center justify-center rounded-[18px] cursor-pointer">
          <div className="flex items-center gap-2 px-4 py-2 bg-white/90 dark:bg-slate-800/90 rounded-full">
            <X className="w-4 h-4 text-theme-primary" />
            <span className="text-sm font-medium text-theme-primary">{t('clickToClose')}</span>
          </div>
        </div>
      )}
      {/* Table Container */}
      <div className="rounded-[18px] border border-theme overflow-hidden bg-theme-primary cursor-pointer">

        {/* Table */}
        {loading ? (
          <div className="overflow-hidden flex flex-col">
            {/* Skeleton Header */}
            <div className="bg-theme-secondary border-b border-theme px-4 py-3 flex">
              {[1, 2, 3, 4].map((i) => (
                <div key={i} className="flex-1 px-2">
                  <div className="h-4 bg-theme-tertiary rounded animate-pulse w-3/4" />
                </div>
              ))}
            </div>
            {/* Skeleton Rows */}
            <div className="flex-1">
              {[1, 2, 3, 4].map((rowIdx) => (
                <div key={rowIdx} className="px-4 py-4 flex h-14 items-center">
                  {[1, 2, 3, 4].map((colIdx) => (
                    <div key={colIdx} className="flex-1 px-2">
                      <div
                        className="h-4 bg-theme-secondary rounded animate-pulse"
                        style={{ width: `${50 + (colIdx * 10)}%`, maxWidth: '90%' }}
                      />
                    </div>
                  ))}
                </div>
              ))}
            </div>
          </div>
        ) : columns.length > 0 ? (
          <>
            <div className="overflow-auto max-h-[360px]">
              <table className="w-full text-sm table-fixed">
                <thead className="bg-theme-secondary border-b border-theme sticky top-0 z-10">
                  <tr>
                    {columns.map((col) => (
                      <th
                        key={col.field}
                        className="px-4 py-3 text-left font-medium text-theme-primary whitespace-nowrap"
                      >
                        <span className="truncate block max-w-[150px]" title={col.header}>
                          {col.header}
                        </span>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {rows.length > 0 ? rows.map((row, rowIdx) => (
                    <tr
                      key={row.id || rowIdx}
                      className="hover:bg-theme-secondary/50 transition-colors h-14"
                    >
                      {columns.map((col) => (
                        <td
                          key={`${row.id}-${col.field}`}
                          className="px-4 py-4 text-theme-primary"
                        >
                          <span
                            className="text-sm truncate block max-w-[200px]"
                            title={getValue(row, col.field)}
                          >
                            {getValue(row, col.field)}
                          </span>
                        </td>
                      ))}
                    </tr>
                  )) : (
                    <tr>
                      <td colSpan={columns.length} className="px-4 py-8 text-center text-theme-muted">
                        <span className="text-sm">{t('tableView.noRows')}</span>
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            {/* Footer - always visible */}
            <div className="bg-white/80 dark:bg-slate-800/80 backdrop-blur-sm border-t border-theme px-4 py-3">
              <div className="flex items-center justify-between">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <Table className="w-4 h-4 text-theme-muted shrink-0" />
                    <span className="text-sm font-medium text-theme-primary truncate">{dataSourceName}</span>
                    <span className="text-xs text-theme-muted shrink-0">
                      {totalCount} row{totalCount !== 1 ? 's' : ''} · {columns.length} col{columns.length !== 1 ? 's' : ''}
                    </span>
                  </div>
                </div>
                <div onClick={e => e.stopPropagation()}>
                  <PreviewActionMenu
                    items={[
                      {
                        id: 'open',
                        label: 'Open',
                        icon: ActionIcons.open,
                        onClick: handleExpandTable,
                      },
                      {
                        id: 'delete',
                        label: 'Delete',
                        icon: ActionIcons.delete,
                        onClick: handleDeleteClick,
                        variant: 'danger',
                      },
                    ]}
                  />
                </div>
              </div>
            </div>
          </>
        ) : (
          <div className="flex flex-col items-center justify-center h-[600px] text-theme-muted">
            <Table className="w-10 h-10 mb-3 opacity-50" />
            <p className="text-sm">{t('tableView.noData')}</p>
            <button
              onClick={handleExpandTable}
              className="mt-3 flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-theme-primary bg-theme-secondary hover:bg-theme-tertiary border border-theme rounded transition-colors"
            >
              <ExternalLink className="h-3 w-3" />
              Open table
            </button>
          </div>
        )}

      </div>

      {/* Delete confirmation modal */}
      <ConfirmDeleteModal
        isOpen={showDeleteModal}
        title="Delete Table"
        message={`Are you sure you want to delete "${dataSourceName}"? This action cannot be undone.`}
        onConfirm={handleConfirmDelete}
        onCancel={handleCancelDelete}
        isLoading={isDeleting}
      />

      {/* Toast notification */}
      {toast && (
        <SimpleToast
          type={toast.type}
          message={toast.message}
          isVisible={!!toast}
          onClose={hideToast}
        />
      )}
    </div>
  );
}
