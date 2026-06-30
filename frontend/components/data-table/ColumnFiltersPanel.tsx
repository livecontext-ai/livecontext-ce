import { useEffect, useRef } from 'react';
import type { DataTableController } from '@/components/data-table/useDataTableController';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

interface ColumnFiltersPanelProps {
  controller: DataTableController;
}

export function ColumnFiltersPanel({ controller }: ColumnFiltersPanelProps) {
  const { showColumnFilters, columns, columnFilters, setColumnFilters } = controller;
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (showColumnFilters && panelRef.current) {
      const firstInput = panelRef.current.querySelector('input');
      firstInput?.focus();
    }
  }, [showColumnFilters]);

  if (!showColumnFilters) {
    return null;
  }

  return (
    <div ref={panelRef} className="bg-theme-secondary p-4 rounded-lg mb-4">
      <h4 className="text-sm font-medium text-theme-primary mb-3">Filter by columns:</h4>
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
        {columns
          .filter((col) => col.field.startsWith('data.'))
          .map((col) => (
            <div key={col.field}>
              <label className="block text-xs font-medium text-theme-secondary mb-1">{col.header_name}</label>
              <Input
                placeholder={`Filter ${col.header_name}...`}
                value={columnFilters[col.field] || ''}
                onChange={(e) =>
                  setColumnFilters((prev) => ({
                    ...prev,
                    [col.field]: e.target.value,
                  }))
                }
                className="text-sm bg-theme-primary text-theme-primary border-theme"
              />
            </div>
          ))}
      </div>
      <div className="flex justify-end mt-3">
        <Button variant="outline" size="sm" onClick={() => setColumnFilters({})}>
          Clear Filters
        </Button>
      </div>
    </div>
  );
}
