'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import type { ApprovalContinuationMode, ApprovalDelegation } from '../../../types';
import { ApprovalDelegationSection } from './ApprovalDelegationSection';
import { InfoPopover } from '@/components/ui/info-popover';

/** Syntax example for the approval context (code/syntax token, intentionally not translated). */
const CONTEXT_TEMPLATE_PLACEHOLDER =
  'Approve refund of {{trigger:form.output.amount}} for {{trigger:form.output.email}}?';

interface ApprovalOutputsFormProps {
  isRunMode?: boolean;
  approvalTimeoutMs?: number;
  handleTimeoutChange: (timeoutMs: number | undefined) => void;
  approvalContextTemplate?: string;
  handleContextTemplateChange: (template: string | undefined) => void;
  approvalContinuationMode?: ApprovalContinuationMode;
  handleContinuationModeChange: (mode: ApprovalContinuationMode | undefined) => void;
  approvalDelegation?: ApprovalDelegation;
  handleDelegationChange: (delegation: ApprovalDelegation | undefined) => void;
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
  approvalContinuationMode,
  handleContinuationModeChange,
  approvalDelegation,
  handleDelegationChange,
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
            <InfoPopover label={t('approval.contextLabel')} size="sm" side="right" align="start">
              <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                <p className="font-semibold text-slate-900 dark:text-slate-100">{t('approval.contextInfoTitle')}</p>
                <p>{t('approval.contextInfoBody')}</p>
                <p>{t('approval.contextInfoVariables')}</p>
                <code className="block text-xs bg-slate-100 dark:bg-slate-800 rounded px-2 py-1 break-words">
                  {CONTEXT_TEMPLATE_PLACEHOLDER}
                </code>
                <p className="text-xs">{t('approval.contextInfoOptional')}</p>
              </div>
            </InfoPopover>
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
            <InfoPopover label="Timeout (milliseconds)" size="sm" side="right" align="start">
              <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                <p className="font-semibold text-slate-900 dark:text-slate-100">Approval Timeout</p>
                <p>Maximum time to wait for user approval before the timeout path is taken.</p>
                <ul className="list-disc list-inside space-y-1 text-xs">
                  <li>Duration is specified in milliseconds</li>
                  <li>Leave empty for no timeout (wait indefinitely)</li>
                  <li>Use presets for common durations</li>
                </ul>
              </div>
            </InfoPopover>
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
                    ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)]'
                    : 'bg-[var(--bg-tertiary)] text-[var(--text-secondary)] hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)]'
                }`}
              >
                {preset.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Continuation mode (only relevant inside a split: per-item vs all-items fan-out) */}
      <div className="flex flex-col gap-2">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('approval.continuationLabel')}</span>
          <InfoPopover label={t('approval.continuationLabel')} size="sm" side="right" align="start">
            <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
              <p className="font-semibold text-slate-900 dark:text-slate-100">{t('approval.continuationInfoTitle')}</p>
              <p>{t('approval.continuationInfoBody')}</p>
              <p className="text-xs">{t('approval.continuationInfoScope')}</p>
            </div>
          </InfoPopover>
        </div>
        <Select
          value={approvalContinuationMode ?? 'all_items'}
          onValueChange={(value) => handleContinuationModeChange(value === 'per_item' ? 'per_item' : undefined)}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all_items">{t('approval.continuationAllItems')}</SelectItem>
            <SelectItem value="per_item">{t('approval.continuationPerItem')}</SelectItem>
          </SelectContent>
        </Select>
        <p className="text-xs text-slate-400 dark:text-slate-500">{t('approval.continuationHint')}</p>
      </div>

      {/* Delegate via external channel (optional, v1: Telegram) */}
      <ApprovalDelegationSection
        isRunMode={isRunMode}
        approvalDelegation={approvalDelegation}
        handleDelegationChange={handleDelegationChange}
      />
    </div>
  );
}
