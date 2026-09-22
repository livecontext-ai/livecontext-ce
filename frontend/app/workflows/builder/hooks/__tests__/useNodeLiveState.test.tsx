// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { useNodeLiveState } from '../useNodeLiveState';
import { StepByStepProvider } from '../../contexts/StepByStepContext';
import type { PendingSignal } from '@/lib/websocket/ws-types';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';

const NODE_ID = 'core:my_wait';

function node(): Node<BuilderNodeData> {
  return {
    id: NODE_ID,
    type: 'waitNode',
    position: { x: 0, y: 0 },
    data: { id: 'wait-1', label: 'My Wait', kind: 'core' } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

function signal(signalType: string): PendingSignal {
  return { id: 1, nodeId: NODE_ID, signalType, status: 'PENDING' };
}

function renderLiveState(
  providerProps: Partial<React.ComponentProps<typeof StepByStepProvider>>,
  options: { isRunMode?: boolean; withNode?: boolean } = {},
) {
  const { isRunMode = true, withNode = true } = options;
  return renderHook(() => useNodeLiveState(withNode ? node() : null, { isRunMode }), {
    wrapper: ({ children }) => (
      <StepByStepProvider
        isEnabled
        isPaused={false}
        readySteps={new Set()}
        completedSteps={new Set()}
        failedSteps={new Set()}
        onExecuteStep={vi.fn()}
        nodeIdToStepId={new Map([[NODE_ID, NODE_ID]])}
        {...providerProps}
      >
        {children}
      </StepByStepProvider>
    ),
  });
}

describe('useNodeLiveState', () => {
  it('reports a node that is executing', () => {
    const { result } = renderLiveState({ runningSteps: new Set([NODE_ID]) });
    expect(result.current.liveState).toBe('running');
    expect(result.current.pendingSignals).toEqual([]);
  });

  it('reports a node parked on an approval, with the signals it is parked on', () => {
    const { result } = renderLiveState({
      awaitingSignalSteps: new Set([NODE_ID]),
      pendingSignals: [signal('USER_APPROVAL')],
    });
    expect(result.current.liveState).toBe('awaiting');
    expect(result.current.pendingSignals.map((s) => s.signalType)).toEqual(['USER_APPROVAL']);
  });

  it('still reports a node parked on a NON-approval signal, without per-signal detail', () => {
    // This hook asks getPendingSignalsForNode for USER_APPROVAL only (it feeds
    // the per-item approval UI). A timer wait must still read as waiting.
    const { result } = renderLiveState({
      awaitingSignalSteps: new Set([NODE_ID]),
      pendingSignals: [signal('WAIT_TIMER')],
    });
    expect(result.current.liveState).toBe('awaiting');
    expect(result.current.pendingSignals).toEqual([]);
  });

  it('prefers AWAITING over RUNNING - yielding never rewrites the running row, so a parked node is in both sets', () => {
    const { result } = renderLiveState({
      runningSteps: new Set([NODE_ID]),
      awaitingSignalSteps: new Set([NODE_ID]),
      pendingSignals: [signal('USER_APPROVAL')],
    });
    expect(result.current.liveState).toBe('awaiting');
    expect(result.current.pendingSignals).toHaveLength(1);
  });

  it('reports nothing for a node that is neither executing nor parked', () => {
    const { result } = renderLiveState({ completedSteps: new Set([NODE_ID]) });
    expect(result.current.liveState).toBeNull();
  });

  it('reports nothing outside run mode, whatever the context says', () => {
    const { result } = renderLiveState({ runningSteps: new Set([NODE_ID]) }, { isRunMode: false });
    expect(result.current.liveState).toBeNull();
  });

  it('reports nothing without a node rather than reading another node\'s state', () => {
    const { result } = renderLiveState({ runningSteps: new Set([NODE_ID]) }, { withNode: false });
    expect(result.current.liveState).toBeNull();
    expect(result.current.pendingSignals).toEqual([]);
  });

  it('reports nothing for a DIFFERENT node that happens to be running', () => {
    const { result } = renderLiveState({ runningSteps: new Set(['core:someone_else']) });
    expect(result.current.liveState).toBeNull();
  });
});
