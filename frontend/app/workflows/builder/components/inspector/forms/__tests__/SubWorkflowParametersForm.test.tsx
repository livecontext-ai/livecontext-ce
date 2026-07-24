// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { SubWorkflowParametersForm } from '../SubWorkflowParametersForm';

// Surface the i18n key as its own value so we can assert labels/placeholders.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

afterEach(cleanup);

function renderForm(data: any, onUpdate = vi.fn()) {
  const node = { id: 'core:publish_video', data } as any;
  render(<SubWorkflowParametersForm node={node} data={data} onUpdate={onUpdate} />);
  return onUpdate;
}

describe('SubWorkflowParametersForm', () => {
  it('renders a STRING inputMapping without crashing (regression: plan/MCP nodes store a SpEL string, the old array-map crashed the builder)', () => {
    // Pre-fix this threw "f.map is not a function" and unmounted the whole canvas.
    expect(() =>
      renderForm({ subWorkflowId: 'wf-1', subWorkflowInputMapping: '{{core:publish_input.output}}' })
    ).not.toThrow();
    const field = screen.getByDisplayValue('{{core:publish_input.output}}');
    expect(field).toBeTruthy();
  });

  it('coerces a legacy array-shaped inputMapping to an empty string instead of crashing', () => {
    expect(() =>
      renderForm({ subWorkflowId: 'wf-1', subWorkflowInputMapping: [{ id: 'a', key: 'k', value: 'v' }] as any })
    ).not.toThrow();
    // The expression field renders empty rather than trying to .map the array.
    const field = screen.getByPlaceholderText('inputMappingPlaceholder') as HTMLTextAreaElement;
    expect(field.value).toBe('');
  });

  it('edits inputMapping as a single expression string', () => {
    const onUpdate = renderForm({ subWorkflowId: 'wf-1', subWorkflowInputMapping: '' });
    fireEvent.change(screen.getByPlaceholderText('inputMappingPlaceholder'), {
      target: { value: '{{trigger.output.payload}}' },
    });
    expect(onUpdate).toHaveBeenCalledWith(
      expect.objectContaining({ subWorkflowInputMapping: '{{trigger.output.payload}}' })
    );
  });

  it('reads the timeout from subWorkflowTimeoutSeconds (the key the importer/export use)', () => {
    renderForm({ subWorkflowId: 'wf-1', subWorkflowTimeoutSeconds: 600 });
    expect(screen.getByDisplayValue('600')).toBeTruthy();
  });
});
