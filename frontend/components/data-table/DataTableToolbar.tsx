import { useState } from 'react';
import { useTranslations } from 'next-intl';
import type { DataTableProps } from '@/components/data-table/types';
import type { DataTableController } from '@/components/data-table/useDataTableController';
import { BulkDeleteModal } from '@/components/ui/BulkDeleteModal';
import { SelectionActionBar, BulkBarButton } from '@/components/ui/SelectionActionBar';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Download, FileJson, FileSpreadsheet, FileText, Filter, Plus, Search, Sparkles, Trash2, X } from 'lucide-react';

interface DataTableToolbarProps {
  controller: DataTableController;
  workflowContext?: DataTableProps['workflowContext'];
  jsonPath?: string;
  onAddAnalyzeBadges?: DataTableProps['onAddAnalyzeBadges'];
  onAnalyzeClick?: DataTableProps['onAnalyzeClick'];
  /** Embedded (side panel / modal / builder inspector) - keep selection actions
   *  inline. Standalone (full table page) floats them in a bottom-center bar,
   *  which is anchored to <main> and would be misplaced inside a side panel. */
  embedded?: boolean;
}

export function DataTableToolbar({
  controller,
  workflowContext,
  jsonPath,
  onAddAnalyzeBadges,
  onAnalyzeClick,
  embedded = false,
}: DataTableToolbarProps) {
  const t = useTranslations('dataTable');
  const tc = useTranslations('common');
  const {
    searchQuery,
    setSearchQuery,
    showColumnFilters,
    setShowColumnFilters,
    selectedExportFormat,
    setSelectedExportFormat,
    selectedRows,
    selectedColumns,
    handleExportCSV,
    handleExportExcel,
    handleExportFull,
    handleExportJSON,
    exportLoading,
    setShowAddColumnModal,
    setShowCreateDataSourceModal,
    deleteSelectedRows,
    deleteSelectedColumns,
    confirmDeleteColumns,
    showDeleteColumnsModal,
    setShowDeleteColumnsModal,
    columnsToDelete,
    clearSelection,
    clearColumnSelection,
    readOnly,
  } = controller;

  const [showDeleteRowsModal, setShowDeleteRowsModal] = useState(false);

  const handleExportClick = async () => {
    if (selectedExportFormat === 'csv') {
      await handleExportCSV();
    } else if (selectedExportFormat === 'json') {
      await handleExportJSON();
    } else if (selectedExportFormat === 'xlsx') {
      await handleExportExcel();
    } else if (selectedExportFormat === 'csv-full') {
      await handleExportFull('csv');
    } else if (selectedExportFormat === 'json-full') {
      await handleExportFull('json');
    } else if (selectedExportFormat === 'xlsx-full') {
      await handleExportFull('xlsx');
    }
  };

  return (
    <div className="flex flex-col gap-4 mb-4">
      <div className="flex flex-col sm:flex-row sm:items-center gap-4 w-full">
        <div className="flex items-center gap-4 flex-1 w-full">
          <div className="relative flex-1 w-full overflow-visible">
            <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-theme-muted" />
            <Input
              placeholder={t('searchPlaceholder')}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-10 w-full bg-theme-primary text-theme-primary border-theme focus-visible:ring-inset focus-visible:ring-offset-0"
            />
          </div>
          <Button
            variant={showColumnFilters ? 'default' : 'ghost'}
            size="sm"
            onClick={() => setShowColumnFilters(!showColumnFilters)}
            className="shrink-0"
          >
            <Filter className="h-4 w-4 mr-1.5" />
            {t('filters')}
          </Button>

          <div className="flex items-center gap-2 shrink-0">
            <Select value={selectedExportFormat || undefined} onValueChange={setSelectedExportFormat}>
              <SelectTrigger className="w-auto min-w-[140px] h-9 px-4">
                <div className="flex items-center space-x-2">
                  <Download className="h-4 w-4" />
                  <SelectValue placeholder={t('selectFormat')} />
                </div>
              </SelectTrigger>
              <SelectContent>
                {(selectedRows.size > 0 || selectedColumns.size > 0) && (
                  <>
                    <SelectItem value="csv">
                      <FileText className="w-4 h-4 inline mr-2" />
                      {t('csvCurrentView')}
                    </SelectItem>
                    <SelectItem value="json">
                      <FileJson className="w-4 h-4 inline mr-2" />
                      {t('jsonCurrentView')}
                    </SelectItem>
                    <SelectItem value="xlsx">
                      <FileSpreadsheet className="w-4 h-4 inline mr-2" />
                      {t('excelCurrentView')}
                    </SelectItem>
                  </>
                )}
                <SelectItem value="csv-full">
                  <FileText className="w-4 h-4 inline mr-2" />
                  {t('csvAllData')}
                </SelectItem>
                <SelectItem value="json-full">
                  <FileJson className="w-4 h-4 inline mr-2" />
                  {t('jsonAllData')}
                </SelectItem>
                <SelectItem value="xlsx-full">
                  <FileSpreadsheet className="w-4 h-4 inline mr-2" />
                  {t('excelAllData')}
                </SelectItem>
              </SelectContent>
            </Select>

            {selectedExportFormat && (
              <Button variant="default" size="sm" onClick={handleExportClick} disabled={exportLoading}>
                {exportLoading ? (
                  <div className="flex items-center gap-2">
                    <div className="w-4 h-4 border-2 border-theme-primary border-t-transparent rounded-full animate-spin"></div>
                    <span>{t('exporting')}</span>
                  </div>
                ) : (
                  t('export')
                )}
              </Button>
            )}
          </div>
        </div>
      </div>

      {(selectedRows.size > 0 || selectedColumns.size > 0) && (() => {
        const clearAll = () => {
          clearSelection();
          clearColumnSelection();
        };
        // Selection-contextual actions, shared between the inline (embedded) and the
        // floating (standalone) layouts so the conditions never diverge.
        const actions = (
          <>
            <BulkBarButton onClick={() => setShowCreateDataSourceModal(true)}>
              <Plus className="h-3.5 w-3.5" />
              {t('createTable')}
            </BulkBarButton>

            {selectedRows.size > 0 && onAddAnalyzeBadges && (
              <BulkBarButton
                onClick={() => {
                  const rowIds = Array.from(selectedRows)
                    .map((key) => key.split('-')[0])
                    .filter((id, index, self) => self.indexOf(id) === index);

                  onAddAnalyzeBadges?.(rowIds, 'data');
                  if (onAnalyzeClick) {
                    onAnalyzeClick();
                  }
                }}
              >
                <Sparkles className="h-3.5 w-3.5" />
                {t('analyzeData')}
              </BulkBarButton>
            )}

            {selectedRows.size > 0 && !readOnly && (
              <BulkBarButton variant="danger" onClick={() => setShowDeleteRowsModal(true)}>
                <Trash2 className="h-3.5 w-3.5" />
                {t('deleteRows')}
              </BulkBarButton>
            )}

            {selectedColumns.size > 0 && !readOnly && (
              <BulkBarButton variant="danger" onClick={deleteSelectedColumns}>
                <Trash2 className="h-3.5 w-3.5" />
                {t('deleteColumnsButton')}
              </BulkBarButton>
            )}
          </>
        );

        // Embedded (side panel / modal / builder inspector): inline - a floating
        // bar would anchor to <main> and float over the whole app, not the panel.
        if (embedded) {
          return (
            <div className="flex flex-col gap-3">
              <div className="flex items-center gap-2 flex-wrap">
                <div className="w-px h-8 bg-theme-muted opacity-30" />
                <div className="flex items-center gap-1.5 flex-wrap">
                  {actions}
                  <BulkBarButton onClick={clearAll} title={tc('clearSelection')}>
                    <X className="h-3.5 w-3.5" />
                    {tc('cancel')}
                  </BulkBarButton>
                </div>
              </div>
            </div>
          );
        }

        // Standalone full-page table: float the actions in a bottom-center bar.
        return (
          <SelectionActionBar count={selectedRows.size + selectedColumns.size} onClear={clearAll}>
            {actions}
          </SelectionActionBar>
        );
      })()}
      <BulkDeleteModal
        isOpen={showDeleteRowsModal}
        title={t('deleteRows')}
        message={t('deleteRowsConfirm', { count: selectedRows.size })}
        confirmLabel={tc('delete')}
        cancelLabel={tc('cancel')}
        onConfirm={() => {
          deleteSelectedRows();
          setShowDeleteRowsModal(false);
        }}
        onCancel={() => setShowDeleteRowsModal(false)}
      />

      <BulkDeleteModal
        isOpen={showDeleteColumnsModal}
        title={t('deleteColumnsButton')}
        message={t('deleteColumnsConfirm', { count: columnsToDelete.length })}
        confirmLabel={tc('delete')}
        cancelLabel={tc('cancel')}
        onConfirm={() => {
          confirmDeleteColumns();
          setShowDeleteColumnsModal(false);
        }}
        onCancel={() => setShowDeleteColumnsModal(false)}
      />
    </div>
  );
}
