'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Textarea } from '@/components/ui/textarea';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../types';
import { NOTE_COLORS } from '../../nodes/NoteNode';

interface NoteParametersFormProps {
  data: BuilderNodeData;
  onUpdate: (data: BuilderNodeData) => void;
  isRunMode?: boolean;
}

export function NoteParametersForm({
  data,
  onUpdate,
  isRunMode = false,
}: NoteParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  return (
    <div className="space-y-3 pt-2">
      <label className="space-y-2">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('note.text')}</span>
        <Textarea
          className="w-full resize-none"
          value={data.noteText || ''}
          onChange={(event) => onUpdate({ ...data, noteText: event.target.value })}
          rows={4}
          placeholder={t('note.placeholder')}
          readOnly={isRunMode}
        />
      </label>

      <div className="space-y-2">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('note.color')}</span>
        <div className="grid grid-cols-3 gap-2">
          {NOTE_COLORS.map((color) => (
            <button
              key={color.name}
              type="button"
              onClick={() => {
                onUpdate({
                  ...data,
                  noteColor: color.value,
                  noteBorderColor: color.border,
                  noteTextColor: color.text,
                });
              }}
              className={clsx(
                'h-10 rounded-lg border-2 transition-all',
                data.noteColor === color.value
                  ? 'border-slate-900 scale-105'
                  : 'border-slate-200 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-600',
                isRunMode && 'cursor-not-allowed opacity-50'
              )}
              style={{ backgroundColor: color.value }}
              title={color.name}
              disabled={isRunMode}
            />
          ))}
        </div>
      </div>
    </div>
  );
}
