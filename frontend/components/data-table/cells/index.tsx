'use client';

import React from 'react';
import type { ReactNode } from 'react';
import type { ColumnVisualType, ColumnDisplayConfig } from '@/types/data-sources';
import { resolveColumnType } from '@/utils/columnSpec';
import type { VisualCellProps } from './types';
import { RatingCell } from './RatingCell';
import { SentimentCell } from './SentimentCell';
import { FileCell } from './FileCell';
import { ImageCell } from './ImageCell';
import { SelectCell } from './SelectCell';
import { ProgressCell, type ProgressCellExtraProps } from './ProgressCell';
import { MultiSelectCell } from './MultiSelectCell';
import { CheckboxCell } from './CheckboxCell';
import { DateCell } from './DateCell';
import { EmailCell } from './EmailCell';
import { PhoneCell } from './PhoneCell';
import { UrlCell } from './UrlCell';
import { NumberCell } from './NumberCell';

export type { VisualCellProps } from './types';

export interface RenderVisualCellOptions {
  value: any;
  rowKey: string;
  field: string;
  type: ColumnVisualType | string;
  displayConfig?: ColumnDisplayConfig;
  isEditing: boolean;
  onSaveAndExit: (value: any) => void;
  onStartEditing: (event?: React.MouseEvent) => void;
  onExitEditing: () => void;
  readOnly?: boolean;
  // Progress-specific
  cellKey: string;
  progressTempValue?: number;
  onProgressTempChange: (cellKey: string, value: number) => void;
  onProgressSave: (value: number) => void;
}

/**
 * Central dispatcher for visual cell rendering.
 * Resolves aliases, then dispatches to the correct cell component.
 * Returns the rendered content (to be wrapped in a <td> shell by the caller), or null for default text.
 */
export function renderVisualCellContent(opts: RenderVisualCellOptions): { content: ReactNode; editable: boolean } | null {
  const resolved = resolveColumnType(opts.type);

  const props: VisualCellProps = {
    value: opts.value,
    rowKey: opts.rowKey,
    field: opts.field,
    displayConfig: opts.displayConfig,
    isEditing: opts.isEditing,
    onSaveAndExit: opts.onSaveAndExit,
    onStartEditing: opts.onStartEditing,
    onExitEditing: opts.onExitEditing,
    readOnly: opts.readOnly,
  };

  switch (resolved) {
    case 'rating':
      return { content: <RatingCell {...props} />, editable: false };

    case 'sentiment':
      return { content: <SentimentCell {...props} />, editable: false };

    case 'file':
      return { content: <FileCell {...props} />, editable: true };

    case 'image':
      return { content: <ImageCell {...props} />, editable: true };

    case 'select':
      return { content: <SelectCell {...props} />, editable: false };

    case 'multi_select':
      return { content: <MultiSelectCell {...props} />, editable: false };

    case 'progress': {
      const progressExtra: ProgressCellExtraProps = {
        cellKey: opts.cellKey,
        tempValue: opts.progressTempValue,
        onTempChange: opts.onProgressTempChange,
        onProgressSave: opts.onProgressSave,
      };
      return { content: <ProgressCell {...props} {...progressExtra} />, editable: false };
    }

    case 'checkbox':
      return { content: <CheckboxCell {...props} />, editable: false };

    case 'date':
      return { content: <DateCell {...props} />, editable: true };

    case 'email':
      return { content: <EmailCell {...props} />, editable: true };

    case 'phone':
      return { content: <PhoneCell {...props} />, editable: true };

    case 'url':
      return { content: <UrlCell {...props} />, editable: true };

    case 'number':
      return { content: <NumberCell {...props} />, editable: true };

    default:
      return null;
  }
}
