'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import type { BuilderNodeData } from '../../../types';
import { useTranslations } from 'next-intl';

interface ResponseParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
}

/**
 * Form component for Response node parameters.
 * Allows configuring the message to send in the chat conversation.
 */
export function ResponseParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
}: ResponseParametersFormProps) {
  const t = useTranslations('workflowBuilder.response');

  const responseMessage: string = (data as any).responseMessage ?? '';

  const handleMessageChange = React.useCallback((value: string) => {
    onUpdate({
      ...data,
      responseMessage: value,
    } as BuilderNodeData);
  }, [data, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      {/* Message input */}
      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('messageLabel')}</span>
            <Popover>
              <PopoverTrigger asChild>
                <button
                  type="button"
                  className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
                >
                  <Info className="h-3 w-3 text-slate-400 dark:text-slate-500" />
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                  <p className="font-semibold text-slate-900 dark:text-slate-100">{t('infoTitle')}</p>
                  <p>{t('infoDescription')}</p>
                  <ul className="list-disc list-inside space-y-1 text-xs">
                    <li>{t('infoExpressions')}</li>
                    <li>{t('infoChatOnly')}</li>
                  </ul>
                </div>
              </PopoverContent>
            </Popover>
          </div>
          <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
        </div>
        <ExpressionEditor
          value={responseMessage}
          onChange={handleMessageChange}
          placeholder={t('messagePlaceholder')}
          className="w-full"
          readOnly={isRunMode}
          isRequired={true}
        />
      </div>
    </div>
  );
}
