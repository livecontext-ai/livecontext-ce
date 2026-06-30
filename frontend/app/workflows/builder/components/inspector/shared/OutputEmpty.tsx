/**
 * Empty state for output display.
 */

'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { InboxIcon } from 'lucide-react';

interface OutputEmptyProps {
  message?: string;
}

export function OutputEmpty({ message }: OutputEmptyProps) {
  const t = useTranslations('workflow.inspector');

  return (
    <div className="flex flex-col items-center justify-center py-8 px-4 text-center">
      <InboxIcon className="h-10 w-10 text-slate-300 mb-3" />
      <p className="text-sm text-slate-500">
        {message || t('output.empty', { defaultValue: 'No output data available' })}
      </p>
      <p className="text-xs text-slate-400 mt-1">
        {t('output.emptyHint', { defaultValue: 'Run the workflow to see results' })}
      </p>
    </div>
  );
}

export default OutputEmpty;
