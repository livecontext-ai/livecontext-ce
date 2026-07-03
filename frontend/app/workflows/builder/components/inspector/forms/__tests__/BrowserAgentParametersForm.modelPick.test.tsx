// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

import { BrowserAgentParametersForm } from '../BrowserAgentParametersForm';

// Translation stub - surface keys verbatim, we don't assert copy here.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// ModelPicker pulls in useModels (network) + a heavy dropdown. Stub it as a
// button that, on click, fires onChange with a fixed target model. Also expose
// the current `value` (provider/id) so a test can assert what the picker
// DISPLAYS - that is the user-visible symptom of this bug.
const PICKED = { provider: 'openai', id: 'gpt-4o' };
// pickTarget lets a test override what the picker emits on click (default: PICKED).
let pickTarget: { provider: string; id: string } = PICKED;
vi.mock('@/components/ai/ModelPicker', () => ({
  ModelPicker: (props: any) => (
    <button
      data-testid="model-picker"
      data-display-provider={props.value?.provider ?? ''}
      data-display-id={props.value?.id ?? ''}
      onClick={() => props.onChange(pickTarget)}
    >
      pick
    </button>
  ),
}));

// The remaining children are irrelevant to model selection - stub them thin so
// the form renders without dragging in editors, portals or the models hook.
vi.mock('../ExpressionField', () => ({
  ExpressionField: () => <div data-testid="expr-field" />,
  ConnectionProps: {},
}));
vi.mock('../OptionalSection', () => ({
  OptionalSection: ({ children }: any) => <div>{children}</div>,
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: any) => <div>{children}</div>,
  SelectContent: ({ children }: any) => <div>{children}</div>,
  SelectItem: ({ children }: any) => <div>{children}</div>,
  SelectTrigger: ({ children }: any) => <div>{children}</div>,
  SelectValue: () => null,
}));
vi.mock('@/components/ui/input', () => ({ Input: (p: any) => <input {...p} /> }));
vi.mock('@/components/ui/label', () => ({ Label: ({ children }: any) => <label>{children}</label> }));

function renderForm(data: any, onUpdate = vi.fn()) {
  render(
    <BrowserAgentParametersForm
      node={{ id: 'n1', data } as any}
      data={data}
      onUpdate={onUpdate}
      connectionProps={{} as any}
      findUnknownVariables={() => []}
      getParamExpression={() => ''}
      handleParamExpressionChange={() => {}}
    />,
  );
  return onUpdate;
}

describe('BrowserAgentParametersForm - model pick round-trip', () => {
  beforeEach(() => { vi.clearAllMocks(); pickTarget = PICKED; });
  afterEach(() => cleanup());

  it('writes data.provider/data.model (top level) when a model is picked, not only params.llm', () => {
    // Node as it looks after a first save + reload: the importer hoisted the
    // original model into the top-level fields; data.params is absent.
    const data = {
      id: 'browser_agent-1',
      provider: 'anthropic',
      model: 'claude-old',
    };
    const onUpdate = renderForm(data);

    fireEvent.click(screen.getByTestId('model-picker'));

    expect(onUpdate).toHaveBeenCalledTimes(1);
    const next = onUpdate.mock.calls[0][0];
    // Regression: pre-fix the pick updated ONLY params.llm, leaving these two
    // stale so the picker reverted to the old model on reload.
    expect(next.provider).toBe('openai');
    expect(next.model).toBe('gpt-4o');
  });

  it('keeps params.llm and paramExpressions.llm in sync with the picked model (runner + exporter contracts)', () => {
    const data = { id: 'browser_agent-1', params: { llm: { provider: 'anthropic', model: 'claude-old', max_steps: 25 } } };
    const onUpdate = renderForm(data);

    fireEvent.click(screen.getByTestId('model-picker'));

    const next = onUpdate.mock.calls[0][0];
    // Runner contract: BrowserAgentModule reads params.llm.provider/model.
    expect(next.params.llm.provider).toBe('openai');
    expect(next.params.llm.model).toBe('gpt-4o');
    // Existing llm sub-fields (max_steps) must be preserved, not clobbered.
    expect(next.params.llm.max_steps).toBe(25);
    // Exporter contract: convertParamExpressionsToInputs reads paramExpressions.
    expect(JSON.parse(next.paramExpressions.llm)).toMatchObject({ provider: 'openai', model: 'gpt-4o' });
  });

  it('clears the top-level provider/model when the model is cleared (falls back to workspace default on export)', () => {
    pickTarget = { provider: '', id: '' };
    const data = { id: 'browser_agent-1', provider: 'anthropic', model: 'claude-old', params: { llm: { provider: 'anthropic', model: 'claude-old' } } };
    const onUpdate = renderForm(data);

    fireEvent.click(screen.getByTestId('model-picker'));

    const next = onUpdate.mock.calls[0][0];
    // Empty top-level fields => agentProcessor drops agent.provider/model
    // (guarded by `if (node.data.provider)`), so the runner resolves the
    // category default rather than reusing the stale model.
    expect(next.provider).toBe('');
    expect(next.model).toBe('');
    expect(next.params.llm.provider).toBe('');
    expect(next.params.llm.model).toBe('');
  });

  it('displays the top-level data.model when params.llm is absent (post-reload state)', () => {
    // The importer (AgentNodeCreator) never sets data.params, so after a reload
    // the picker must fall back to data.provider/data.model. This asserts the
    // display source the fix keeps fresh.
    const data = { id: 'browser_agent-1', provider: 'anthropic', model: 'claude-3-5-sonnet' };
    renderForm(data);

    const picker = screen.getByTestId('model-picker');
    expect(picker.getAttribute('data-display-provider')).toBe('anthropic');
    expect(picker.getAttribute('data-display-id')).toBe('claude-3-5-sonnet');
  });
});
