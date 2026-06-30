// @vitest-environment jsdom
/**
 * The node right-click menu can't own the pin confirmation modal (it unmounts
 * the instant an item is clicked), so its Pin/Unpin item dispatches
 * TRIGGER_PIN_REQUEST_EVENT and the always-mounted pin button - matched by
 * nodeId - opens the flow. These tests pin that bridge: the matching button
 * reacts, siblings stay silent.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';

const mockListVersions = vi.fn();
// Mutable so a test can put the button into its hidden (not-loaded) state while
// the request-pin listener must still fire.
let mockMode: Record<string, unknown>;

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => mockMode }));
vi.mock('@/contexts/WorkflowRunContext', () => ({ useRun: () => [null, null] }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { listVersions: (...a: unknown[]) => mockListVersions(...a), pinVersion: vi.fn() } }));

import { TriggerNodePinButton } from '../TriggerNodePinButton';
import { TRIGGER_PIN_REQUEST_EVENT } from '../../../hooks/useTriggerPin';

const dispatchRequest = (detail: { workflowId: string; nodeId?: string }) =>
  window.dispatchEvent(new CustomEvent(TRIGGER_PIN_REQUEST_EVENT, { detail }));

beforeEach(() => {
  mockListVersions.mockReset();
  mockListVersions.mockResolvedValue({ currentVersion: 2, pinnedVersion: null });
  mockMode = { isRunMode: false, runId: null, currentVersion: 2, activeVersion: 2, pinnedVersion: null, workflowDirty: false };
});

describe('TriggerNodePinButton - request-pin event bridge', () => {
  it('opens the pin flow when the event targets this node id', async () => {
    render(<TriggerNodePinButton workflowId="wf1" nodeId="n1" />);
    dispatchRequest({ workflowId: 'wf1', nodeId: 'n1' });
    await waitFor(() => expect(mockListVersions).toHaveBeenCalledWith('wf1'));
  });

  it('ignores the event when it targets a different node id', async () => {
    render(<TriggerNodePinButton workflowId="wf1" nodeId="n1" />);
    dispatchRequest({ workflowId: 'wf1', nodeId: 'someOtherNode' });
    await new Promise((r) => setTimeout(r, 0));
    expect(mockListVersions).not.toHaveBeenCalled();
  });

  it('ignores the event for a different workflow', async () => {
    render(<TriggerNodePinButton workflowId="wf1" nodeId="n1" />);
    dispatchRequest({ workflowId: 'otherWorkflow', nodeId: 'n1' });
    await new Promise((r) => setTimeout(r, 0));
    expect(mockListVersions).not.toHaveBeenCalled();
  });

  it('reacts to a workflow-only event (no node id) as a broadcast', async () => {
    render(<TriggerNodePinButton workflowId="wf1" nodeId="n1" />);
    dispatchRequest({ workflowId: 'wf1' });
    await waitFor(() => expect(mockListVersions).toHaveBeenCalledWith('wf1'));
  });

  it('still opens the flow when the round button is hidden (not-loaded state)', async () => {
    // No version metadata yet: the button renders nothing, but its listener is
    // registered unconditionally so a menu-driven request still works.
    mockMode = { isRunMode: false, runId: null, currentVersion: null, activeVersion: null, pinnedVersion: null, workflowDirty: false };
    const { container } = render(<TriggerNodePinButton workflowId="wf1" nodeId="n1" />);
    expect(container.querySelector('button')).toBeNull();
    dispatchRequest({ workflowId: 'wf1', nodeId: 'n1' });
    await waitFor(() => expect(mockListVersions).toHaveBeenCalledWith('wf1'));
  });
});
