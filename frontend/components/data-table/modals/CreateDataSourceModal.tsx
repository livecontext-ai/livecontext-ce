'use client';

import React from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Table } from 'lucide-react';

interface CreateDataSourceModalProps {
  isOpen: boolean;
  isCreating: boolean;
  name: string;
  description: string;
  selectedRows: Set<string>;
  selectedColumns: Set<string>;
  onClose: () => void;
  onCreate: () => void;
  onNameChange: (name: string) => void;
  onDescriptionChange: (description: string) => void;
}

export function CreateDataSourceModal({
  isOpen,
  isCreating,
  name,
  description,
  selectedRows,
  selectedColumns,
  onClose,
  onCreate,
  onNameChange,
  onDescriptionChange,
}: CreateDataSourceModalProps) {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black/20 backdrop-blur-sm flex items-center justify-center p-4 z-[9999]">
      <div className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 text-center animate-in fade-in-0 zoom-in-95 duration-300 border border-theme">
        {/* Icon */}
        <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
          <Table className="w-8 h-8 text-theme-primary" />
        </div>

        <h3 className="text-2xl font-semibold text-theme-primary mb-4">Create Table from Selection</h3>

        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-1">Name *</label>
            <Input
              value={name}
              onChange={(e) => onNameChange(e.target.value)}
              placeholder="Enter table name"
              className="w-full"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-theme-primary mb-1">Description</label>
            <Input
              value={description}
              onChange={(e) => onDescriptionChange(e.target.value)}
              placeholder="Enter description (optional)"
              className="w-full"
            />
          </div>

          {/* Selection information */}
          <div className="bg-theme-secondary p-4 rounded-lg border border-theme">
            <h4 className="text-sm font-semibold text-theme-primary mb-2">Selection Summary</h4>
            <div className="space-y-2 text-sm text-theme-secondary">
              {selectedRows.size > 0 && (
                <div className="flex items-center gap-2">
                  <div className="w-2 h-2 bg-theme-primary rounded-full opacity-60"></div>
                  <span>
                    <strong>{selectedRows.size}</strong> row{selectedRows.size > 1 ? 's' : ''} selected
                  </span>
                </div>
              )}
              {selectedColumns.size > 0 && (
                <div className="flex items-center gap-2">
                  <div className="w-2 h-2 bg-theme-primary rounded-full opacity-60"></div>
                  <span>
                    <strong>{selectedColumns.size}</strong> column{selectedColumns.size > 1 ? 's' : ''} selected
                  </span>
                </div>
              )}
              {selectedRows.size === 0 && selectedColumns.size > 0 && (
                <div className="flex items-center gap-2">
                  <div className="w-2 h-2 bg-theme-primary rounded-full opacity-60"></div>
                  <span>All rows will be included with selected columns only</span>
                </div>
              )}
              {selectedRows.size > 0 && selectedColumns.size === 0 && (
                <div className="flex items-center gap-2">
                  <div className="w-2 h-2 bg-theme-primary rounded-full opacity-60"></div>
                  <span>All columns will be included for selected rows</span>
                </div>
              )}
              {selectedRows.size > 0 && selectedColumns.size > 0 && (
                <div className="flex items-center gap-2">
                  <div className="w-2 h-2 bg-theme-primary rounded-full opacity-60"></div>
                  <span>Selected rows with selected columns only</span>
                </div>
              )}
            </div>

            {/* Selected columns details */}
            {selectedColumns.size > 0 && (
              <div className="mt-3 pt-3 border-t border-theme">
                <p className="text-sm text-theme-secondary mb-1">Selected columns:</p>
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
          <Button onClick={onClose} disabled={isCreating} variant="outline" className="flex-1">
            Cancel
          </Button>
          <Button onClick={onCreate} disabled={!name.trim() || isCreating} variant="default" className="flex-1">
            {isCreating ? 'Creating...' : 'Create Table'}
          </Button>
        </div>
      </div>
    </div>
  );
}
