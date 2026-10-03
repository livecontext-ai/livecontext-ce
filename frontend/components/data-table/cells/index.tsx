'use client';

import React from 'react';
import type { ReactNode } from 'react';
import type { ColumnVisualType, ColumnDisplayConfig } from '@/types/data-sources';
import { resolveColumnType } from '@/utils/columnSpec';
import type { VisualCellProps } from './types';
import { RatingCell } from './RatingCell';
import { SentimentCell } from './SentimentCell';
import { AssetCell } from './AssetCell';
import { SelectCell } from './SelectCell';
import { ProgressCell, type ProgressCellExtraProps } from './ProgressCell';
import { MultiSelectCell } from './MultiSelectCell';
import { CheckboxCell } from './CheckboxCell';
import { DateCell } from './DateCell';
import { EmailCell } from './EmailCell';
import { PhoneCell } from './PhoneCell';
import { UrlCell } from './UrlCell';
import { NumberCell } from './NumberCell';
import { VectorCell } from './VectorCell';

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
  onProgressSave: (value: number) => void | Promise<boolean | void>;
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

    // ONE cell for both. `file` and `image` were never two data contracts, only two
    // presentations of the same stored asset, so the only difference left is display.render.
    case 'file':
    case 'image': {
      const assetProps: VisualCellProps = {
        ...props,
        displayConfig: {
          // A legacy `image` column carries no display.render - keep the round thumbnail it
          // has always had. An explicit render (set on the column) still wins.
          render: resolved === 'image' ? 'thumbnail' : 'card',
          ...(opts.displayConfig ?? {}),
        },
      };
      return { content: <AssetCell {...assetProps} />, editable: true };
    }

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

    /**
     * An embedding is not a value a person edits. The numbers live in a dedicated vector table, and
     * the items endpoint merges the whole embedding back into the row as TEXT, so `value` here is a
     * long "[-0.009573,...]" string with nothing to show a reader. Falling through to `default`
     * handed it to the generic editable-text path, which rendered a box a click could turn into a
     * text input over an embedding column. Harmless while vector columns were self-hosted-only and
     * rare; not something to hand to every cloud workspace on a paid plan. Read-only, and it says
     * what it is.
     */
    case 'vector':
      return { content: <VectorCell {...props} />, editable: false };

    default:
      return null;
  }
}
