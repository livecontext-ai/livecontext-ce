'use client';

import * as React from 'react';
import clsx from 'clsx';
import { useTranslations } from 'next-intl';
import { ChevronRight } from 'lucide-react';
import type { Node } from 'reactflow';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import type { BuilderNodeData, NodePolicy } from '../../types';
import {
  MAX_RETRY_COUNT,
  isContinueOnFailureBlocked,
  isExecuteOnceBlocked,
  sanitizeNodePolicy,
} from '../../utils/nodePolicy';

interface NodeSettingsSectionProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  onUpdate: (data: BuilderNodeData) => void;
  isRunMode?: boolean;
}

/**
 * Generic n8n-style "Settings" section rendered at the bottom of the
 * inspector parameter column for EVERY executable node type (triggers and
 * notes are excluded by the caller - the backend parser ignores a policy
 * there).
 *
 * Edits the node's plan-level `nodePolicy` block. Defaults render as
 * empty/off and the section only writes a `nodePolicy` object onto the
 * builder node data when at least one field is non-default, so plans stay
 * clean. The BACKEND is the single validator; the gating here (continue-on-
 * fail on branching nodes, execute-once on split coordinators) only mirrors
 * its parse-time rules.
 */
export function NodeSettingsSection({
  node,
  data,
  onUpdate,
  isRunMode = false,
}: NodeSettingsSectionProps) {
  const t = useTranslations('workflowBuilder.nodeSettings');

  const policy = React.useMemo<NodePolicy>(
    () => sanitizeNodePolicy(data.nodePolicy) ?? {},
    [data.nodePolicy]
  );
  const activeCount = Object.keys(policy).length;
  const [isOpen, setIsOpen] = React.useState(activeCount > 0);

  const continueBlocked = isContinueOnFailureBlocked(node);
  const executeOnceBlocked = isExecuteOnceBlocked(node);

  const writePolicy = React.useCallback(
    (patch: Partial<NodePolicy>) => {
      if (isRunMode) return;
      const next = sanitizeNodePolicy({ ...policy, ...patch });
      if (next) {
        onUpdate({ ...data, nodePolicy: next });
      } else if (data.nodePolicy !== undefined) {
        const rest = { ...data };
        delete rest.nodePolicy;
        onUpdate(rest);
      }
    },
    [isRunMode, policy, data, onUpdate]
  );

  const handleNumberChange = React.useCallback(
    (field: 'retryCount' | 'retryBackoffMs' | 'timeoutMs') =>
      (event: React.ChangeEvent<HTMLInputElement>) => {
        const rawValue = event.target.value;
        let value = rawValue === '' ? 0 : parseInt(rawValue, 10);
        if (isNaN(value) || value < 0) value = 0;
        if (field === 'retryCount') {
          value = Math.min(value, MAX_RETRY_COUNT);
          // Backoff is meaningless (and hidden) without retries - drop it too
          // so no stale value silently survives in the plan.
          writePolicy(value === 0 ? { retryCount: 0, retryBackoffMs: 0 } : { retryCount: value });
          return;
        }
        writePolicy({ [field]: value });
      },
    [writePolicy]
  );

  const retryCount = policy.retryCount ?? 0;

  return (
    <div
      className="border-t border-slate-200 dark:border-slate-700"
      data-testid="node-settings-section"
    >
      <button
        type="button"
        className="flex items-center gap-2 w-full py-2.5 text-sm font-semibold text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-slate-100 transition-colors"
        onClick={() => setIsOpen((open) => !open)}
        aria-expanded={isOpen}
        data-testid="node-settings-toggle"
      >
        <ChevronRight
          className={clsx('h-3.5 w-3.5 transition-transform', isOpen && 'rotate-90')}
        />
        <span>{t('title')}</span>
        {activeCount > 0 ? (
          <span className="text-xs text-slate-400 dark:text-slate-500">({activeCount})</span>
        ) : null}
      </button>

      {isOpen ? (
        <div className="space-y-4 pb-2 pl-1">
          {/* Retry on fail */}
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-slate-500 dark:text-slate-400">
              {t('retryCountLabel')}
            </label>
            <Input
              type="number"
              min={0}
              max={MAX_RETRY_COUNT}
              step={1}
              value={retryCount > 0 ? retryCount : ''}
              onChange={handleNumberChange('retryCount')}
              placeholder="0"
              readOnly={isRunMode}
              aria-label={t('retryCountLabel')}
              data-testid="node-settings-retry-count"
              className="w-full"
            />
            <p className="text-sm text-slate-400 dark:text-slate-500">{t('retryCountHelp')}</p>
          </div>

          {/* Retry backoff - only meaningful with retries enabled */}
          {retryCount > 0 ? (
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-slate-500 dark:text-slate-400">
                {t('retryBackoffLabel')}
              </label>
              <Input
                type="number"
                min={0}
                step={100}
                value={policy.retryBackoffMs ?? ''}
                onChange={handleNumberChange('retryBackoffMs')}
                placeholder="0"
                readOnly={isRunMode}
                aria-label={t('retryBackoffLabel')}
                data-testid="node-settings-retry-backoff"
                className="w-full"
              />
              <p className="text-sm text-slate-400 dark:text-slate-500">{t('retryBackoffHelp')}</p>
            </div>
          ) : null}

          {/* Continue on fail */}
          <PolicyToggleRow
            label={t('continueOnFailureLabel')}
            help={t('continueOnFailureHelp')}
            checked={policy.continueOnFailure === true}
            onChange={(checked) => writePolicy({ continueOnFailure: checked })}
            disabled={isRunMode || continueBlocked}
            blockedReason={continueBlocked ? t('continueOnFailureBlockedTooltip') : undefined}
            testId="node-settings-continue-on-failure"
          />

          {/* Timeout */}
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-slate-500 dark:text-slate-400">
              {t('timeoutLabel')}
            </label>
            <Input
              type="number"
              min={0}
              step={1000}
              value={policy.timeoutMs ?? ''}
              onChange={handleNumberChange('timeoutMs')}
              placeholder="0"
              readOnly={isRunMode}
              aria-label={t('timeoutLabel')}
              data-testid="node-settings-timeout"
              className="w-full"
            />
            <p className="text-sm text-slate-400 dark:text-slate-500">{t('timeoutHelp')}</p>
          </div>

          {/* Execute once */}
          <PolicyToggleRow
            label={t('executeOnceLabel')}
            help={t('executeOnceHelp')}
            checked={policy.executeOnce === true}
            onChange={(checked) => writePolicy({ executeOnce: checked })}
            disabled={isRunMode || executeOnceBlocked}
            blockedReason={executeOnceBlocked ? t('executeOnceBlockedTooltip') : undefined}
            testId="node-settings-execute-once"
          />
        </div>
      ) : null}
    </div>
  );
}

interface PolicyToggleRowProps {
  label: string;
  help: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled: boolean;
  /** When set, the toggle is gated for this node type - shown as a tooltip. */
  blockedReason?: string;
  testId: string;
}

function PolicyToggleRow({
  label,
  help,
  checked,
  onChange,
  disabled,
  blockedReason,
  testId,
}: PolicyToggleRowProps) {
  const toggle = (
    <Switch
      checked={checked}
      onCheckedChange={onChange}
      disabled={disabled}
      aria-label={label}
    />
  );

  return (
    <div className="flex flex-col gap-1.5" data-testid={testId}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium text-slate-500 dark:text-slate-400">{label}</span>
        {blockedReason ? (
          <TooltipProvider delayDuration={150}>
            <Tooltip>
              <TooltipTrigger asChild>
                {/* span wrapper: a disabled button does not fire pointer events */}
                <span tabIndex={0} data-testid={`${testId}-blocked`}>{toggle}</span>
              </TooltipTrigger>
              <TooltipContent side="left" className="max-w-72">
                {blockedReason}
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        ) : (
          toggle
        )}
      </div>
      <p className="text-sm text-slate-400 dark:text-slate-500">
        {blockedReason ?? help}
      </p>
    </div>
  );
}
