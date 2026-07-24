// @vitest-environment jsdom
import * as React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import type { AbstractIntlMessages } from 'use-intl';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData, NodeMock } from '../../../types';
import { MockOutputSection, parseDurationSecondsToMs } from '../MockOutputSection';
import { MOCK_MAX_DURATION_MS } from '../../../utils/nodeMock';

// Heavy collaborators not under test.
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getToolResponses: vi.fn().mockResolvedValue([]) },
}));
vi.mock('../../../hooks/useNodeDefinitions', () => ({
  useNodeDefinitions: () => ({ getOutputSchema: () => [] }),
}));
vi.mock('../SourceCoreNodeInspector', () => ({
  getCoreNodeSchema: () => [],
}));

const messages: AbstractIntlMessages = {
  workflowBuilder: {
    mock: {
      title: 'Mock output',
      toggleHelp: 'Editor runs return the configured mock instead of executing this node.',
      sourceLabel: 'Mock source',
      sourceCustom: 'Custom JSON',
      sourceCatalogExample: 'Catalog example',
      sourceError: 'Simulated error',
      customJsonLabel: 'Output JSON',
      customJsonHelp: 'Used as this node output when the workflow runs from the editor.',
      invalidJson: 'Invalid JSON',
      catalogExampleHelp: 'Serves this tool default example response.',
      previewExample: 'Preview example',
      previewProjectionNote: 'Preview of the stored example.',
      resetToExample: 'Reset to example',
      portLabel: 'Branch to take',
      errorMessageLabel: 'Error message',
      durationLabel: 'Simulated duration',
      durationUnit: 'seconds',
      durationHelp: 'The node takes this long before returning the mock.',
    },
  },
};

function mcpNode(mock?: NodeMock): Node<BuilderNodeData> {
  const id = 'mcp-duration-1';
  return {
    id,
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id,
      label: 'Send Email',
      kind: 'action',
      toolData: { toolId: 'gmail/send_email', toolSlug: 'send_email' },
      ...(mock ? { mock } : {}),
    } as unknown as BuilderNodeData,
  };
}

function renderSection(node: Node<BuilderNodeData>, onUpdate = vi.fn()) {
  render(
    <NextIntlClientProvider locale="en" messages={messages}>
      <MockOutputSection node={node} data={node.data} onUpdate={onUpdate} />
    </NextIntlClientProvider>
  );
  return onUpdate;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('parseDurationSecondsToMs', () => {
  it('converts seconds (decimals allowed) to rounded milliseconds', () => {
    expect(parseDurationSecondsToMs('90')).toBe(90000);
    expect(parseDurationSecondsToMs('1.5')).toBe(1500);
    expect(parseDurationSecondsToMs(' 2 ')).toBe(2000);
  });

  it('returns undefined for empty / non-numeric / non-positive input', () => {
    expect(parseDurationSecondsToMs('')).toBeUndefined();
    expect(parseDurationSecondsToMs('   ')).toBeUndefined();
    expect(parseDurationSecondsToMs('fast')).toBeUndefined();
    expect(parseDurationSecondsToMs('0')).toBeUndefined();
    expect(parseDurationSecondsToMs('-3')).toBeUndefined();
  });

  it('clamps beyond the 10-minute backend cap', () => {
    expect(parseDurationSecondsToMs('601')).toBe(MOCK_MAX_DURATION_MS);
    expect(parseDurationSecondsToMs('99999')).toBe(MOCK_MAX_DURATION_MS);
  });
});

describe('MockOutputSection simulated duration', () => {
  it('shows the committed duration in seconds', () => {
    renderSection(mcpNode({ output: { sent: true }, durationMs: 90000 }));

    const input = screen.getByTestId('mock-duration-input') as HTMLInputElement;
    expect(input.value).toBe('90');
  });

  it('commits durationMs on blur when a mock is already configured', () => {
    const onUpdate = renderSection(mcpNode({ output: { sent: true } }));

    const input = screen.getByTestId('mock-duration-input');
    fireEvent.change(input, { target: { value: '12.5' } });
    fireEvent.blur(input);

    expect(onUpdate).toHaveBeenCalledTimes(1);
    const updated = onUpdate.mock.calls[0][0] as BuilderNodeData & { mock?: NodeMock };
    expect(updated.mock).toEqual({ output: { sent: true }, durationMs: 12500 });
  });

  it('clearing the input removes durationMs from the committed mock', () => {
    const onUpdate = renderSection(mcpNode({ output: { sent: true }, durationMs: 5000 }));

    const input = screen.getByTestId('mock-duration-input');
    fireEvent.change(input, { target: { value: '' } });
    fireEvent.blur(input);

    expect(onUpdate).toHaveBeenCalledTimes(1);
    const updated = onUpdate.mock.calls[0][0] as BuilderNodeData & { mock?: NodeMock };
    expect(updated.mock).toEqual({ output: { sent: true } });
  });

  it('a duration typed BEFORE the first commit rides the JSON commit (never lost)', () => {
    // No mock configured yet: open the section, type a duration, then commit JSON.
    const node = mcpNode();
    const onUpdate = renderSection(node);

    fireEvent.click(screen.getByRole('switch'));
    const durationInput = screen.getByTestId('mock-duration-input');
    fireEvent.change(durationInput, { target: { value: '30' } });
    fireEvent.blur(durationInput); // no committed mock yet - stays pending

    expect(onUpdate).not.toHaveBeenCalled();

    const textarea = screen.getByTestId('mock-json-textarea');
    fireEvent.change(textarea, { target: { value: '{"sent": true}' } });
    fireEvent.blur(textarea);

    expect(onUpdate).toHaveBeenCalledTimes(1);
    const updated = onUpdate.mock.calls[0][0] as BuilderNodeData & { mock?: NodeMock };
    expect(updated.mock).toEqual({ output: { sent: true }, durationMs: 30000 });
  });

  it('a pending duration rides the error-message commit too', () => {
    const node = mcpNode();
    const onUpdate = renderSection(node);

    fireEvent.click(screen.getByRole('switch'));
    // Switch to the simulated-error source.
    fireEvent.click(screen.getByTestId('mock-source-select'));
    fireEvent.click(screen.getByText('Simulated error'));

    const durationInput = screen.getByTestId('mock-duration-input');
    fireEvent.change(durationInput, { target: { value: '5' } });
    fireEvent.blur(durationInput);

    const errorInput = screen.getByTestId('mock-error-message');
    fireEvent.change(errorInput, { target: { value: 'Rate limit exceeded' } });
    fireEvent.blur(errorInput);

    expect(onUpdate).toHaveBeenCalledTimes(1);
    const updated = onUpdate.mock.calls[0][0] as BuilderNodeData & { mock?: NodeMock };
    expect(updated.mock).toEqual({
      error: { message: 'Rate limit exceeded' },
      durationMs: 5000,
    });
  });
});
