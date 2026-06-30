'use client';

import React from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Plus } from 'lucide-react';
import type { ColumnDefinition } from '@/components/data-table/types';

interface AddRowModalProps {
  isOpen: boolean;
  isAdding: boolean;
  newRowPriority: number;
  newRowData: Record<string, any>;
  dynamicColumns: ColumnDefinition[];
  onClose: () => void;
  onAdd: () => void;
  onPriorityChange: (priority: number) => void;
  onRowDataChange: (field: string, value: string) => void;
}

export function AddRowModal({
  isOpen,
  isAdding,
  newRowPriority,
  newRowData,
  dynamicColumns,
  onClose,
  onAdd,
  onPriorityChange,
  onRowDataChange,
}: AddRowModalProps) {
  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm flex items-center justify-center p-4 z-[9999]"
      onClick={(e) => {
        if (e.target === e.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        className="max-w-2xl w-full bg-theme-primary rounded-3xl shadow-2xl p-8 text-center animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Icon */}
        <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
          <Plus className="w-8 h-8 text-theme-primary" />
        </div>

        <h3 className="text-2xl font-semibold text-theme-primary mb-4">Add New Row</h3>

        <div className="space-y-4">
          {/* Priority */}
          <div className="flex items-center gap-4">
            <label className="text-sm font-medium text-theme-primary w-32 text-left">Priority</label>
            <Input
              type="number"
              value={newRowPriority}
              onChange={(e) => onPriorityChange(parseInt(e.target.value) || 1)}
              className="flex-1"
              min="1"
            />
          </div>

          {/* Dynamic columns */}
          {dynamicColumns.map((col) => {
            const fieldKey = col.field.replace('data.', '');
            return (
              <div key={col.field} className="flex items-center gap-4">
                <label className="text-sm font-medium text-theme-primary w-32 text-left">{fieldKey}</label>
                <Input
                  value={newRowData[fieldKey] || ''}
                  onChange={(e) => onRowDataChange(fieldKey, e.target.value)}
                  placeholder={`Enter ${fieldKey}`}
                  className="flex-1"
                />
              </div>
            );
          })}

          {dynamicColumns.length === 0 && (
            <div className="text-sm text-theme-secondary p-4 bg-theme-secondary rounded-md">
              No dynamic columns available. Add a column first to create data fields.
            </div>
          )}
        </div>

        <div className="flex gap-3 mt-6">
          <Button onClick={onClose} disabled={isAdding} variant="outline" className="flex-1">
            Cancel
          </Button>
          <Button onClick={onAdd} disabled={isAdding} variant="default" className="flex-1">
            {isAdding ? 'Adding...' : 'Add Row'}
          </Button>
        </div>
      </div>
    </div>
  );
}
