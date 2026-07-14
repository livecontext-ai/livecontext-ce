// @vitest-environment jsdom
import * as React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import type { AbstractIntlMessages } from 'use-intl';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { MockOutputSection } from '../MockOutputSection';

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
    },
  },
};

function decisionNode(): Node<BuilderNodeData> {
  const id = 'decision-guard-1';
  return {
    id,
    type: 'decisionNode',
    position: { x: 0, y: 0 },
    data: {
      id,
      label: 'Check Urgent',
      kind: 'decision',
      decisionConditions: [
        { id: 'c-if', type: 'if', label: 'If' },
        { id: 'c-else', type: 'else', label: 'Else' },
      ],
    } as BuilderNodeData,
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

describe('MockOutputSection static-mock port guard', () => {
  // Backend parse rule: a static mock on a branching node MUST select a branch,
  // otherwise every editor run fails at parse time. The section must therefore
  // never commit a port-less static mock on a decision node.
  it('commits a port-less static mock WITH the first branch on blur (decision node)', () => {
    const node = decisionNode();
    // A port-less static mock (e.g. authored by an older build or hand-edited).
    (node.data as BuilderNodeData & { mock?: unknown }).mock = { output: { verdict: true } };
    const onUpdate = renderSection(node);

    // The section starts OPEN when a mock is already configured.
    const textarea = screen.getByTestId('mock-json-textarea');
    fireEvent.change(textarea, { target: { value: '{"verdict": false}' } });
    fireEvent.blur(textarea);

    expect(onUpdate).toHaveBeenCalledTimes(1);
    const updated = onUpdate.mock.calls[0][0] as BuilderNodeData & { mock?: { output?: unknown; port?: string } };
    expect(updated.mock?.output).toEqual({ verdict: false });
    // The guard defaulted the decision's first branch.
    expect(updated.mock?.port).toBe('if');
  });

  it('keeps an already-selected branch on re-commit', () => {
    const node = decisionNode();
    (node.data as BuilderNodeData & { mock?: unknown }).mock = {
      output: { verdict: true },
      port: 'else',
    };
    const onUpdate = renderSection(node);

    // The section starts OPEN when a mock is already configured.
    const textarea = screen.getByTestId('mock-json-textarea');
    fireEvent.change(textarea, { target: { value: '{"verdict": false}' } });
    fireEvent.blur(textarea);

    const updated = onUpdate.mock.calls[0][0] as BuilderNodeData & { mock?: { port?: string } };
    expect(updated.mock?.port).toBe('else');
  });

  it('does not add a port when committing custom JSON on a non-branching mcp node', () => {
    const id = 'mcp-guard-1';
    const node: Node<BuilderNodeData> = {
      id,
      type: 'flowNode',
      position: { x: 0, y: 0 },
      data: {
        id,
        label: 'Send Email',
        kind: 'action',
        toolData: { toolId: 'gmail/send_email', toolSlug: 'send_email' },
        mock: { output: { sent: true } },
      } as unknown as BuilderNodeData,
    };
    const onUpdate = renderSection(node);

    // The section starts OPEN when a mock is already configured.
    const textarea = screen.getByTestId('mock-json-textarea');
    fireEvent.change(textarea, { target: { value: '{"sent": false}' } });
    fireEvent.blur(textarea);

    const updated = onUpdate.mock.calls[0][0] as BuilderNodeData & { mock?: { port?: string } };
    expect(updated.mock?.port).toBeUndefined();
  });

  it('rejects invalid JSON without committing anything', () => {
    const node = decisionNode();
    (node.data as BuilderNodeData & { mock?: unknown }).mock = { output: { verdict: true } };
    const onUpdate = renderSection(node);

    // The section starts OPEN when a mock is already configured.
    const textarea = screen.getByTestId('mock-json-textarea');
    fireEvent.change(textarea, { target: { value: '{not json' } });
    fireEvent.blur(textarea);

    expect(screen.getByTestId('mock-json-error')).toBeTruthy();
    expect(onUpdate).not.toHaveBeenCalled();
  });
});
