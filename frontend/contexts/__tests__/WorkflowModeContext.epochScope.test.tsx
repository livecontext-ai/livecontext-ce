/**
 * @vitest-environment jsdom
 *
 * Regression test: the cross-tree `viewingEpochChanged` window event must be
 * scoped by runId. The multi-app side panel keeps every opened app tab mounted
 * at once (keepMounted), so each app's WorkflowModeProvider is alive
 * simultaneously. Changing the epoch on run A must NOT move run B.
 *
 * Pre-fix the event carried only `{ epoch }` and every mounted provider adopted
 * it globally, slaving the two apps' epoch selectors together (the user-reported
 * "système partagé" bug: two apps launched at the same epoch). This test fails
 * on the pre-fix code (run B follows run A) and passes after scoping by runId.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React from 'react';
import { render, act } from '@testing-library/react';
import { WorkflowModeProvider, useWorkflowMode } from '../WorkflowModeContext';

vi.mock('next/navigation', () => ({
  usePathname: () => '/app/applications/test',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

// No workflowId is passed below, so listVersions is never actually invoked -
// the mock only keeps the '@/lib/api' import graph out of the test.
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    listVersions: vi.fn(async () => ({ pinnedVersion: null, currentVersion: 1 })),
  },
}));

vi.mock('@/lib/stores/pending-interfaces-store', () => ({
  usePendingInterfacesStore: { getState: () => ({ clear: vi.fn() }) },
}));

vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: { getState: () => ({ clear: vi.fn() }) },
}));

// Probe surfaces each provider's viewingEpoch + setViewingEpoch to the test.
const captured: Record<string, { epoch: number | null; set: (e: number | null) => void }> = {};

function Probe({ name }: { name: string }) {
  const { viewingEpoch, setViewingEpoch } = useWorkflowMode();
  captured[name] = { epoch: viewingEpoch, set: setViewingEpoch };
  return <div data-testid={`epoch-${name}`}>{String(viewingEpoch)}</div>;
}

beforeEach(() => {
  for (const k of Object.keys(captured)) delete captured[k];
});

describe('viewingEpochChanged runId scoping (multi-app side panel)', () => {
  it('does NOT move a sibling app on a different run when one app changes epoch', () => {
    render(
      <>
        <WorkflowModeProvider initialRunId="run-A"><Probe name="a" /></WorkflowModeProvider>
        <WorkflowModeProvider initialRunId="run-B"><Probe name="b" /></WorkflowModeProvider>
      </>,
    );

    act(() => { captured.a.set(3); });

    expect(captured.a.epoch).toBe(3);
    // Pre-fix this asserted value would be 3 (unscoped global broadcast).
    expect(captured.b.epoch).toBeNull();
  });

  it('DOES sync two providers bound to the SAME run (canvas RunInfo ↔ same-run app tab)', () => {
    render(
      <>
        <WorkflowModeProvider initialRunId="run-X"><Probe name="x1" /></WorkflowModeProvider>
        <WorkflowModeProvider initialRunId="run-X"><Probe name="x2" /></WorkflowModeProvider>
      </>,
    );

    act(() => { captured.x1.set(5); });

    expect(captured.x1.epoch).toBe(5);
    expect(captured.x2.epoch).toBe(5);
  });

  it('still adopts a legacy unscoped event (no runId in detail) for backward compatibility', () => {
    render(
      <WorkflowModeProvider initialRunId="run-A"><Probe name="a" /></WorkflowModeProvider>,
    );

    act(() => {
      window.dispatchEvent(new CustomEvent('viewingEpochChanged', { detail: { epoch: 7 } }));
    });

    expect(captured.a.epoch).toBe(7);
  });
});
