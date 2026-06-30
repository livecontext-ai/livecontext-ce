'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ExpressionEditor } from '@/components/ui/expression-editor';

/** Syntax example for the approval context (code/syntax token, intentionally not translated). */
const CONTEXT_TEMPLATE_PLACEHOLDER =
  'Approve refund of {{trigger:form.output.amount}} for {{trigger:form.output.email}}?';

interface ApprovalOutputsFormProps {
  isRunMode?: boolean;
  approvalTimeoutMs?: number;
  handleTimeoutChange: (timeoutMs: number | undefined) => void;
  approvalContextTemplate?: string;
  handleContextTemplateChange: (template: string | undefined) => void;
}

function formatDuration(ms: number): string {
  if (ms >= 86_400_000) {
    const d = Math.floor(ms / 86_400_000);
    const h = Math.floor((ms % 86_400_000) / 3_600_000);
    return h > 0 ? `${d}d ${h}h` : `${d} day${d > 1 ? 's' : ''}`;
  }
  if (ms >= 3_600_000) {
    const h = Math.floor(ms / 3_600_000);
    const m = Math.floor((ms % 3_600_000) / 60_000);
    return m > 0 ? `${h}h ${m}m` : `${h} hour${h > 1 ? 's' : ''}`;
  }
  if (ms >= 60_000) {
    const m = Math.floor(ms / 60_000);
    const s = Math.floor((ms % 60_000) / 1000);
    return s > 0 ? `${m}m ${s}s` : `${m} minute${m > 1 ? 's' : ''}`;
  }
  if (ms >= 1000) {
    const s = Math.floor(ms / 1000);
    return `${s} second${s > 1 ? 's' : ''}`;
  }
  return `${ms}ms`;
}

export function ApprovalOutputsForm({
  isRunMode = false,
  approvalTimeoutMs,
  handleTimeoutChange,
  approvalContextTemplate,
  handleContextTemplateChange,
}: ApprovalOutputsFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  const duration = approvalTimeoutMs ?? 0;

  const handleDurationChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isRunMode) return;
    const value = event.target.value;
    if (value === '') {
      handleTimeoutChange(undefined);
      return;
    }
    const numValue = parseInt(value, 10);
    if (isNaN(numValue) || numValue < 0) return;
    handleTimeoutChange(numValue > 0 ? numValue : undefined);
  }, [isRunMode, handleTimeoutChange]);

  return (
    <div className="space-y-4 pt-2">
      {/* Approval context (resolved at pause time, shown to the approver) */}
      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('approval.contextLabel')}</span>
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
                  <p className="font-semibold text-slate-900 dark:text-slate-100">{t('approval.contextInfoTitle')}</p>
                  <p>{t('approval.contextInfoBody')}</p>
                  <p>{t('approval.contextInfoVariables')}</p>
                  <code className="block text-xs bg-slate-100 dark:bg-slate-800 rounded px-2 py-1 break-words">
                    {CONTEXT_TEMPLATE_PLACEHOLDER}
                  </code>
                  <p className="text-xs">{t('approval.contextInfoOptional')}</p>
                </div>
              </PopoverContent>
            </Popover>
          </div>
        </div>
        <ExpressionEditor
          value={approvalContextTemplate ?? ''}
          onChange={(value) => handleContextTemplateChange(value === '' ? undefined : value)}
          placeholder={CONTEXT_TEMPLATE_PLACEHOLDER}
          className="w-full"
          isRequired
          readOnly={isRunMode}
        />
      </div>

      {/* Timeout input */}
      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">Timeout (milliseconds)</span>
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
                  <p className="font-semibold text-slate-900 dark:text-slate-100">Approval Timeout</p>
                  <p>Maximum time to wait for user approval before the timeout path is taken.</p>
                  <ul className="list-disc list-inside space-y-1 text-xs">
                    <li>Duration is specified in milliseconds</li>
                    <li>Leave empty for no timeout (wait indefinitely)</li>
                    <li>Use presets for common durations</li>
                  </ul>
                </div>
              </PopoverContent>
            </Popover>
          </div>
          <span className="text-sm text-slate-500 dark:text-slate-400">Optional</span>
        </div>
        <Input
          type="number"
          min="0"
          step="1000"
          value={duration > 0 ? duration : ''}
          onChange={handleDurationChange}
          className="w-full"
          placeholder="86400000"
          readOnly={isRunMode}
        />
        {duration > 0 && (
          <p className="text-sm text-slate-400 dark:text-slate-500">
            {formatDuration(duration)}
          </p>
        )}
      </div>

      {/* Quick presets */}
      {!isRunMode && (
        <div className="flex flex-col gap-2">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">Quick Presets</span>
          <div className="flex flex-wrap gap-2">
            {[
              { label: '5m', value: 300_000 },
              { label: '15m', value: 900_000 },
              { label: '30m', value: 1_800_000 },
              { label: '1h', value: 3_600_000 },
              { label: '6h', value: 21_600_000 },
              { label: '12h', value: 43_200_000 },
              { label: '24h', value: 86_400_000 },
            ].map((preset) => (
              <button
                key={preset.value}
                type="button"
                onClick={() => handleTimeoutChange(preset.value)}
                className={`px-2 py-1 text-xs rounded-md transition-colors ${
                  duration === preset.value
                    ? 'bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900'
                    : 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-600'
                }`}
              >
                {preset.label}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
