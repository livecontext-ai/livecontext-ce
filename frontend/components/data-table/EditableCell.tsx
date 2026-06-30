'use client';

import { useState, useRef, useCallback } from 'react';
import type { KeyboardEvent } from 'react';
import { useTranslations } from 'next-intl';
import type { ColumnVisualType } from '@/types/data-sources';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';

export interface EditableCellProps {
  value: any;
  onSave: (value: string) => void;
  className?: string;
  onMouseEnter?: () => void;
  columnType?: ColumnVisualType;
  readOnly?: boolean;
}

export function EditableCell({ value, onSave, className, onMouseEnter, columnType, readOnly }: EditableCellProps) {
  const t = useTranslations('dataTable');
  const [isEditing, setIsEditing] = useState(false);
  const [editingValue, setEditingValue] = useState('');
  const originalValueRef = useRef('');
  const isNumberType = columnType === 'number' || (columnType === undefined && typeof value === 'number');

  const handleClick = () => {
    if (readOnly) return;
    setIsEditing(true);
    if (value === null || value === undefined) {
      setEditingValue('');
      originalValueRef.current = '';
    } else if (typeof value === 'object') {
      const jsonStr = JSON.stringify(value);
      setEditingValue(JSON.stringify(value, null, 2));
      originalValueRef.current = jsonStr;
    } else {
      const strValue = String(value);
      setEditingValue(strValue);
      originalValueRef.current = strValue;
    }
  };

  const handleSave = () => {
    let newValueStr = '';

    try {
      if (isNumberType) {
        if (editingValue.trim() === '') {
          newValueStr = '';
        } else {
          const numValue = Number(editingValue);
          if (!isNaN(numValue)) {
            newValueStr = String(numValue);
          } else {
            newValueStr = editingValue.trim();
          }
        }
      } else if (typeof value === 'object' && value !== null) {
        const parsed = JSON.parse(editingValue);
        newValueStr = JSON.stringify(parsed);
      } else {
        newValueStr = editingValue.trim();
      }
    } catch (error) {
      newValueStr = editingValue.trim();
    }

    if (newValueStr !== originalValueRef.current) {
      if (isNumberType) {
        const numValue = editingValue.trim() === '' ? '' : Number(editingValue);
        onSave(editingValue.trim() === '' ? '' : String(numValue));
      } else if (typeof value === 'object' && value !== null) {
        try {
          const parsed = JSON.parse(editingValue);
          onSave(JSON.stringify(parsed));
        } catch {
          onSave(editingValue);
        }
      } else {
        onSave(newValueStr);
      }
    }

    setIsEditing(false);
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement | HTMLInputElement>) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      handleSave();
    } else if (e.key === 'Escape') {
      setIsEditing(false);
    }
  };

  const formatValue = (val: any) => {
    if (val === null || val === undefined) {
      return <span className="text-xs text-theme-secondary">{t('noData')}</span>;
    }

    if (typeof val === 'object') {
      // `break-words` (not `break-all`): only break inside a token when the token
      // itself can't fit. With `break-all`, JSON-stringified arrays of strings
      // containing internal whitespace (e.g. RFC 2822 dates "Mon, 11 May 2026…")
      // were getting wrapped mid-word, making each entry unreadable.
      return (
        <pre className="max-h-40 overflow-auto whitespace-pre-wrap break-words rounded bg-theme-secondary/30 p-2 text-xs text-theme-primary">
          {JSON.stringify(val, null, 2)}
        </pre>
      );
    }

    if (typeof val === 'boolean') {
      return <span className="text-theme-muted italic">{val ? t('true') : t('false')}</span>;
    }

    if (typeof val === 'number') {
      return <span className="font-mono text-sm text-theme-primary min-w-0 truncate">{val}</span>;
    }

    if (typeof val === 'string' && val.length > 60) {
      return (
        <span className="text-sm text-theme-primary min-w-0 truncate">
          {val}
        </span>
      );
    }

    if (typeof val === 'string' && val.length === 0) {
      return <span className="text-xs text-theme-secondary">{t('noData')}</span>;
    }

    if (typeof val === 'boolean' && val === false) {
      return <span className="text-xs text-theme-secondary">{t('noData')}</span>;
    }

    return <span className="text-sm text-theme-primary min-w-0 truncate" title={String(val)}>{String(val)}</span>;
  };

  const widthMatch = className?.match(/w-\[(\d+px)\]/);
  const widthStyle = widthMatch ? { width: widthMatch[1] } : {};

  // Auto-size textarea on mount
  const textareaRef = useCallback((node: HTMLTextAreaElement | null) => {
    if (node) {
      node.style.height = 'auto';
      const newHeight = Math.min(node.scrollHeight, 200);
      node.style.height = `${Math.max(newHeight, 80)}px`;
      // Place cursor at end
      node.selectionStart = node.value.length;
      node.selectionEnd = node.value.length;
    }
  }, []);

  return (
    <td className={`px-3 py-2 ${className || ''} ${isEditing ? 'relative' : ''}`} style={widthStyle} onMouseEnter={onMouseEnter}>
      {isEditing ? (
        isNumberType ? (
          <input
            type="number"
            value={editingValue}
            onChange={(e) => setEditingValue(e.target.value)}
            className="w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 text-sm leading-[20px] h-[46px] text-[var(--text-primary)] placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-2"
            onKeyDown={handleKeyDown}
            onBlur={handleSave}
            autoFocus
            placeholder={t('enterNumber')}
          />
        ) : (
          <textarea
            ref={textareaRef}
            value={editingValue}
            onChange={(e) => setEditingValue(e.target.value)}
            className="absolute left-0 top-0 w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-[13px] text-sm leading-[20px] text-[var(--text-primary)] placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-2 resize-none overflow-y-auto z-50 shadow-lg"
            style={{ minHeight: '80px', maxHeight: '200px' }}
            onKeyDown={handleKeyDown}
            onBlur={handleSave}
            autoFocus
            placeholder={t('enterValue')}
            onInput={(e) => {
              const target = e.target as HTMLTextAreaElement;
              target.style.height = 'auto';
              const newHeight = Math.min(target.scrollHeight, 200);
              target.style.height = `${Math.max(newHeight, 80)}px`;
            }}
          />
        )
      ) : (() => {
        const cellInner = (
          <div
            className={`w-full min-w-0 max-w-[320px] overflow-hidden rounded-xl px-4 h-[46px] border-2 border-transparent transition-all duration-200 flex items-center justify-center ${readOnly ? '' : 'cursor-text hover:border-[var(--accent-primary)] hover:bg-[var(--bg-secondary)]'}`}
            onClick={handleClick}
          >
            {formatValue(value)}
          </div>
        );
        const showTooltip = value != null && String(value).length > 30;
        if (!showTooltip) return cellInner;
        // Portal-based tooltip with collision detection - auto-flips above the cell
        // when there's not enough space below (rows near the bottom of the table).
        return (
          <TooltipProvider delayDuration={300}>
            <Tooltip>
              <TooltipTrigger asChild>{cellInner}</TooltipTrigger>
              <TooltipContent
                side="bottom"
                align="start"
                collisionPadding={8}
                avoidCollisions
                className="max-w-[400px] min-w-[200px] whitespace-normal break-words bg-theme-primary border-theme text-theme-primary shadow-lg"
              >
                {typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value)}
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        );
      })()}
    </td>
  );
}
