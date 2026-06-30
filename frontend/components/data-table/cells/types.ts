import type { ReactNode } from 'react';
import type { ColumnDisplayConfig } from '@/types/data-sources';

export interface VisualCellProps {
  value: any;
  rowKey: string;
  field: string;
  displayConfig?: ColumnDisplayConfig;
  isEditing: boolean;
  onSaveAndExit: (value: any) => void;
  onStartEditing: (event?: React.MouseEvent) => void;
  onExitEditing: () => void;
  readOnly?: boolean;
}

export interface CellShellProps {
  rowKey: string;
  field: string;
  className: string;
  isEditing: boolean;
  onMouseEnter: () => void;
  onStartEditing: (event?: React.MouseEvent) => void;
  children: ReactNode;
  editable?: boolean;
}
