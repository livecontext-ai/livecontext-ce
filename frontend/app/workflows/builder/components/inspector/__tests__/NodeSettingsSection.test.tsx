// @vitest-environment jsdom
import * as React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import type { AbstractIntlMessages } from 'use-intl';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData, NodePolicy } from '../../../types';
import { NodeSettingsSection } from '../NodeSettingsSection';

// Heavy MockOutputSection collaborators not under test here.
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
    nodeSettings: {
      title: 'Settings',
      retryCountLabel: 'Retry on fail',
      retryCountHelp: 'Extra attempts after a failure (0-10). 0 = no retry.',
      retryBackoffLabel: 'Retry backoff (ms)',
      retryBackoffHelp: 'Delay between attempts, in milliseconds.',
      continueOnFailureLabel: 'Continue on fail',
      continueOnFailureHelp: 'If every attempt fails, keep running the next nodes instead of skipping them.',
      continueOnFailureBlockedTooltip: 'Not available on branching nodes: continuing would run all branches at once.',
      timeoutLabel: 'Timeout (ms)',
      timeoutHelp: 'Best effort, applies per attempt. 0 = disabled.',
      executeOnceLabel: 'Execute once',
      executeOnceHelp: 'In a split, run for the first item only.',
      executeOnceBlockedTooltip: 'Not available on split, aggregate, merge or loop nodes.',
    },
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
      clear: 'Remove mock',
    },
  },
};

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
  fireEvent.click(screen.getByRole('button', { name: /Settings/ }));
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('NodeSettingsSection', () => {
  it('renders collapsed by default for a plain node without a policy', () => {
    renderSection(makeNode('flowNode', 'action'));
    expect(screen.getByTestId('node-settings-section')).toBeTruthy();
    expect(screen.queryByTestId('node-settings-retry-count')).toBeNull();
  });

  it('expands and shows all fields; backoff stays hidden while retryCount is 0', () => {
    renderSection(makeNode('flowNode', 'action'));
    expandSection();
    expect(screen.getByTestId('node-settings-retry-count')).toBeTruthy();
    expect(screen.queryByTestId('node-settings-retry-backoff')).toBeNull();
    expect(screen.getByTestId('node-settings-continue-on-failure')).toBeTruthy();
    expect(screen.getByTestId('node-settings-timeout')).toBeTruthy();
    expect(screen.getByTestId('node-settings-execute-once')).toBeTruthy();
  });

  it('starts expanded when the node already carries a policy, and shows backoff with retries on', () => {
    renderSection(makeNode('flowNode', 'action', { retryCount: 2, retryBackoffMs: 1500 }));
    expect(screen.getByTestId('node-settings-retry-count')).toBeTruthy();
    const backoff = screen.getByTestId('node-settings-retry-backoff') as HTMLInputElement;
    expect(backoff.value).toBe('1500');
  });

  it('writes a minimal nodePolicy object when retry count is set', () => {
    const onUpdate = renderSection(makeNode('flowNode', 'action'));
    expandSection();
    fireEvent.change(screen.getByTestId('node-settings-retry-count'), { target: { value: '3' } });
    expect(onUpdate).toHaveBeenCalledTimes(1);
    expect(onUpdate.mock.calls[0][0].nodePolicy).toEqual({ retryCount: 3 });
  });

  it('clamps the retry count to the 0-10 stepper bound', () => {
    const onUpdate = renderSection(makeNode('flowNode', 'action'));
    expandSection();
    fireEvent.change(screen.getByTestId('node-settings-retry-count'), { target: { value: '99' } });
    expect(onUpdate.mock.calls[0][0].nodePolicy).toEqual({ retryCount: 10 });
  });

  it('removes the nodePolicy key entirely when the last field returns to default', () => {
    const onUpdate = renderSection(makeNode('flowNode', 'action', { retryCount: 2 }));
    fireEvent.change(screen.getByTestId('node-settings-retry-count'), { target: { value: '0' } });
    expect(onUpdate).toHaveBeenCalledTimes(1);
    expect(onUpdate.mock.calls[0][0]).not.toHaveProperty('nodePolicy');
  });

  it('drops the backoff together with the retry count (no stale value in the plan)', () => {
    const onUpdate = renderSection(
      makeNode('flowNode', 'action', { retryCount: 2, retryBackoffMs: 1500, timeoutMs: 9000 })
    );
    fireEvent.change(screen.getByTestId('node-settings-retry-count'), { target: { value: '' } });
    expect(onUpdate.mock.calls[0][0].nodePolicy).toEqual({ timeoutMs: 9000 });
  });

  it('writes timeoutMs and executeOnce on a plain node', () => {
    const onUpdate = renderSection(makeNode('flowNode', 'action'));
    expandSection();
    fireEvent.change(screen.getByTestId('node-settings-timeout'), { target: { value: '30000' } });
    expect(onUpdate.mock.calls[0][0].nodePolicy).toEqual({ timeoutMs: 30000 });

    fireEvent.click(screen.getByRole('switch', { name: 'Execute once' }));
    expect(onUpdate.mock.calls[1][0].nodePolicy).toEqual({ executeOnce: true });
  });

  it('toggles continueOnFailure on a plain node', () => {
    const onUpdate = renderSection(makeNode('flowNode', 'action'));
    expandSection();
    fireEvent.click(screen.getByRole('switch', { name: 'Continue on fail' }));
    expect(onUpdate.mock.calls[0][0].nodePolicy).toEqual({ continueOnFailure: true });
  });

  describe('gating matrix', () => {
    it.each([
      ['decision', 'decisionNode', 'decision'],
      ['switch', 'switchNode', 'switch'],
      ['option', 'optionNode', 'option'],
    ])('disables Continue on fail with the blocked tooltip on %s nodes', (_label, type, kind) => {
      const onUpdate = renderSection(makeNode(type, kind));
      expandSection();
      const toggle = screen.getByRole('switch', { name: 'Continue on fail' }) as HTMLButtonElement;
      expect(toggle.disabled).toBe(true);
      // Blocked reason surfaced (tooltip wrapper + helper text)
      expect(screen.getByTestId('node-settings-continue-on-failure-blocked')).toBeTruthy();
      expect(
        screen.getAllByText(/Not available on branching nodes/).length
      ).toBeGreaterThan(0);
      fireEvent.click(toggle);
      expect(onUpdate).not.toHaveBeenCalled();
      // Execute once stays available on branching nodes
      const executeOnce = screen.getByRole('switch', { name: 'Execute once' }) as HTMLButtonElement;
      expect(executeOnce.disabled).toBe(false);
    });

    it.each([
      ['split', 'splitNode', 'split'],
      ['aggregate', 'aggregateNode', 'aggregate'],
      ['merge', 'mergeNode', 'merge'],
      ['loop', 'whileGroupNode', 'loop'],
    ])('disables Execute once with the blocked tooltip on %s nodes', (_label, type, kind) => {
      const onUpdate = renderSection(makeNode(type, kind));
      expandSection();
      const toggle = screen.getByRole('switch', { name: 'Execute once' }) as HTMLButtonElement;
      expect(toggle.disabled).toBe(true);
      expect(screen.getByTestId('node-settings-execute-once-blocked')).toBeTruthy();
      fireEvent.click(toggle);
      expect(onUpdate).not.toHaveBeenCalled();
      // Continue on fail stays available on split coordinators
      const continueOnFail = screen.getByRole('switch', { name: 'Continue on fail' }) as HTMLButtonElement;
      expect(continueOnFail.disabled).toBe(false);
    });
  });

  it('is read-only in run mode (inputs readonly, toggles disabled, no writes)', () => {
    const onUpdate = renderSection(
      makeNode('flowNode', 'action', { retryCount: 1 }),
      vi.fn(),
      true
    );
    const retry = screen.getByTestId('node-settings-retry-count') as HTMLInputElement;
    expect(retry.readOnly).toBe(true);
    const toggle = screen.getByRole('switch', { name: 'Continue on fail' }) as HTMLButtonElement;
    expect(toggle.disabled).toBe(true);
    fireEvent.change(retry, { target: { value: '5' } });
    expect(onUpdate).not.toHaveBeenCalled();
  });

  describe('nested Mock output block', () => {
    function withMock(node: Node<BuilderNodeData>, mock: unknown): Node<BuilderNodeData> {
      (node.data as BuilderNodeData & { mock?: unknown }).mock = mock;
      return node;
    }

    it('renders the Mock output row inside the expanded section, configuration hidden while off', () => {
      renderSection(makeNode('flowNode', 'action'));
      expandSection();
      expect(screen.getByTestId('mock-output-section')).toBeTruthy();
      const toggle = screen.getByRole('switch', { name: 'Mock output' }) as HTMLButtonElement;
      expect(toggle.getAttribute('aria-checked')).toBe('false');
      expect(screen.queryByTestId('mock-source-select')).toBeNull();
    });

    it('does not render the Mock output row on a mock-blocked split node', () => {
      renderSection(makeNode('splitNode', 'split'));
      expandSection();
      expect(screen.queryByTestId('mock-output-section')).toBeNull();
    });

    it('reveals the configuration when toggled on, without committing anything', () => {
      const onUpdate = renderSection(makeNode('flowNode', 'action'));
      expandSection();
      fireEvent.click(screen.getByRole('switch', { name: 'Mock output' }));
      expect(screen.getByTestId('mock-source-select')).toBeTruthy();
      // Nothing is written until the user commits a source (JSON blur / select).
      expect(onUpdate).not.toHaveBeenCalled();
    });

    it('starts expanded with the badge counting an enabled mock, and disables it on toggle off', () => {
      const node = withMock(makeNode('flowNode', 'action'), { output: { ok: true } });
      const onUpdate = renderSection(node);
      // Active mock = section open without clicking, counted in the header badge.
      const header = screen.getByRole('button', { name: /Settings/ });
      expect(header.textContent).toContain('(1)');
      const toggle = screen.getByRole('switch', { name: 'Mock output' }) as HTMLButtonElement;
      expect(toggle.getAttribute('aria-checked')).toBe('true');

      fireEvent.click(toggle);
      expect(onUpdate).toHaveBeenCalledTimes(1);
      const updated = onUpdate.mock.calls[0][0] as BuilderNodeData & { mock?: { enabled?: boolean } };
      expect(updated.mock).toEqual({ output: { ok: true }, enabled: false });
      // Configuration hides with the switch.
      expect(screen.queryByTestId('mock-source-select')).toBeNull();
    });

    it('re-enables a disabled mock when toggled back on', () => {
      const node = withMock(makeNode('flowNode', 'action'), {
        output: { ok: true },
        enabled: false,
      });
      const onUpdate = renderSection(node);
      expandSection();
      const toggle = screen.getByRole('switch', { name: 'Mock output' }) as HTMLButtonElement;
      expect(toggle.getAttribute('aria-checked')).toBe('false');

      fireEvent.click(toggle);
      const updated = onUpdate.mock.calls[0][0] as BuilderNodeData & { mock?: { enabled?: boolean } };
      // enabled true is the default and stays omitted (plan-clean shape).
      expect(updated.mock).toEqual({ output: { ok: true } });
    });

    it('has no separate Remove action: the switch is the single mock control', () => {
      const node = withMock(makeNode('flowNode', 'action'), { output: { ok: true } });
      renderSection(node);
      expect(screen.queryByTestId('mock-remove')).toBeNull();
      expect(screen.queryByText('Remove mock')).toBeNull();
    });

    it('keeps the mock switch disabled in run mode', () => {
      const node = withMock(makeNode('flowNode', 'action'), { output: { ok: true } });
      const onUpdate = renderSection(node, vi.fn(), true);
      const toggle = screen.getByRole('switch', { name: 'Mock output' }) as HTMLButtonElement;
      expect(toggle.disabled).toBe(true);
      fireEvent.click(toggle);
      expect(onUpdate).not.toHaveBeenCalled();
    });

    it('keeps a DISABLED mock inspectable in run mode (config shown, switch off and frozen)', () => {
      const node = withMock(makeNode('flowNode', 'action'), {
        output: { ok: true },
        enabled: false,
      });
      renderSection(node, vi.fn(), true);
      expandSection();
      const toggle = screen.getByRole('switch', { name: 'Mock output' }) as HTMLButtonElement;
      expect(toggle.getAttribute('aria-checked')).toBe('false');
      expect(toggle.disabled).toBe(true);
      // Read-only inspection: the configuration stays reachable.
      expect(screen.getByTestId('mock-source-select')).toBeTruthy();
    });

    it('does not write anything when toggled on then off before any commit', () => {
      const onUpdate = renderSection(makeNode('flowNode', 'action'));
      expandSection();
      const toggle = screen.getByRole('switch', { name: 'Mock output' });
      fireEvent.click(toggle); // on: reveal only
      fireEvent.click(toggle); // off again: still nothing committed
      expect(onUpdate).not.toHaveBeenCalled();
      expect(screen.queryByTestId('mock-source-select')).toBeNull();
    });

    it('excludes a disabled mock from the header badge and the auto-open', () => {
      const node = withMock(makeNode('flowNode', 'action'), {
        output: { ok: true },
        enabled: false,
      });
      renderSection(node);
      const header = screen.getByRole('button', { name: /Settings/ });
      expect(header.textContent).not.toContain('(');
      // Disabled mock = section starts collapsed like a policy-less node.
      expect(screen.queryByTestId('mock-output-section')).toBeNull();
    });

    it('reseeds the switch when the mock changes externally (Use as mock output / removal)', () => {
      const node = makeNode('flowNode', 'action');
      const onUpdate = vi.fn();
      const view = render(
        <NextIntlClientProvider locale="en" messages={messages}>
          <NodeSettingsSection node={node} data={node.data} onUpdate={onUpdate} isRunMode={false} />
        </NextIntlClientProvider>
      );
      expandSection();
      const toggle = () => screen.getByRole('switch', { name: 'Mock output' });
      expect(toggle().getAttribute('aria-checked')).toBe('false');

      // External write (e.g. "Use as mock output" from the run panel).
      const withExternalMock = { ...node.data, mock: { output: { copied: true } } };
      view.rerender(
        <NextIntlClientProvider locale="en" messages={messages}>
          <NodeSettingsSection node={node} data={withExternalMock} onUpdate={onUpdate} isRunMode={false} />
        </NextIntlClientProvider>
      );
      expect(toggle().getAttribute('aria-checked')).toBe('true');
      expect(screen.getByTestId('mock-source-select')).toBeTruthy();

      // External removal flips it back off.
      view.rerender(
        <NextIntlClientProvider locale="en" messages={messages}>
          <NodeSettingsSection node={node} data={node.data} onUpdate={onUpdate} isRunMode={false} />
        </NextIntlClientProvider>
      );
      expect(toggle().getAttribute('aria-checked')).toBe('false');
    });

    it('reseeds the switch from the newly selected node when the inspected node changes', () => {
      const bare = makeNode('flowNode', 'action', undefined, 'action-reseed-a');
      const onUpdate = vi.fn();
      const view = render(
        <NextIntlClientProvider locale="en" messages={messages}>
          <NodeSettingsSection node={bare} data={bare.data} onUpdate={onUpdate} isRunMode={false} />
        </NextIntlClientProvider>
      );
      expandSection();
      const toggle = () => screen.getByRole('switch', { name: 'Mock output' });
      // Locally revealed on the first node (nothing committed).
      fireEvent.click(toggle());
      expect(toggle().getAttribute('aria-checked')).toBe('true');

      // Switching to a mock-less node must NOT keep the previous node's local ON.
      const other = makeNode('flowNode', 'action', undefined, 'action-reseed-b');
      view.rerender(
        <NextIntlClientProvider locale="en" messages={messages}>
          <NodeSettingsSection node={other} data={other.data} onUpdate={onUpdate} isRunMode={false} />
        </NextIntlClientProvider>
      );
      expect(toggle().getAttribute('aria-checked')).toBe('false');
    });
  });
});
