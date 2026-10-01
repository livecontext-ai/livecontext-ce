// @vitest-environment jsdom
import * as React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import type { AbstractIntlMessages } from 'use-intl';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData, NodePolicy } from '../../../types';
import { NodeSettingsSection } from '../NodeSettingsSection';
import enMessages from '@/messages/en.json';

// retryOn picker. The Radix select is reduced to a native one: what is under test is what the
// section writes for a choice, not how the select draws it.
vi.mock('@/components/ui/select', () => ({
  Select: ({ children, value, onValueChange, disabled }: any) => (
    <select
      data-testid="native-select"
      value={value}
      disabled={disabled}
      onChange={(e) => onValueChange(e.target.value)}
    >
      {children}
    </select>
  ),
  SelectContent: ({ children }: any) => <>{children}</>,
  SelectItem: ({ children, value }: any) => <option value={value}>{children}</option>,
  SelectTrigger: () => null,
  SelectValue: () => null,
}));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getToolResponses: vi.fn().mockResolvedValue([]) },
}));
vi.mock('../../../hooks/useNodeDefinitions', () => ({
  useNodeDefinitions: () => ({ getOutputSchema: () => [] }),
}));
vi.mock('../SourceCoreNodeInspector', () => ({
  getCoreNodeSchema: () => [],
}));

// The REAL English copy, so a test never pins a string the product no longer shows.
const messages: AbstractIntlMessages = {
  workflowBuilder: (enMessages as { workflowBuilder: AbstractIntlMessages }).workflowBuilder,
};
const RETRY_ON_LABEL = String(
  ((enMessages as any).workflowBuilder.nodeSettings as Record<string, string>).retryOnLabel
);

function makeNode(
  type: string,
  kind: string,
  nodePolicy?: NodePolicy,
  id = `${kind}-test-1`
): Node<BuilderNodeData> {
  return {
    id,
    type,
    position: { x: 0, y: 0 },
    data: { id, label: 'Test Node', kind, ...(nodePolicy ? { nodePolicy } : {}) } as BuilderNodeData,
  };
}

/** A catalog tool step, which is what the plan emitter counts as one: the exclusions PLUS tool data. */
function makeToolNode(nodePolicy?: NodePolicy, id = 'tool-test-1'): Node<BuilderNodeData> {
  const node = makeNode('flowNode', 'action', nodePolicy, id);
  // The two fields toolData actually requires, so this is a shape the builder can really hold.
  return { ...node, data: { ...node.data, toolData: { apiName: 'Slack', method: 'POST' } } };
}

function renderSection(
  node: Node<BuilderNodeData>,
  onUpdate = vi.fn(),
  isRunMode = false
) {
  render(
    <NextIntlClientProvider locale="en" messages={messages}>
      <NodeSettingsSection node={node} data={node.data} onUpdate={onUpdate} isRunMode={isRunMode} />
    </NextIntlClientProvider>
  );
  return onUpdate;
}

function expandSection() {
  fireEvent.click(screen.getByTestId('node-settings-toggle'));
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('NodeSettingsSection - retryOn and the backoff cap', () => {
  it('hides the retryOn picker while retryCount is 0', () => {
    renderSection(makeToolNode());
    expandSection();
    expect(screen.queryByText(RETRY_ON_LABEL)).toBeNull();
  });

  it('hides the retryOn picker on a node that is not a tool step, even with retries', () => {
    renderSection(makeNode('flowNode', 'action', { retryCount: 2 }));
    expect(screen.queryByText(RETRY_ON_LABEL)).toBeNull();
  });

  it('shows the picker on a tool step with retries, on the default classification', () => {
    renderSection(makeToolNode({ retryCount: 2 }));
    expect(screen.getByText(RETRY_ON_LABEL)).toBeTruthy();
    expect((screen.getByTestId('native-select') as HTMLSelectElement).value).toBe('default');
  });

  it('writes retryOn=rate_limit when the second option is chosen', () => {
    const onUpdate = renderSection(makeToolNode({ retryCount: 2 }));
    fireEvent.change(screen.getByTestId('native-select'), { target: { value: 'rate_limit' } });
    expect(onUpdate.mock.calls[0][0].nodePolicy).toEqual({ retryCount: 2, retryOn: 'rate_limit' });
  });

  it('removes retryOn when the default option is chosen again', () => {
    const onUpdate = renderSection(makeToolNode({ retryCount: 2, retryOn: 'rate_limit' }));
    fireEvent.change(screen.getByTestId('native-select'), { target: { value: 'default' } });
    expect(onUpdate.mock.calls[0][0].nodePolicy).toEqual({ retryCount: 2 });
  });

  it('drops retryOn together with the retry count (no stale value in the plan)', () => {
    const onUpdate = renderSection(makeToolNode({ retryCount: 2, retryOn: 'rate_limit', timeoutMs: 9000 }));
    fireEvent.change(screen.getByTestId('node-settings-retry-count'), { target: { value: '0' } });
    expect(onUpdate.mock.calls[0][0].nodePolicy).toEqual({ timeoutMs: 9000 });
  });

  it('clamps the backoff to 60000 ms', () => {
    const onUpdate = renderSection(makeToolNode({ retryCount: 1 }));
    fireEvent.change(screen.getByTestId('node-settings-retry-backoff'), { target: { value: '999999' } });
    expect(onUpdate.mock.calls[0][0].nodePolicy).toEqual({ retryCount: 1, retryBackoffMs: 60000 });
  });

  it('keeps the picker disabled in run mode', () => {
    renderSection(makeToolNode({ retryCount: 1 }), vi.fn(), true);
    expect((screen.getByTestId('native-select') as HTMLSelectElement).disabled).toBe(true);
  });
});
