'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Input } from '@/components/ui/input';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';
import { InfoPopover } from '@/components/ui/info-popover';

interface RssParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Form component for RSS node parameters.
 * Configures the RSS/Atom feed URL and maximum number of items to return.
 */
export function RssParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: RssParametersFormProps) {
  const t = useTranslations('workflowBuilder.rssNode');

  const url: string = (data as any).rssUrl ?? '';
  const maxItems: number = (data as any).rssMaxItems ?? 10;

  const handleUrlChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      rssUrl: value,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const handleMaxItemsChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    const value = event.target.value;
    if (value === '') {
      onUpdate({
        ...data,
        rssMaxItems: 1,
      } as BuilderNodeData);
      return;
    }

    const numValue = parseInt(value, 10);
    if (isNaN(numValue) || numValue < 1) {
      return;
    }

    onUpdate({
      ...data,
      rssMaxItems: numValue,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  return (
    <div className="space-y-4 pt-2">
      {/* Info header */}
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('title')}</span>
        <InfoPopover label={t('title')} size="sm" side="right" align="start">
          <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
            <p className="font-semibold text-slate-900 dark:text-slate-100">{t('infoTitle')}</p>
            <p>{t('infoDescription')}</p>
          </div>
        </InfoPopover>
      </div>

      {/* Feed URL */}
      <div className="space-y-1">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('url')} <span className="text-red-500">*</span></span>
          <span className="text-sm text-slate-500 dark:text-slate-400">Required</span>
        </div>
        <ExpressionEditor
          value={url}
          onChange={handleUrlChange}
          placeholder={t('urlPlaceholder')}
          className="w-full"
          unknownVariables={findUnknownVariables({ rssUrl: url })}
          handleId={`rss-url-${node.id}`}
          connections={connectionProps.connections}
          onHandleClick={connectionProps.handleHandleClick}
          draggingFromHandle={connectionProps.draggingFromHandle}
          onHandleMouseDown={connectionProps.handleHandleMouseDown}
          onHandleMouseUp={connectionProps.handleHandleMouseUp}
          hoveredTargetHandle={connectionProps.hoveredTargetHandle}
          onSetHandleRef={connectionProps.handleSetHandleRef}
          readOnly={isRunMode}
          isRequired={true}
        />
      </div>

      {/* Max Items */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('maxItems')}</span>
        <Input
          type="number"
          min="1"
          value={maxItems}
          onChange={handleMaxItemsChange}
          className="w-full"
          placeholder={t('maxItemsPlaceholder')}
          readOnly={isRunMode}
        />
        <p className="text-sm text-slate-400 dark:text-slate-500">
          {t('maxItemsHelp')}
        </p>
      </div>
    </div>
  );
}
