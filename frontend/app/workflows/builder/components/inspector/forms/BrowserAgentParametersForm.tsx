'use client';

/**
 * BrowserAgentParametersForm - inspector form for the agent:browser_agent
 * node. Edits the inline params block (task, start_url, llm, max_steps,
 * timeout_seconds, interaction_mode, domain_allowlist/denylist,
 * screenshot_policy, expected_output_schema). Outputs are documented in
 * BrowserAgentNodeSpec and surfaced via the run preview panel.
 *
 * Design intent: keep the form tight - most params have safe defaults,
 * only `task` is mandatory. Users who need the full surface (custom
 * schemas, viewport, headless toggle) get the OptionalSection drawer.
 */

import * as React from 'react';
import { useTranslations } from 'next-intl';
import type { Node } from 'reactflow';

import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ModelPicker } from '@/components/ai/ModelPicker';
import type { SelectedModel } from '@/hooks/useModels';
import { ExpressionField, ConnectionProps } from '../ExpressionField';
import { OptionalSection } from '../OptionalSection';
import { OptionalFeatureNotice } from '../OptionalFeatureNotice';
import { BROWSER_AGENT_ENABLE_COMMAND } from '@/lib/optionalComponentCommands';
import { useFeatureCapabilities } from '@/hooks/useFeatureCapabilities';
import type { BuilderNodeData } from '../../../types';

const DEFAULT_MAX_STEPS = 25;
const DEFAULT_TIMEOUT_SECONDS = 300;
const DEFAULT_INTERACTION_MODE = 'autonomous';
const DEFAULT_SCREENSHOT_POLICY = 'on_change';

interface BrowserAgentParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  onUpdate: (data: BuilderNodeData) => void;
  isRunMode?: boolean;
  connectionProps: ConnectionProps;
  // findUnknownVariables matches ExpressionField's signature: it receives a
  // map of `{ [variableKey]: expression }` and returns the unknown vars list.
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  getParamExpression: (key: string) => string;
  handleParamExpressionChange: (key: string, value: string) => void;
}

export function BrowserAgentParametersForm({
  node,
  data,
  onUpdate,
  isRunMode = false,
  connectionProps,
  findUnknownVariables,
  getParamExpression,
  handleParamExpressionChange,
}: BrowserAgentParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms.browserAgent');
  // Optional browser stack availability - null while unknown (no false warning).
  const { capabilities } = useFeatureCapabilities();
  const browserAgentMissing = capabilities !== null && !capabilities.browserAgent;

  // Browser-agent params live under data.params per AgentCreator spec -
  // but historic entries may have stored fields at the top level. Read
  // both, prefer params, write back to params.
  const params = ((data as any).params ?? {}) as Record<string, any>;
  const readParam = <T,>(key: string, fallback: T): T =>
    (params[key] as T) ?? ((data as any)[key] as T) ?? fallback;

  const updateParam = (key: string, value: unknown) => {
    // Atomic dual-write: the inspector's UI controls (ModelPicker, Select,
    // numeric inputs) live on `data.params` so they round-trip cleanly,
    // BUT the plan exporter (agentProcessor.ts:100 →
    // convertParamExpressionsToInputs) reads ONLY `data.paramExpressions`.
    // Without writing both, every ModelPicker change is silently dropped at
    // export time - the persisted plan keeps the stale value imported via
    // inputToParamExpressions (which JSON.stringify's objects into the
    // expressions map). We update them in the same onUpdate call so the two
    // callers can't race each other (an earlier two-call sequence
    // deterministically lost the second write because both captured `data`
    // at the same pre-update snapshot).
    const nextParams = { ...params, [key]: value };
    const nextExpressions: Record<string, string> = {
      ...((data.paramExpressions as Record<string, string> | undefined) ?? {}),
    };
    if (value === '' || value == null) {
      delete nextParams[key];
      delete nextExpressions[key];
    } else if (typeof value === 'object') {
      // Plan exporter expects strings in paramExpressions - JSON-stringify
      // objects (llm block, schema, session, allowlist arrays, …) so the
      // exporter's per-key passthrough preserves structure across import /
      // export cycles. The backend deserialises them on its side.
      nextExpressions[key] = JSON.stringify(value);
    } else {
      nextExpressions[key] = String(value);
    }
    // params on BuilderNodeData is typed as `string` for legacy reasons but
    // the runtime shape here is the structured Record we're updating. The
    // double-cast through unknown is the TS-suggested form for this case
    // (the old form `as BuilderNodeData` started failing once the form
    // accumulated enough fields for TS to refuse the direct narrowing).
    onUpdate({ ...data, params: nextParams, paramExpressions: nextExpressions } as unknown as BuilderNodeData);
  };

  const llm = (params.llm ?? {}) as Record<string, any>;
  const modelSelection: SelectedModel = {
    provider: llm.provider ?? data.provider ?? '',
    id: llm.model ?? data.model ?? '',
  };
  const handleModelPick = (next: SelectedModel) => {
    // Keep THREE storage sites in sync in ONE atomic onUpdate, mirroring
    // classify/guardrail which write data.provider/data.model directly:
    //  - params.llm            : the runner contract (BrowserAgentModule
    //                            reads params.llm.provider/model).
    //  - paramExpressions.llm  : the plan exporter's read source
    //                            (convertParamExpressionsToInputs).
    //  - data.provider/model   : the ModelPicker's display fallback AND the
    //                            fields agentProcessor.ts emits as
    //                            agent.provider/model. If we update only
    //                            params.llm (as updateParam does), these two
    //                            stay stale: AgentNodeCreator only hoists
    //                            params.llm -> data.provider/model when they
    //                            are BLANK, so after the first save every
    //                            later model change reverts on reload to the
    //                            old data.model. Writing all three here is
    //                            what makes the picked model stick.
    const nextLlm = { ...llm, provider: next.provider, model: next.id };
    const nextParams = { ...params, llm: nextLlm };
    const nextExpressions: Record<string, string> = {
      ...((data.paramExpressions as Record<string, string> | undefined) ?? {}),
      llm: JSON.stringify(nextLlm),
    };
    onUpdate({
      ...data,
      provider: next.provider,
      model: next.id,
      params: nextParams,
      paramExpressions: nextExpressions,
    } as unknown as BuilderNodeData);
  };

  const interactionMode = readParam<string>('interaction_mode', DEFAULT_INTERACTION_MODE);
  const screenshotPolicy = readParam<string>('screenshot_policy', DEFAULT_SCREENSHOT_POLICY);
  const maxSteps = (llm.max_steps as number | undefined) ?? readParam<number>('max_steps', DEFAULT_MAX_STEPS);
  const timeoutSeconds = readParam<number>('timeout_seconds', DEFAULT_TIMEOUT_SECONDS);

  const allowlist = (readParam<string[]>('domain_allowlist', []) ?? []).join(', ');
  const denylist = (readParam<string[]>('domain_denylist', []) ?? []).join(', ');

  const startUrlValue = getParamExpression('start_url') || readParam<string>('start_url', '');
  const [showOptional, setShowOptional] = React.useState(false);

  // Count optional fields that diverge from default (to render the
  // "Optional (N)" badge).
  const optionalCount = React.useMemo(() => {
    let n = 0;
    if (startUrlValue && startUrlValue.length > 0) n++;
    if (interactionMode !== DEFAULT_INTERACTION_MODE) n++;
    if (screenshotPolicy !== DEFAULT_SCREENSHOT_POLICY) n++;
    if (maxSteps !== DEFAULT_MAX_STEPS) n++;
    if (timeoutSeconds !== DEFAULT_TIMEOUT_SECONDS) n++;
    if (allowlist.length > 0) n++;
    if (denylist.length > 0) n++;
    return n;
  }, [startUrlValue, interactionMode, screenshotPolicy, maxSteps, timeoutSeconds, allowlist, denylist]);

  return (
    <div className="space-y-5 pt-2">
      {/* Browser stack missing on this install: the node WILL fail at run time -
          say so up front with the enable path instead of a run-time surprise. */}
      {!isRunMode && browserAgentMissing && (
        <OptionalFeatureNotice
          message={t('unavailableNotice')}
          command={BROWSER_AGENT_ENABLE_COMMAND}
        />
      )}

      {/* LLM picker - FIRST, mirrors the classify/guardrail layout where
          provider/model is the most prominent control. Browser-agent steps
          each cost an LLM call, so the choice belongs at the top. */}
      <ModelPicker
        value={modelSelection}
        onChange={handleModelPick}
        disabled={isRunMode}
      />

      {/* Task - mandatory */}
      <ExpressionField
        label={t('task.label')}
        value={getParamExpression('task') || readParam<string>('task', '')}
        onChange={(v) => handleParamExpressionChange('task', v)}
        nodeId={node.id}
        fieldName="param-task"
        isRequired={true}
        isRunMode={isRunMode}
        findUnknownVariables={findUnknownVariables}
        variableKey="task"
        connectionProps={connectionProps}
        placeholder={t('task.placeholder')}
        description={t('task.help')}
      />

      {/* Optional advanced params - same OptionalSection contract as
          ClassifyParametersForm: isOpen / onToggle / count. Start URL is
          here because the runner already infers a URL from the task when
          omitted (browser-use's standard fallback) - surfacing it as a
          top-level required-looking field misled users. Note: max_steps
          lives under llm.max_steps, not as a top-level optional, since
          it's part of the LLM call shape consumed by the runner. */}
      <OptionalSection
        isOpen={showOptional}
        onToggle={() => setShowOptional((v) => !v)}
        count={optionalCount}
      >
        <div className="space-y-3">
          {/* Start URL */}
          <ExpressionField
            label={t('startUrl.label')}
            value={startUrlValue}
            onChange={(v) => handleParamExpressionChange('start_url', v)}
            nodeId={node.id}
            fieldName="param-start_url"
            isRunMode={isRunMode}
            findUnknownVariables={findUnknownVariables}
            variableKey="start_url"
            connectionProps={connectionProps}
            placeholder="https://example.com"
            description={t('startUrl.help')}
          />

          {/* Interaction mode */}
          <div>
            <Label className="text-xs">{t('interactionMode.label')}</Label>
            <Select
              value={interactionMode}
              onValueChange={(v) => updateParam('interaction_mode', v)}
              disabled={isRunMode}
            >
              <SelectTrigger className="h-8 text-sm">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="autonomous">{t('interactionMode.autonomous')}</SelectItem>
                <SelectItem value="supervised">{t('interactionMode.supervised')}</SelectItem>
                <SelectItem value="manual">{t('interactionMode.manual')}</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {/* Max steps + timeout */}
          <div className="grid grid-cols-2 gap-2">
            <div>
              <Label className="text-xs">{t('maxSteps.label')}</Label>
              <Input
                type="number"
                min={1}
                max={100}
                value={maxSteps}
                onChange={(e) => {
                  const n = Number(e.target.value);
                  updateParam('llm', { ...llm, max_steps: Number.isFinite(n) ? n : DEFAULT_MAX_STEPS });
                }}
                disabled={isRunMode}
                className="h-8 text-sm"
              />
            </div>
            <div>
              <Label className="text-xs">{t('timeout.label')}</Label>
              <Input
                type="number"
                min={10}
                max={600}
                value={timeoutSeconds}
                onChange={(e) => updateParam('timeout_seconds', Number(e.target.value))}
                disabled={isRunMode}
                className="h-8 text-sm"
              />
            </div>
          </div>

          {/* Screenshot policy */}
          <div>
            <Label className="text-xs">{t('screenshotPolicy.label')}</Label>
            <Select
              value={screenshotPolicy}
              onValueChange={(v) => updateParam('screenshot_policy', v)}
              disabled={isRunMode}
            >
              <SelectTrigger className="h-8 text-sm">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="every_step">{t('screenshotPolicy.everyStep')}</SelectItem>
                <SelectItem value="on_change">{t('screenshotPolicy.onChange')}</SelectItem>
                <SelectItem value="final_only">{t('screenshotPolicy.finalOnly')}</SelectItem>
                <SelectItem value="off">{t('screenshotPolicy.off')}</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {/* Domain allow/deny */}
          <div>
            <Label className="text-xs">{t('domainAllowlist.label')}</Label>
            <Input
              type="text"
              value={allowlist}
              onChange={(e) =>
                updateParam(
                  'domain_allowlist',
                  e.target.value.split(',').map((s) => s.trim()).filter(Boolean),
                )
              }
              placeholder="example.com, api.example.com"
              disabled={isRunMode}
              className="h-8 text-sm"
            />
          </div>
          <div>
            <Label className="text-xs">{t('domainDenylist.label')}</Label>
            <Input
              type="text"
              value={denylist}
              onChange={(e) =>
                updateParam(
                  'domain_denylist',
                  e.target.value.split(',').map((s) => s.trim()).filter(Boolean),
                )
              }
              placeholder="ads.example.com"
              disabled={isRunMode}
              className="h-8 text-sm"
            />
          </div>
        </div>
      </OptionalSection>
    </div>
  );
}
