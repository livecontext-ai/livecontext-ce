'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import type { BuilderNodeData } from '../../../types';

interface StopOnErrorParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
}

export function StopOnErrorParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
}: StopOnErrorParametersFormProps) {
  const t = useTranslations('workflowBuilder.stopOnErrorNode');

  const errorMessage: string = (data as any).stopOnErrorMessage ?? '';
  const errorCode: string = (data as any).stopOnErrorCode ?? '';

  const handleChange = React.useCallback(
    (field: string, value: string) => {
      if (isRunMode) return;
      onUpdate({ ...data, [field]: value } as BuilderNodeData);
    },
    [data, isRunMode, onUpdate],
  );

  return (
    <div className="space-y-4 pt-2">
      {/* Info header */}
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('title')}
        </span>
        <Popover>
          <PopoverTrigger asChild>
            <button className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-300">
              <Info className="h-3.5 w-3.5" />
            </button>
          </PopoverTrigger>
          <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
            <p className="font-semibold mb-1">{t('infoTitle')}</p>
            <p className="text-slate-500 dark:text-slate-400 text-xs">
              {t('infoDescription')}
            </p>
          </PopoverContent>
        </Popover>
      </div>

      {/* Error Message */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('errorMessage')} <span className="text-red-500">*</span>
        </span>
        <Input
          value={errorMessage}
          onChange={(e) => handleChange('stopOnErrorMessage', e.target.value)}
          placeholder={t('errorMessagePlaceholder')}
          disabled={isRunMode}
          className="text-sm"
        />
      </div>

      {/* Error Code */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('errorCode')}
        </span>
        <span className="text-xs text-slate-400 dark:text-slate-500 ml-1">({t('optional')})</span>
        <Input
          value={errorCode}
          onChange={(e) => handleChange('stopOnErrorCode', e.target.value)}
          placeholder={t('errorCodePlaceholder')}
          disabled={isRunMode}
          className="text-sm"
        />
      </div>
    </div>
  );
}
