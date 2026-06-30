'use client';

import * as React from 'react';
import * as ReactDOM from 'react-dom';
import { Info, X } from 'lucide-react';
import type { Node } from 'reactflow';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../../types';
import { FieldInfoTooltip } from './shared/FieldInfoTooltip';
import { usePopoverPosition } from '../../../hooks/ui/usePopoverPosition';

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

  const [isInfoOpen, setIsInfoOpen] = React.useState(false);
  const { buttonRef: infoButtonRef, popoverPosition } = usePopoverPosition(isInfoOpen, 280);

  return (
    <div className="space-y-4 pt-2">
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('manual.configuration')}</span>
        <div className="relative inline-flex">
          <button
            ref={infoButtonRef}
            onClick={(e) => {
              e.stopPropagation();
              setIsInfoOpen(!isInfoOpen);
            }}
            className="p-0.5 rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
            title={t('manual.moreInfo')}
          >
            <Info className="h-3 w-3 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300" />
          </button>
          {isInfoOpen && typeof document !== 'undefined' && ReactDOM.createPortal(
            <>
              <div
                className="fixed inset-0 z-[9998]"
                onClick={() => setIsInfoOpen(false)}
              />
              <div
                className="fixed z-[9999] w-72 p-3 bg-white dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700 shadow-lg"
                style={{ top: popoverPosition.top, left: popoverPosition.left }}
              >
                <div className="flex items-start justify-between gap-2 mb-2">
                  <span className="font-medium text-sm text-slate-700 dark:text-slate-200">
                    {t('manual.title')}
                  </span>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setIsInfoOpen(false);
                    }}
                    className="p-0.5 rounded hover:bg-slate-100 dark:hover:bg-slate-700"
                  >
                    <X className="h-3.5 w-3.5 text-slate-400" />
                  </button>
                </div>
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
              </div>
            </>,
            document.body
          )}
        </div>
      </div>

      {/* Action Type */}
      <div className="space-y-2">
        <div className="flex items-center gap-1.5">
          <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('manual.action')}</label>
          <FieldInfoTooltip description={ACTION_TYPES.find(a => a.value === manualTriggerData.actionType)?.description || ''} />
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
