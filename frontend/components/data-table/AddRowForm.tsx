'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Plus, X, Save } from 'lucide-react';
import type { ColumnDefinition, DataTableProps } from '@/components/data-table/types';
import type { ViewConfig } from '@/components/data-table/viewConfig';
import { isColumnVisible } from '@/components/data-table/viewConfig';
import { FIXED_COLUMN_WIDTH } from '@/components/data-table/tableStyles';
import { resolveColumnType } from '@/utils/columnSpec';
import { renderVisualCellContent } from '@/components/data-table/cells';

/** Types that should use their visual cell editor in the add-row form */
const VISUAL_ADD_ROW_TYPES = new Set([
  'checkbox', 'rating', 'sentiment', 'progress', 'select', 'multi_select',
  'date', 'number', 'email', 'phone', 'url', 'file', 'image',
]);

interface AddRowFormProps {
  columns: ColumnDefinition[];
  getUniqueColumns: () => ColumnDefinition[];
  getDynamicColumns: () => ColumnDefinition[];
  getFieldPath: (field: string) => string;
  viewConfig: ViewConfig;
  checkboxColumnWidth: string;
  isAddingRowInline: boolean;
  isAddingRow: boolean;
  newRowPriority: number;
  newRowData: Record<string, any>;
  onStartAddingRow: () => void;
  onCancelAddingRow: () => void;
  onAddRow: () => void;
  onPriorityChange: (priority: number) => void;
  onRowDataChange: (field: string, value: string) => void;
}

export function AddRowForm({
  columns,
  getUniqueColumns,
  getDynamicColumns,
  getFieldPath,
  viewConfig,
  checkboxColumnWidth,
  isAddingRowInline,
  isAddingRow,
  newRowPriority,
  newRowData,
  onStartAddingRow,
  onCancelAddingRow,
  onAddRow,
  onPriorityChange,
  onRowDataChange,
}: AddRowFormProps) {
  const t = useTranslations('dataTable');
  // Dummy progress state (not used for add-row but required by renderVisualCellContent)
  const noop = () => {};

  return (
    <tr className={`sticky bottom-0 z-30 min-h-[62px] h-[62px] ${isAddingRowInline ? 'bg-theme-primary border border-theme hover-row-item' : ''}`}>
      {getUniqueColumns()
        .filter((col) => isColumnVisible(col.field, viewConfig))
        .map((col) => {
          // Colonne ID
          if (col.field === 'id') {
            const hasCheckbox = getUniqueColumns().some((c) => c.field === 'checkbox');
            const idLeftOffset = hasCheckbox ? checkboxColumnWidth : 0;
            return (
              <td
                key={`add-row-${col.field}`}
                className="px-2 py-2 text-center sticky bg-theme-primary"
                style={{
                  left: idLeftOffset,
                  zIndex: 35,
                  minWidth: '100px',
                  maxWidth: '150px',
                }}
              >
                {/* Vide pour la ligne d'ajout */}
              </td>
            );
          }

          const fixedColumnStyle =
            col.field === 'checkbox'
              ? {
                  width: checkboxColumnWidth,
                  minWidth: checkboxColumnWidth,
                  maxWidth: checkboxColumnWidth,
                }
              : {
                  minWidth: '150px',
                };

          // Colonne checkbox avec boutons
          if (col.field === 'checkbox') {
            return (
              <td
                key={`add-row-${col.field}`}
                className="px-1 py-2 text-center sticky left-0 z-30 bg-theme-primary"
                style={fixedColumnStyle}
              >
                {isAddingRowInline ? (
                  <div className="flex items-center justify-center gap-1">
                    <Button
                      variant="ghost"
                      onClick={onCancelAddingRow}
                      disabled={isAddingRow}
                      className="w-5 h-5 p-0 rounded-full"
                      title={t('cancel')}
                    >
                      <X className="w-4 h-4" />
                    </Button>
                    <Button
                      variant="ghost"
                      onClick={onAddRow}
                      disabled={isAddingRow}
                      className="w-5 h-5 p-0 rounded-full"
                      title={t('saveRow')}
                    >
                      <Save className="w-4 h-4" />
                    </Button>
                  </div>
                ) : (
                  <Button
                    variant="default"
                    onClick={onStartAddingRow}
                    className="w-5 h-5 p-0 rounded-full"
                    title={t('addRow')}
                  >
                    <Plus className="w-4 h-4" />
                  </Button>
                )}
              </td>
            );
          }

          // Colonne priority
          if (col.field === 'priority') {
            return (
              <td
                key={`add-row-${col.field}`}
                className={`px-3 py-2 ${isAddingRowInline ? 'bg-theme-primary' : 'bg-transparent'}`}
                style={fixedColumnStyle}
              >
                {isAddingRowInline ? (
                  <Input
                    type="number"
                    value={newRowPriority}
                    onChange={(e) => onPriorityChange(parseInt(e.target.value) || 1)}
                    className="w-full h-10 text-sm"
                    min="1"
                    placeholder={t('priority')}
                  />
                ) : null}
              </td>
            );
          }

          // Colonnes dynamiques
          if (isAddingRowInline) {
            const dynamicCols = getDynamicColumns();
            const isDynamicCol = dynamicCols.some((dc) => dc.field === col.field);
            if (isDynamicCol) {
              const fieldKey = getFieldPath(col.field);
              const resolved = resolveColumnType(col.type);

              // Vector columns are not manually editable - show empty cell
              if (resolved === 'vector') {
                return (
                  <td
                    key={`add-row-${col.field}`}
                    className="px-3 py-2 text-center bg-theme-primary"
                    style={fixedColumnStyle}
                  />
                );
              }

              // Use visual cell component for typed columns
              if (VISUAL_ADD_ROW_TYPES.has(resolved)) {
                const cellKey = `add-row-${col.field}`;
                const currentValue = newRowData[fieldKey] ?? null;
                const result = renderVisualCellContent({
                  value: currentValue,
                  rowKey: 'add-row',
                  field: col.field,
                  type: col.type,
                  displayConfig: col.displayConfig,
                  isEditing: true,
                  onSaveAndExit: (val: any) => onRowDataChange(fieldKey, String(val)),
                  onStartEditing: noop,
                  onExitEditing: noop,
                  cellKey,
                  onProgressTempChange: noop,
                  onProgressSave: (val: number) => onRowDataChange(fieldKey, String(val)),
                });

                if (result) {
                  return (
                    <td
                      key={`add-row-${col.field}`}
                      className="px-3 py-2 bg-theme-primary align-middle text-center"
                      style={fixedColumnStyle}
                    >
                      <div className="flex w-full items-center justify-center">
                        {result.content}
                      </div>
                    </td>
                  );
                }
              }

              // Fallback: textarea for text and other types
              return (
                <td
                  key={`add-row-${col.field}`}
                  className="px-3 py-2 bg-theme-primary align-top"
                  style={fixedColumnStyle}
                >
                  <Textarea
                    value={newRowData[fieldKey] || ''}
                    onChange={(e) => onRowDataChange(fieldKey, e.target.value)}
                    placeholder={t('enterField', { field: fieldKey })}
                    className="w-full min-h-[40px] text-sm resize-none overflow-hidden"
                    rows={1}
                    onInput={(e) => {
                      const target = e.target as HTMLTextAreaElement;
                      target.style.height = 'auto';
                      target.style.height = `${target.scrollHeight}px`;
                    }}
                  />
                </td>
              );
            }
          }

          // Autres colonnes
          return (
            <td
              key={`add-row-${col.field}`}
              className={`px-3 py-2 text-center ${isAddingRowInline ? 'bg-theme-primary' : 'bg-transparent'}`}
              style={fixedColumnStyle}
            />
          );
        })}
      {/* Cellule correspondante à la colonne "Add Column" */}
      <td
        className="px-1 py-2 text-center sticky right-0 z-30"
        style={{
          width: FIXED_COLUMN_WIDTH,
          minWidth: FIXED_COLUMN_WIDTH,
          maxWidth: FIXED_COLUMN_WIDTH,
          backgroundColor: 'transparent'
        }}
      >
      </td>
    </tr>
  );
}
