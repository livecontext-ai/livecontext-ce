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
      providerRetryLabel: 'Provider retry wait (seconds)',
      providerRetryPlaceholder: 'Platform decides',
      providerRetryHelp: 'Leave empty to let the platform wait out a rate limit.',
      providerRetryHelpCededToNode: 'This node retries on its own, so the platform does not.',
      providerRetryHelpBoundedByTimeout: "This node's timeout leaves {seconds}s for the platform.",
      providerRetryHelpCappedByTimeout: "This node's timeout caps it at {seconds}s.",
      providerRetryInfoDefault: 'Empty: the platform waits the delay the provider asked for.',
      providerRetryInfoZero: '0: the call is never re-sent.',
      providerRetryInfoCap: 'A longer wait is reduced to the platform budget.',
      providerRetryInfoRunning: 'The node stays running while it waits.',
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

/**
 * A catalog tool step, which is what the plan emitter counts as one: the exclusions PLUS tool data.
 * Without the tool data a `flowNode` is not emitted as a `mcps` entry at all, so the provider-retry
 * control does not belong on it and the inspector does not offer it.
 */
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
      const header = screen.getByTestId('node-settings-toggle');
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
      const header = screen.getByTestId('node-settings-toggle');
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

  describe('provider retry budget', () => {
    // The setting exists because the platform's retry and the author's MULTIPLY: a node that
    // retries around a call the platform re-sends hits a rate-limited provider harder than a node
    // that does nothing. So the two states that matter are "empty" (the platform decides) and "0"
    // (never re-send), and the field is worthless if the UI cannot tell them apart or misreports
    // which one is in force.
    function providerRetryInput() {
      return screen.getByTestId('node-settings-provider-retry') as HTMLInputElement;
    }

    it('is offered on a catalog tool step', () => {
      renderSection(makeToolNode());
      expandSection();
      expect(providerRetryInput()).toBeTruthy();
    });

    it('is NOT offered on a node the plan emitter would not make a tool step', () => {
      // Same rule as the emitter, including its positive half: a flowNode with no tool data is not
      // a `mcps` entry, so a value set here could never reach one.
      renderSection(makeNode('flowNode', 'action'));
      expandSection();
      expect(screen.queryByTestId('node-settings-provider-retry')).toBeNull();
    });

    it('is NOT offered on a control node', () => {
      renderSection(makeNode('decisionNode', 'decision'));
      expandSection();
      expect(screen.queryByTestId('node-settings-provider-retry')).toBeNull();
    });

    it('starts empty, and says the platform decides', () => {
      renderSection(makeToolNode());
      expandSection();
      expect(providerRetryInput().value).toBe('');
      expect(providerRetryInput().getAttribute('placeholder')).toBe('Platform decides');
    });

    it('writes 0 as a real value, not as "unset"', () => {
      const onUpdate = renderSection(makeToolNode());
      expandSection();

      fireEvent.change(providerRetryInput(), { target: { value: '0' } });

      const updated = onUpdate.mock.calls[0][0] as BuilderNodeData;
      expect(updated.nodePolicy).toEqual({ providerRetryMaxWaitSec: 0 });
    });

    it('writes a positive budget', () => {
      const onUpdate = renderSection(makeToolNode());
      expandSection();

      fireEvent.change(providerRetryInput(), { target: { value: '45' } });

      const updated = onUpdate.mock.calls[0][0] as BuilderNodeData;
      expect(updated.nodePolicy).toEqual({ providerRetryMaxWaitSec: 45 });
    });

    it('clearing the field removes the setting instead of writing 0', () => {
      // Emptying a number input yields '', which the other numeric handlers read as 0. Here 0 is
      // the OPPOSITE of empty, so reusing that handler would turn "let the platform decide" into
      // "never retry" the moment a user cleared the box.
      // No expandSection: a node that carries a policy starts EXPANDED, so clicking the header
      // would collapse it and the field would not be in the document at all.
      const onUpdate = renderSection(makeToolNode({ providerRetryMaxWaitSec: 30 }));

      fireEvent.change(providerRetryInput(), { target: { value: '' } });

      const updated = onUpdate.mock.calls[0][0] as BuilderNodeData;
      expect(updated.nodePolicy).toBeUndefined();
    });

    it('clearing it keeps the other policy fields', () => {
      const onUpdate = renderSection(makeToolNode({ retryCount: 2, providerRetryMaxWaitSec: 30 }));

      fireEvent.change(providerRetryInput(), { target: { value: '' } });

      const updated = onUpdate.mock.calls[0][0] as BuilderNodeData;
      expect(updated.nodePolicy).toEqual({ retryCount: 2 });
    });

    it('editing the retry count PRESERVES a budget already set', () => {
      // The interaction the design turns on: these two fields are read together, and a write that
      // rebuilt the policy from the visible inputs would drop the one the author set deliberately.
      const onUpdate = renderSection(makeToolNode({ providerRetryMaxWaitSec: 45 }));

      fireEvent.change(screen.getByTestId('node-settings-retry-count'), { target: { value: '3' } });

      const updated = onUpdate.mock.calls[0][0] as BuilderNodeData;
      expect(updated.nodePolicy).toEqual({ retryCount: 3, providerRetryMaxWaitSec: 45 });
    });

    it('editing the timeout preserves it too', () => {
      const onUpdate = renderSection(makeToolNode({ providerRetryMaxWaitSec: 0 }));

      fireEvent.change(screen.getByTestId('node-settings-timeout'), { target: { value: '5000' } });

      const updated = onUpdate.mock.calls[0][0] as BuilderNodeData;
      expect(updated.nodePolicy).toEqual({ timeoutMs: 5000, providerRetryMaxWaitSec: 0 });
    });

    it('a node that retries itself is told the platform has already stood down', () => {
      // Otherwise an author reads an empty field and assumes the platform is still retrying
      // underneath their own retry, which is the multiplication this feature prevents.
      renderSection(makeToolNode({ retryCount: 2 }));

      expect(screen.getByText('This node retries on its own, so the platform does not.')).toBeTruthy();
      expect(providerRetryInput().getAttribute('placeholder')).toBe('0');
    });

    it('a node with its OWN timeout is told what that leaves, not "platform decides"', () => {
      // A 3s per-attempt timeout leaves 1s. Saying "the platform decides" there was false in the
      // expensive direction: the author would believe a 5s Retry-After gets waited out when the
      // attempt will be abandoned first.
      renderSection(makeToolNode({ timeoutMs: 3000 }));

      expect(screen.getByText("This node's timeout leaves 1s for the platform.")).toBeTruthy();
      expect(providerRetryInput().getAttribute('placeholder')).toBe('1');
    });

    it('a timeout too short for any wait says 0, the state the field exists to make explicit', () => {
      renderSection(makeToolNode({ timeoutMs: 1000 }));

      expect(providerRetryInput().getAttribute('placeholder')).toBe('0');
    });

    it('an explicit budget overrides the ceded-to-node implication', () => {
      renderSection(makeToolNode({ retryCount: 2, providerRetryMaxWaitSec: 45 }));

      expect(screen.getByText('Leave empty to let the platform wait out a rate limit.')).toBeTruthy();
      expect(providerRetryInput().value).toBe('45');
    });

    it('an explicit budget the node timeout will CAP says so, instead of showing a number that '
      + 'will not be used', () => {
      // The state the backend bound exists for, and the one the UI could not express: 45 typed,
      // 1 applied. Showing 45 with the generic help is how a correct guard reads as a broken one -
      // the author believes a 5s Retry-After gets waited out and the attempt is abandoned first.
      renderSection(makeToolNode({ timeoutMs: 3000, providerRetryMaxWaitSec: 45 }));

      expect(providerRetryInput().value).toBe('45');
      expect(screen.getByText("This node's timeout caps it at 1s.")).toBeTruthy();
    });

    it('an explicit budget UNDER the ceiling is not reported as capped', () => {
      renderSection(makeToolNode({ timeoutMs: 30000, providerRetryMaxWaitSec: 5 }));

      expect(screen.getByText('Leave empty to let the platform wait out a rate limit.')).toBeTruthy();
    });

    it('is read-only in run mode, like every other setting', () => {
      const onUpdate = renderSection(makeToolNode({ providerRetryMaxWaitSec: 0 }), vi.fn(), true);

      expect(providerRetryInput().readOnly).toBe(true);
      fireEvent.change(providerRetryInput(), { target: { value: '9' } });
      expect(onUpdate).not.toHaveBeenCalled();
    });

    it('a negative entry changes nothing on a node that already has a budget', () => {
      // A minus sign is a typo, not a decision. The node must already carry a policy for this to
      // mean anything: on an empty node no write happens either way, so the guard would be
      // untested. Reading '-3' as 0 would silently switch the platform retry off.
      const onUpdate = renderSection(makeToolNode({ providerRetryMaxWaitSec: 45 }));

      fireEvent.change(providerRetryInput(), { target: { value: '-3' } });

      expect(onUpdate).not.toHaveBeenCalled();
    });

    it('a FRACTION does not become 0, which parseInt would have made it', () => {
      // parseInt('0.5') is 0, and 0 on this field is not "under a second" - it is the switch that
      // turns the platform retry off. A number input does not stop a fraction being typed.
      const onUpdate = renderSection(makeToolNode({ providerRetryMaxWaitSec: 45 }));

      fireEvent.change(providerRetryInput(), { target: { value: '0.5' } });

      expect(onUpdate).not.toHaveBeenCalled();
    });

    it('explains what the states mean, since none of them is guessable', () => {
      renderSection(makeToolNode());
      expandSection();

      fireEvent.click(screen.getByTestId('node-settings-provider-retry-info'));

      expect(screen.getByText('Empty: the platform waits the delay the provider asked for.')).toBeTruthy();
      expect(screen.getByText('0: the call is never re-sent.')).toBeTruthy();
      expect(screen.getByText('A longer wait is reduced to the platform budget.')).toBeTruthy();
      expect(screen.getByText('The node stays running while it waits.')).toBeTruthy();
    });

    it('a budget stored on a node that shows no such field is not counted in the badge', () => {
      // Only reachable from a plan written before the tool actions began refusing it. Counting it
      // produced "Settings (1)" on an auto-expanded section whose every visible control sat at its
      // default, with nothing to explain the 1 and no way to clear it.
      renderSection(makeNode('decisionNode', 'decision', { providerRetryMaxWaitSec: 0 }));

      expect(screen.getByTestId('node-settings-toggle').textContent).not.toContain('(');
    });
  });
});
