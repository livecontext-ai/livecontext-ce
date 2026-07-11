import type { DataTableController } from '@/components/data-table/useDataTableController';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ChevronLeft, ChevronRight } from 'lucide-react';

interface DataTablePaginationProps {
  controller: DataTableController;
}

export function DataTablePagination({ controller }: DataTablePaginationProps) {
  const { pagination, handlePageChange, handlePageSizeChange, rows } = controller;

  // Use rows.length as the source of truth for displayed rows count
  const visibleRows = rows.length;
  // Use pagination.totalItems if available, otherwise fallback to rows.length
  const total = pagination.totalItems > 0 ? pagination.totalItems : visibleRows;

  const { selectedRows, selectedColumns } = controller;
  const hasSelection = selectedRows.size > 0 || selectedColumns.size > 0;

  return (
    <div className="mt-4 flex items-center justify-between">
      <div className="text-sm text-theme-secondary">
        {hasSelection && (
          <span>
            {selectedRows.size > 0 && `${selectedRows.size} row${selectedRows.size > 1 ? 's' : ''}`}
            {selectedRows.size > 0 && selectedColumns.size > 0 && ' + '}
            {selectedColumns.size > 0 && `${selectedColumns.size} column${selectedColumns.size > 1 ? 's' : ''}`}
            {' selected'}
          </span>
        )}
      </div>
      <div className="flex items-center">
      {pagination.totalPages > 1 && (
        <div className="flex items-center gap-1">
          <button
            className="w-7 h-7 p-0 rounded-full transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]"
            onClick={() => handlePageChange(pagination.currentPage - 1)}
            disabled={pagination.currentPage <= 1}
            aria-label="Previous"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
          </button>

          <span className="text-sm text-theme-secondary px-2">
            {pagination.currentPage} / {pagination.totalPages}
          </span>

          <button
            className="w-7 h-7 p-0 rounded-full transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]"
            onClick={() => handlePageChange(pagination.currentPage + 1)}
            disabled={pagination.currentPage >= pagination.totalPages}
            aria-label="Next"
          >
            <ChevronRight className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      <div className="flex items-center gap-2 ml-4">
        <span className="text-sm text-theme-secondary">Rows per page:</span>
        <Select value={pagination.pageSize.toString()} onValueChange={(value) => handlePageSizeChange(Number(value))}>
          <SelectTrigger className="w-[120px] h-9 text-sm">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="10">10</SelectItem>
            <SelectItem value="20">20</SelectItem>
            <SelectItem value="50">50</SelectItem>
            <SelectItem value="100">100</SelectItem>
          </SelectContent>
        </Select>
      </div>

      <div className="text-sm text-theme-secondary ml-4">
        {total === 0 ? 'No rows' : `Showing ${visibleRows} of ${total} rows`}
      </div>
      </div>
    </div>
  );
}
