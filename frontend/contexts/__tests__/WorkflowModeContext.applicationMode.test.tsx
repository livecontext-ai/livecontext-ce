/**
 * @vitest-environment jsdom
 *
 * Pins the `isApplicationMode` resolution that gates the interface node's
 * "open in application panel" affordance (InterfacePreviewNode / FlowNode /
 * CanvasContextMenu). Inside an application (/app/applications/*) the app is
 * already the main view, so that affordance must be hidden; in workflow
 * surfaces it must stay visible. Both depend on this flag being true on an
 * applications path and false on a workflow path.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React from 'react';
import { render } from '@testing-library/react';

let mockPathname = '/app/applications/test';
vi.mock('next/navigation', () => ({
  usePathname: () => mockPathname,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: { listVersions: vi.fn(async () => ({ pinnedVersion: null, currentVersion: 1 })) },
}));
vi.mock('@/lib/stores/pending-interfaces-store', () => ({
  usePendingInterfacesStore: { getState: () => ({ clear: vi.fn() }) },
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: { getState: () => ({ clear: vi.fn() }) },
}));

import { WorkflowModeProvider, useWorkflowMode } from '../WorkflowModeContext';

let captured: boolean | null = null;
function Probe() {
  captured = useWorkflowMode().isApplicationMode;
  return null;
}

function setPath(path: string) {
  mockPathname = path;
  window.history.pushState({}, '', path);
}

beforeEach(() => {
  captured = null;
});

describe('WorkflowModeContext - isApplicationMode', () => {
  it('is TRUE on an /app/applications path (app is the main view - hide the panel affordance)', () => {
    // initialRunId makes the provider programmatic, exactly like the canvas
    // provider ApplicationDetailView mounts for its side-panel workflow canvas.
    setPath('/en/app/applications/pub-1');
    render(<WorkflowModeProvider initialRunId="run-1"><Probe /></WorkflowModeProvider>);
    expect(captured).toBe(true);
  });

  it('is FALSE on an /app/workflow edit path (workflow surface - keep the panel affordance)', () => {
    setPath('/en/app/workflow/wf-1');
    render(<WorkflowModeProvider><Probe /></WorkflowModeProvider>);
    expect(captured).toBe(false);
  });

  it('is FALSE on an /app/workflow run path (workflow run surface - keep the panel affordance)', () => {
    setPath('/en/app/workflow/wf-1/run/run-1');
    render(<WorkflowModeProvider><Probe /></WorkflowModeProvider>);
    expect(captured).toBe(false);
  });
});
