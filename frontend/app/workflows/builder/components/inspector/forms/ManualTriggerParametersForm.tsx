'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../types';
import { InfoPopover } from '@/components/ui/info-popover';

const ACTION_TYPES = [
  { value: 'click', label: 'Click', description: 'Manual click to trigger the workflow' },
];

interface ManualTriggerData {
  actionType: string;
}

interface ManualTriggerParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  /** Trigger ID for multi-DAG support (e.g., "trigger:my_manual") */
  triggerId?: string | null;
}

export function ManualTriggerParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  triggerId,
}: ManualTriggerParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  const manualTriggerData: ManualTriggerData = React.useMemo(() => {
    const existing = (data as any).manualTriggerData as ManualTriggerData | undefined;
    return existing || {
      actionType: 'click',
    };
  }, [(data as any).manualTriggerData]);

  const handleActionTypeChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    onUpdate({
      ...data,
      manualTriggerData: {
        ...manualTriggerData,
        actionType: value,
      },
    } as BuilderNodeData);
  }, [data, manualTriggerData, isRunMode, onUpdate]);


  return (
    <div className="space-y-4 pt-2">
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('manual.configuration')}</span>
        <InfoPopover label={t('manual.configuration')} size="sm" side="bottom" align="end" contentClassName="w-[288px] p-3">
          <p className="mb-2 font-medium text-sm text-slate-700 dark:text-slate-200">{t('manual.title')}</p>
          <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed mb-2">
            {t('manual.description')}
          </p>
          <div className="border-t border-slate-200 dark:border-slate-700 pt-2">
            <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">{t('manual.availableOutputs')}</p>
            <ul className="text-xs text-slate-500 dark:text-slate-400 space-y-0.5 font-mono">
              <li>• triggered_at</li>
              <li>• triggered_by</li>
            </ul>
          </div>
        </InfoPopover>
      </div>

      {/* Action Type */}
      <div className="space-y-2">
        <div className="flex items-center gap-1.5">
          <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('manual.action')}</label>
          <InfoPopover label={t('manual.action')} size="sm" side="bottom" align="end">{ACTION_TYPES.find(a => a.value === manualTriggerData.actionType)?.description || ''}</InfoPopover>
        </div>
        <Select
          value={manualTriggerData.actionType}
          onValueChange={handleActionTypeChange}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full">
            <SelectValue placeholder={t('manual.selectAction')} />
          </SelectTrigger>
          <SelectContent>
            {ACTION_TYPES.map((action) => (
              <SelectItem key={action.value} value={action.value} description={action.description}>
                {action.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    </div>
  );
}
