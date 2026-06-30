'use client';

import { useState, useCallback, useMemo } from 'react';
import type { DataTableProps } from '@/components/data-table/types';
import { useDataTableController } from '@/components/data-table/useDataTableController';
import { DataTableToolbar } from '@/components/data-table/DataTableToolbar';
import { ColumnFiltersPanel } from '@/components/data-table/ColumnFiltersPanel';
import { DataTableGrid } from '@/components/data-table/DataTableGrid';
import { DataTablePagination } from '@/components/data-table/DataTablePagination';
import { DataTableModals } from '@/components/data-table/DataTableModals';
import { Breadcrumb } from '@/components/ui/breadcrumb';
import type { BreadcrumbItem } from '@/components/ui/breadcrumb';
import { useRouter } from '@/i18n/navigation';
import { Table } from 'lucide-react';
import ToastContainer from './ToastContainer';

export default function DataTable({
  dataSourceId,
  className = '',
  jsonPath: externalJsonPath,
  workflowContext,
  onNavigate: externalOnNavigate,
  onAddAnalyzeBadges,
  onAnalyzeClick,
  showIdColumn = false,
  readOnly = false,
  embedded = false,
  snapshotData,
  rowFilter,
  serverFilters,
  infiniteScroll = false,
}: DataTableProps) {
  // Snapshot mode forces read-only + embedded - writes would try to hit an endpoint we don't have,
  // and URL-based nav would route to /app/tables/<undefined>.
  const effectiveReadOnly = readOnly || !!snapshotData;
  const effectiveEmbedded = embedded || !!snapshotData;
  const router = useRouter();

  // Internal jsonPath state for embedded mode (side panel / tab)
  const [internalJsonPath, setInternalJsonPath] = useState<string>('');

  // Embedded mode uses internal state; standalone pages use URL (externalJsonPath from route params)
  const jsonPath = externalJsonPath ?? (effectiveEmbedded ? internalJsonPath : '');

  // Determine base path based on current route
  const dataSourceBasePath = '/app/tables';

  // Internal navigation handler
  const handleNavigate = useCallback((newPath: string) => {
    if (externalOnNavigate) {
      // If external handler provided, use it (workflow context)
      externalOnNavigate(newPath);
    } else if (effectiveEmbedded) {
      // Embedded/snapshot mode: stay internal, update state
      setInternalJsonPath(newPath);
    } else {
      // Standalone page: navigate via URL so AppHeader breadcrumb updates
      const pathSegments = newPath.split('.').filter(s => s.length > 0);
      if (pathSegments.length > 0) {
        router.push(`/app/tables/${dataSourceId}/${pathSegments.join('/')}`);
      } else {
        router.push(`/app/tables/${dataSourceId}`);
      }
    }
  }, [externalOnNavigate, effectiveEmbedded, router, dataSourceId]);

  // Breadcrumb for embedded mode
  const handleBreadcrumbClick = useCallback((pathUpToSegment: string) => {
    if (externalOnNavigate) {
      externalOnNavigate(pathUpToSegment);
    } else {
      setInternalJsonPath(pathUpToSegment);
    }
  }, [externalOnNavigate]);

  const breadcrumbItems = useMemo((): BreadcrumbItem[] => {
    const items: BreadcrumbItem[] = [
      { label: '', icon: Table, onClick: () => handleBreadcrumbClick('') }
    ];
    if (jsonPath) {
      const segments = jsonPath.split('.').filter(s => s.length > 0);
      segments.forEach((segment, index) => {
        const pathUpToSegment = segments.slice(0, index + 1).join('.');
        const isLast = index === segments.length - 1;
        items.push({
          label: segment,
          onClick: isLast ? undefined : () => handleBreadcrumbClick(pathUpToSegment),
        });
      });
    }
    return items;
  }, [jsonPath, handleBreadcrumbClick]);

  // Initialize the controller with all data management logic
  const controller = useDataTableController({
    dataSourceId,
    jsonPath,
    workflowContext,
    showIdColumn,
    readOnly: effectiveReadOnly,
    snapshotData,
    serverFilters,
  });

  // Apply optional client-side rowFilter on top of the controller's rows.
  // Filtering happens AFTER fetch + sort + array grouping, so server-side pagination is unchanged
  // (caller is responsible for understanding that filtering only sees the current page).
  const filteredController = useMemo(() => {
    if (!rowFilter) return controller;
    const keepRow = (row: any) => {
      if (row && row.type === 'parent') {
        return Array.isArray(row.subRows) && row.subRows.some(rowFilter);
      }
      return rowFilter(row);
    };
    return {
      ...controller,
      rows: controller.rows.filter(rowFilter),
      displayRows: (controller.displayRows as any[]).filter(keepRow),
    };
  }, [controller, rowFilter]);

  // Navigation handler for URL routing (not used for jsonPath navigation)
  const navigateTo = (path: string) => {
    router.push(path);
  };

  return (
    <div className={`w-full h-full flex flex-col ${className}`}>
      {/* Breadcrumb for embedded mode (side panel / tab) - matches AppHeader style */}
      {effectiveEmbedded && jsonPath && (
        <div className="flex-shrink-0 pb-1">
          <Breadcrumb
            items={breadcrumbItems}
            variant="minimal"
            separator="slash"
          />
        </div>
      )}

      {/* Toolbar: Search, Filters toggle, Export, Selection actions - hidden in read-only/snapshot mode */}
      {!effectiveReadOnly && (
        <div className="flex-shrink-0">
          <DataTableToolbar
            controller={controller}
            workflowContext={workflowContext}
            jsonPath={jsonPath}
            onAddAnalyzeBadges={onAddAnalyzeBadges}
            onAnalyzeClick={onAnalyzeClick}
            embedded={effectiveEmbedded}
          />
        </div>
      )}

      {/* Column Filters Panel */}
      <div className="flex-shrink-0">
        <ColumnFiltersPanel controller={controller} />
      </div>

      {/* Data Grid - sizes to content, shrinks with scroll when too tall */}
      <div className="w-full min-h-[200px] flex flex-col overflow-hidden" style={{ flex: '1 1 auto' }}>
        <DataTableGrid
          controller={filteredController}
          workflowContext={workflowContext}
          jsonPath={jsonPath}
          dataSourceId={dataSourceId}
          onNavigate={handleNavigate}
          navigateTo={navigateTo}
          dataSourceBasePath={dataSourceBasePath}
          infiniteScroll={infiniteScroll}
        />
      </div>

      {/* Pagination - hidden in infinite-scroll mode (the grid renders an in-container
          sentinel that appends the next page when scrolled into view). */}
      {!infiniteScroll && (
        <div className="flex-shrink-0">
          <DataTablePagination controller={controller} />
        </div>
      )}

      {/* Modals */}
      <DataTableModals controller={controller} />

      {/* Toast notifications */}
      <ToastContainer toasts={controller.toasts} onRemoveToast={controller.removeToast} />
    </div>
  );
}
