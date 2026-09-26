'use client';

import * as React from 'react';
import clsx from 'clsx';
import { useTranslations } from 'next-intl';
import { ChevronRight } from 'lucide-react';
import type { Node } from 'reactflow';
import { Input } from '@/components/ui/input';
import { InspectorToggleRow } from './InspectorToggleRow';
import type { BuilderNodeData, NodePolicy } from '../../types';
import {
  MAX_RETRY_COUNT,
  isContinueOnFailureBlocked,
  isExecuteOnceBlocked,
  nodeCallsProvider,
  sanitizeNodePolicy,
} from '../../utils/nodePolicy';
import { nodeSupportsMock, sanitizeNodeMock } from '../../utils/nodeMock';
import { MockOutputSection } from './MockOutputSection';
import { InfoPopover } from '@/components/ui/info-popover';

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
 *
 * Also hosts the Mock output block (`MockOutputSection`) for mock-capable
 * nodes, so every per-node execution knob lives under the one Settings
 * section.
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
  const supportsMock = nodeSupportsMock(node);
  const mockActive = React.useMemo(() => {
    if (!supportsMock) return false;
    const mock = sanitizeNodeMock(data.mock);
    return !!mock && mock.enabled !== false;
  }, [supportsMock, data.mock]);
  // Counts what this node's controls can actually SHOW. A provider-retry budget stored on a node
  // that makes no provider call (only reachable from a plan written before the tool actions began
  // refusing it) renders no input here, so counting it produced a "Settings (1)" badge that
  // auto-expanded a section where every visible control sat at its default.
  const visiblePolicyKeys = Object.keys(policy).filter(
    (key) => key !== 'providerRetryMaxWaitSec' || nodeCallsProvider(node)
  );
  const activeCount = visiblePolicyKeys.length + (mockActive ? 1 : 0);
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

  const handleProviderRetryChange = React.useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      if (isRunMode) return;
      const raw = event.target.value;
      // Empty clears the setting and hands the decision back to the platform. It cannot go
      // through handleNumberChange, which reads '' as 0 - and 0 here means the opposite:
      // "never retry". The two states have to stay distinguishable.
      if (raw === '') {
        const { providerRetryMaxWaitSec: _dropped, ...rest } = policy;
        const next = sanitizeNodePolicy(rest);
        if (next) {
          onUpdate({ ...data, nodePolicy: next });
        } else if (data.nodePolicy !== undefined) {
          const cleared = { ...data };
          delete cleared.nodePolicy;
          onUpdate(cleared);
        }
        return;
      }
      // Whole seconds only, and checked on the RAW text. parseInt('0.5') is 0, and 0 on this field
      // is not "under a second", it is the switch that turns the platform retry off - the one state
      // the whole field exists to make explicit. A number input does not stop a fraction, so
      // anything that is not a plain non-negative integer leaves the setting as it stands rather
      // than being rounded into a decision nobody made.
      if (!/^\d+$/.test(raw.trim())) {
        return;
      }
      const parsed = parseInt(raw.trim(), 10);
      if (isNaN(parsed)) {
        return;
      }
      writePolicy({ providerRetryMaxWaitSec: parsed });
    },
    [isRunMode, policy, data, onUpdate, writePolicy]
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

  /**
   * What the platform will actually allow when this field is left EMPTY, or null when the answer is
   * "the platform's own budget".
   *
   * Mirrors StepNode.resolveProviderRetryBudget: a node that retries cedes entirely, and a node
   * that declares a per-attempt timeout gets half that window, because the attempt has to pay for
   * the requests as well as the wait. Shown because the field would otherwise read "Platform
   * decides" while a 1s timeout had already reduced it to zero - the very state this control exists
   * to make explicit, entered without anyone choosing it.
   */
  const impliedProviderRetry = React.useMemo(() => {
    if (policy.providerRetryMaxWaitSec !== undefined) return null;
    if (retryCount > 0) return 0;
    const timeoutMs = policy.timeoutMs ?? 0;
    return timeoutMs > 0 ? Math.floor(timeoutMs / 2000) : null;
  }, [policy.providerRetryMaxWaitSec, policy.timeoutMs, retryCount]);

  /**
   * The value the platform will actually use when this field IS set but the node's own timeout
   * bounds it lower, or null when what is typed is what applies.
   *
   * Without this the field showed 45 while the platform applied 1, which is the state the backend
   * bound exists to prevent: an author who believes a 5s Retry-After will be waited out gets the
   * attempt abandoned first. Showing the number and saying nothing is the one way to make a
   * correct guard read as a broken one.
   */
  const cappedProviderRetry = React.useMemo(() => {
    const asked = policy.providerRetryMaxWaitSec;
    const timeoutMs = policy.timeoutMs ?? 0;
    if (asked === undefined || timeoutMs <= 0) return null;
    const ceiling = Math.floor(timeoutMs / 2000);
    return asked > ceiling ? ceiling : null;
  }, [policy.providerRetryMaxWaitSec, policy.timeoutMs]);

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

          {/* Provider retry budget - only where a provider is actually called */}
          {nodeCallsProvider(node) ? (
            <div className="flex flex-col gap-1.5">
              <div className="flex items-center gap-1.5">
                <label className="text-sm font-medium text-slate-500 dark:text-slate-400">
                  {t('providerRetryLabel')}
                </label>
                <InfoPopover label={t('providerRetryLabel')} size="sm" side="right" align="start" contentClassName="w-[300px] p-3" data-testid="node-settings-provider-retry-info">
                  <p className="text-xs text-slate-600 dark:text-slate-300">
                    {t('providerRetryInfoDefault')}
                  </p>
                  <p className="mt-2 text-xs text-slate-600 dark:text-slate-300">
                    {t('providerRetryInfoZero')}
                  </p>
                  <p className="mt-2 text-xs text-slate-600 dark:text-slate-300">
                    {t('providerRetryInfoCap')}
                  </p>
                  <p className="mt-2 text-xs text-slate-600 dark:text-slate-300">
                    {t('providerRetryInfoRunning')}
                  </p>
                </InfoPopover>
              </div>
              <Input
                type="number"
                min={0}
                step={1}
                value={policy.providerRetryMaxWaitSec ?? ''}
                onChange={handleProviderRetryChange}
                placeholder={impliedProviderRetry !== null
                  ? String(impliedProviderRetry)
                  : t('providerRetryPlaceholder')}
                readOnly={isRunMode}
                aria-label={t('providerRetryLabel')}
                data-testid="node-settings-provider-retry"
                className="w-full"
              />
              <p className="text-sm text-slate-400 dark:text-slate-500">
                {cappedProviderRetry !== null
                  ? t('providerRetryHelpCappedByTimeout', { seconds: cappedProviderRetry })
                  : policy.providerRetryMaxWaitSec !== undefined
                    ? t('providerRetryHelp')
                    : retryCount > 0
                      ? t('providerRetryHelpCededToNode')
                      : impliedProviderRetry !== null
                        ? t('providerRetryHelpBoundedByTimeout', { seconds: impliedProviderRetry })
                        : t('providerRetryHelp')}
              </p>
            </div>
          ) : null}

          {/* Continue on fail */}
          <InspectorToggleRow
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
          <InspectorToggleRow
            label={t('executeOnceLabel')}
            help={t('executeOnceHelp')}
            checked={policy.executeOnce === true}
            onChange={(checked) => writePolicy({ executeOnce: checked })}
            disabled={isRunMode || executeOnceBlocked}
            blockedReason={executeOnceBlocked ? t('executeOnceBlockedTooltip') : undefined}
            testId="node-settings-execute-once"
          />

          {/* Mock output - editor runs serve it instead of executing the
              node; split/merge/aggregate/loop/fork cores are excluded
              (backend rejects a mock there) */}
          {supportsMock ? (
            <MockOutputSection
              node={node}
              data={data}
              onUpdate={onUpdate}
              isRunMode={isRunMode}
            />
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
