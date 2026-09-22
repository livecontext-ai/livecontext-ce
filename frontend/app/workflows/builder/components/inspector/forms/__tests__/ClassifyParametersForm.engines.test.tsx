// @vitest-environment jsdom
/**
 * The classify inspector is the only place that asks its picker for two kinds of model,
 * and the only place that adapts the form to the one chosen.
 *
 * Without this file, deleting either prop from the ModelPicker call leaves every other
 * frontend test green: `ModelPicker.decisionEngine.test.tsx` proves the picker behaves
 * WHEN GIVEN them, not that anything passes them. Both are needed and for different
 * reasons - the category fetches the slice the chat answer never contains, the capability
 * says which of the merged result belongs here - so each is asserted separately.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';

import { ClassifyParametersForm } from '../ClassifyParametersForm';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// Capture what the form asks its picker for. The props ARE the contract here.
const pickerProps: Record<string, unknown>[] = [];
vi.mock('@/components/ai/ModelPicker', () => ({
  ModelPicker: (props: any) => {
    pickerProps.push(props);
    return <button data-testid="model-picker">pick</button>;
  },
}));

vi.mock('../../ExpressionField', () => ({
  ExpressionField: ({ label }: any) => <div data-testid="expression-field">{label}</div>,
}));
vi.mock('../../OptionalSection', () => ({
  OptionalSection: ({ children }: any) => <div data-testid="optional-section">{children}</div>,
}));
vi.mock('@/components/ui/expression-editor', () => ({
  ExpressionEditor: () => <div />,
}));
vi.mock('next/image', () => ({ default: () => null }));

const baseData = (overrides: Record<string, unknown> = {}) => ({
  id: 'classify-1',
  label: 'Route ticket',
  classifyCategories: [
    { id: 'c1', label: 'billing', description: 'Payment issues' },
    { id: 'c2', label: 'technical', description: 'Bugs' },
  ],
  ...overrides,
}) as any;

const renderForm = (data: Record<string, unknown>) =>
  render(
    <ClassifyParametersForm
      node={{ id: 'classify-1', data } as any}
      data={data as any}
      onUpdate={() => {}}
      connectionProps={{} as any}
      findUnknownVariables={() => []}
      getParamExpression={() => ''}
      handleParamExpressionChange={() => {}}
    />,
  );

describe('ClassifyParametersForm - the two engines', () => {
  beforeEach(() => { pickerProps.length = 0; });
  afterEach(() => cleanup());

  it('asks its picker for BOTH engines, so the chat one stays available', () => {
    renderForm(baseData());

    expect(pickerProps).toHaveLength(1);
    expect(pickerProps[0].filterCapability).toEqual(['chat', 'decision']);
  });

  it('asks for the classification slice, which the chat answer never contains', () => {
    // The half a capability filter cannot supply: it can only subtract from what arrived.
    renderForm(baseData());

    expect(pickerProps[0].unionCategory).toBe('classification');
  });

  it('prices the classify unit of work, not a whole conversation', () => {
    // An agent conversation costs roughly a hundred times a classify step, so the wrong
    // profile here quotes a number that is wrong on every row of the picker.
    renderForm(baseData());

    expect(pickerProps[0].costProfile).toBe('classifyStep');
  });

  it('hides the sampling controls on a decision engine, which ignores them', () => {
    renderForm(baseData({ provider: 'typesafe', model: 'jev-latest' }));

    expect(screen.queryByTestId('optional-section')).toBeNull();
  });

  it('keeps them on a chat engine, where they do something', () => {
    renderForm(baseData({ provider: 'openai', model: 'gpt-5-mini' }));

    expect(screen.queryByTestId('optional-section')).not.toBeNull();
  });

  it('keeps them when no provider is set yet, which is the platform default (a chat model)', () => {
    renderForm(baseData());

    expect(screen.queryByTestId('optional-section')).not.toBeNull();
  });

  it('recognises the decision provider whatever its casing', () => {
    // The catalogue promises no casing, and the node stores whatever was picked.
    renderForm(baseData({ provider: 'TypeSafe', model: 'jev-latest' }));

    expect(screen.queryByTestId('optional-section')).toBeNull();
  });
});
