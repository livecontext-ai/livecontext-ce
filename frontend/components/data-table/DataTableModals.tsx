import { useTranslations } from 'next-intl';
import { AddColumnModal } from '@/components/data-table/modals/AddColumnModal';
import { EditColumnModal } from '@/components/data-table/modals/EditColumnModal';
import type { DataTableController } from '@/components/data-table/useDataTableController';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Plus, Table } from 'lucide-react';

interface DataTableModalsProps {
  controller: DataTableController;
}

export function DataTableModals({ controller }: DataTableModalsProps) {
  const t = useTranslations('dataTable');
  const {
    showAddRowModal,
    setShowAddRowModal,
    newRowData,
    setNewRowData,
    newRowPriority,
    setNewRowPriority,
    getDynamicColumns,
    handleRowDataChange,
    isAddingRow,
    addNewRow,
    showAddColumnModal,
    setShowAddColumnModal,
    newColumnName,
    setNewColumnName,
    selectedColumnStyle,
    setSelectedColumnStyle,
    isAddingColumn,
    addNewColumn,
    showCreateDataSourceModal,
    setShowCreateDataSourceModal,
    newDataSourceName,
    setNewDataSourceName,
    newDataSourceDescription,
    setNewDataSourceDescription,
    createDataSourceFromSelection,
    isCreatingDataSource,
    selectedRows,
    selectedColumns,
    showDeleteColumnsModal,
    setShowDeleteColumnsModal,
    columnsToDelete,
    setColumnsToDelete,
    confirmDeleteColumns,
    showEditColumnModal,
    setShowEditColumnModal,
    columnToEdit,
    setColumnToEdit,
    isEditingColumn,
    saveEditColumn,
  } = controller;

  return (
    <>
      {showAddRowModal && (
        <div
          className="fixed inset-0 bg-black/20 backdrop-blur-sm flex items-center justify-center p-4 z-50"
          onClick={(e) => {
            if (e.target === e.currentTarget) {
              setShowAddRowModal(false);
              setNewRowData({});
              setNewRowPriority(1);
            }
          }}
        >
          <div
            className="max-w-2xl w-full bg-theme-primary rounded-3xl shadow-2xl p-8 text-center animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
              <Plus className="w-8 h-8 text-theme-primary" />
            </div>

            <h3 className="text-2xl font-semibold text-theme-primary mb-4">{t('addNewRow')}</h3>

            <div className="space-y-4">
              <div className="flex items-center gap-4">
                <label className="text-sm font-medium text-theme-primary w-32 text-left">{t('priority')}</label>
                <Input
                  type="number"
                  value={newRowPriority}
                  onChange={(e) => setNewRowPriority(parseInt(e.target.value) || 1)}
                  className="flex-1"
                  min="1"
                />
              </div>

              {getDynamicColumns().map((col) => {
                const fieldKey = col.field.replace('data.', '');
                return (
                  <div key={col.field} className="flex items-center gap-4">
                    <label className="text-sm font-medium text-theme-primary w-32 text-left">{fieldKey}</label>
                    <Input
                      value={newRowData[fieldKey] || ''}
                      onChange={(e) => handleRowDataChange(fieldKey, e.target.value)}
                      placeholder={t('enterField', { field: fieldKey })}
                      className="flex-1"
                    />
                  </div>
                );
              })}

              {getDynamicColumns().length === 0 && (
                <div className="text-sm text-theme-secondary p-4 bg-theme-secondary rounded-md">
                  {t('noColumns')}
                </div>
              )}
            </div>

            <div className="flex gap-3 mt-6">
              <Button
                onClick={() => {
                  setShowAddRowModal(false);
                  setNewRowData({});
                  setNewRowPriority(1);
                }}
                disabled={isAddingRow}
                variant="outline"
                className="flex-1"
              >
                {t('cancel')}
              </Button>
              <Button onClick={addNewRow} disabled={isAddingRow} variant="default" className="flex-1">
                {isAddingRow ? t('adding') : t('addRow')}
              </Button>
            </div>
          </div>
        </div>
      )}

      <AddColumnModal
        isOpen={showAddColumnModal}
        isAdding={isAddingColumn}
        columnName={newColumnName}
        selectedStyle={selectedColumnStyle}
        onClose={() => setShowAddColumnModal(false)}
        onAdd={addNewColumn}
        onColumnNameChange={setNewColumnName}
        onStyleChange={setSelectedColumnStyle}
      />

      <EditColumnModal
        isOpen={showEditColumnModal}
        isSaving={isEditingColumn}
        column={columnToEdit}
        onClose={() => {
          setShowEditColumnModal(false);
          setColumnToEdit(null);
        }}
        onSave={saveEditColumn}
      />


      {showCreateDataSourceModal && (
        <div className="fixed inset-0 bg-black/20 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 text-center animate-in fade-in-0 zoom-in-95 duration-300 border border-theme">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
              <Table className="w-8 h-8 text-theme-primary" />
            </div>

            <h3 className="text-2xl font-semibold text-theme-primary mb-4">{t('createFromSelection')}</h3>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1">Name *</label>
                <Input value={newDataSourceName} onChange={(e) => setNewDataSourceName(e.target.value)} placeholder={t('enterTableName')} className="w-full" />
              </div>

              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1">Description</label>
                <Input
                  value={newDataSourceDescription}
                  onChange={(e) => setNewDataSourceDescription(e.target.value)}
                  placeholder={t('enterDescription')}
                  className="w-full"
                />
              </div>

              <div className="bg-theme-secondary p-4 rounded-lg border border-theme">
                <h4 className="text-sm font-semibold text-theme-primary mb-2">{t('selectionSummary')}</h4>
                <div className="space-y-2 text-sm text-theme-secondary">
                  {selectedRows.size > 0 && (
                    <div className="flex items-center gap-2">
                      <div className="w-2 h-2 bg-theme-primary rounded-full opacity-60"></div>
                      <span>{t('rowsSelected', { count: selectedRows.size })}</span>
                    </div>
                  )}
                  {selectedColumns.size > 0 && (
                    <div className="flex items-center gap-2">
                      <div className="w-2 h-2 bg-theme-primary rounded-full opacity-60"></div>
                      <span>{t('columnsSelected', { count: selectedColumns.size })}</span>
                    </div>
                  )}
                  {selectedRows.size === 0 && selectedColumns.size > 0 && (
                    <div className="flex items-center gap-2">
                      <div className="w-2 h-2 bg-theme-primary rounded-full opacity-60"></div>
                      <span>{t('allRowsIncluded')}</span>
                    </div>
                  )}
                  {selectedRows.size > 0 && selectedColumns.size === 0 && (
                    <div className="flex items-center gap-2">
                      <div className="w-2 h-2 bg-theme-primary rounded-full opacity-60"></div>
                      <span>{t('allColumnsIncluded')}</span>
                    </div>
                  )}
                  {selectedRows.size > 0 && selectedColumns.size > 0 && (
                    <div className="flex items-center gap-2">
                      <div className="w-2 h-2 bg-theme-primary rounded-full opacity-60"></div>
                      <span>{t('selectedRowsAndColumns')}</span>
                    </div>
                  )}
                </div>

                {selectedColumns.size > 0 && (
                  <div className="mt-3 pt-3 border-t border-theme">
                    <p className="text-xs text-theme-secondary mb-1">{t('selectedColumnsLabel')}</p>
                    <div className="flex flex-wrap gap-1">
                      {Array.from(selectedColumns).map((column) => {
                        const cleanField = column.startsWith('data.') ? column.replace('data.', '') : column;
                        return (
                          <Badge key={column} variant="secondary">
                            {cleanField}
                          </Badge>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            </div>

            <div className="flex gap-3 mt-6">
              <Button onClick={() => setShowCreateDataSourceModal(false)} disabled={isCreatingDataSource} variant="outline" className="flex-1">
                {t('cancel')}
              </Button>
              <Button
                onClick={createDataSourceFromSelection}
                disabled={!newDataSourceName.trim() || isCreatingDataSource}
                variant="default"
                className="flex-1"
              >
                {isCreatingDataSource ? t('creating') : t('createTable')}
              </Button>
            </div>
          </div>
        </div>
      )}

    </>
  );
}
