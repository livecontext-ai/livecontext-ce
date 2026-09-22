/**
 * @vitest-environment jsdom
 *
 * The panel's Save and Run, rendered for real.
 *
 * The sibling suite stubs both controls to keep its subject the bar's layout, so
 * nothing there proves the two things a user actually depends on: that Save is
 * enabled exactly when there is something to save, and that Run starts THIS
 * workflow and not whichever canvas happens to be mounted next to it.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, act } from '@testing-library/react';

let mockCanMutate: boolean;

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  // The version control reaches run mode on a pin, and asks where it is first.
  usePathname: () => '/en/app/workflow/wf-1',
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ setRunId: vi.fn() }) }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => mockCanMutate }));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflow: vi.fn().mockResolvedValue({ name: 'wf', description: '' }),
    listVersions: vi.fn().mockResolvedValue({ versions: [], currentVersion: 1, pinnedVersion: null }),
  },
}));
// The publish wizard is a portal with its own heavy tree; it has its own suite.
vi.mock('@/components/workflow/ShareWorkflowModal', () => ({
  PublishWorkflowModal: () => null,
}));
// The version history it mounts asks for the bell's automation rows again after a pin, since
// pinning is what puts a workflow in them. The real hook reaches for a QueryClient this suite
// has no provider for; the ask is pinned by the call-site guard in
// lib/api/orchestrator/__tests__/automationRowMutations.callSites.test.ts.
vi.mock('@/hooks/useHomeStatus', () => ({ useRefreshHomeStatus: () => () => {} }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="spinner" /> }));

import { WorkflowPanelActions } from '../WorkflowPanelActions';

/** The Save half of WorkflowSaveWithVersions - the chevron beside it is separate. */
function saveButton() {
  return screen.getByTitle('actions.save');
}

function renderActions(overrides: { isRunMode?: boolean } = {}) {
  return render(
    <WorkflowPanelActions
      workflowId="wf-1"
      isRunMode={overrides.isRunMode ?? false}
      isPreviewOnly={false}
    />,
  );
}

function emit(type: string, detail: Record<string, unknown>) {
  act(() => { window.dispatchEvent(new CustomEvent(type, { detail })); });
}

beforeEach(() => { mockCanMutate = true; });
afterEach(cleanup);

describe('WorkflowPanelActions - the real Save control', () => {
  it('is disabled while the canvas has nothing unsaved', () => {
    renderActions();

    expect(saveButton()).toBeDisabled();
  });

  it('enables once THIS workflow reports unsaved changes', () => {
    renderActions();

    emit('workflowDirtyChange', { isDirty: true, workflowId: 'wf-1' });

    expect(saveButton()).not.toBeDisabled();
  });

  it('stays disabled on another workflow going dirty', () => {
    renderActions();

    emit('workflowDirtyChange', { isDirty: true, workflowId: 'wf-other' });

    expect(saveButton()).toBeDisabled();
  });

  it('is GONE in run mode, not merely disabled - a run is watched, not authored', () => {
    renderActions({ isRunMode: true });

    // Even with unsaved changes pending: the control used to render permanently
    // greyed out, which kept an edit affordance in the header of a page where it can
    // never do anything. It is dropped instead.
    emit('workflowDirtyChange', { isDirty: true, workflowId: 'wf-1' });

    expect(screen.queryByTitle('actions.save')).toBeNull();
  });

  it('keeps the version history reachable in run mode - it is a read, not an edit', () => {
    renderActions({ isRunMode: true });

    // Which plan version a run is executing is exactly a run-mode question, so the
    // half of the split button that opens the list survives on its own.
    expect(screen.getByTestId('workflow-version-history-toggle')).toBeInTheDocument();
  });

  it('is disabled while this workflow agent is streaming into the canvas', () => {
    renderActions();
    emit('workflowDirtyChange', { isDirty: true, workflowId: 'wf-1' });

    emit('workflowStreamingStateChange', { isStreaming: true, workflowId: 'wf-1' });

    expect(saveButton()).toBeDisabled();
  });

  it('asks THIS workflow to save, and confirms when it reports back', () => {
    const saves: CustomEvent[] = [];
    window.addEventListener('workflowViewSave', (e) => saves.push(e as CustomEvent));
    renderActions();
    emit('workflowDirtyChange', { isDirty: true, workflowId: 'wf-1' });

    fireEvent.click(saveButton());
    expect(saves).toHaveLength(1);
    expect(saves[0].detail).toEqual({ workflowId: 'wf-1' });
    // Pending feedback while the canvas works.
    expect(screen.getByTestId('spinner')).toBeTruthy();

    // Icon-only in the panel bar, so the confirmation is the colour and the icon,
    // not a word. What matters is that it left the pending state.
    emit('workflowViewSaveComplete', { success: true, workflowId: 'wf-1' });
    expect(screen.queryByTestId('spinner')).toBeNull();
    expect(saveButton().className).toContain('text-green-600');
  });

  it('shows the failure rather than a false confirmation', () => {
    renderActions();
    emit('workflowDirtyChange', { isDirty: true, workflowId: 'wf-1' });
    fireEvent.click(saveButton());

    emit('workflowViewSaveComplete', { success: false, workflowId: 'wf-1' });

    expect(saveButton().className).toContain('text-red-600');
    // The changes are still unsaved, so the control must stay usable.
    expect(saveButton()).not.toBeDisabled();
  });
});

describe('WorkflowPanelActions - the real Run control', () => {
  it('starts THIS workflow, naming it so no other mounted canvas answers', () => {
    const starts: CustomEvent[] = [];
    window.addEventListener('workflowViewStart', (e) => starts.push(e as CustomEvent));
    renderActions();

    fireEvent.click(screen.getByTitle('actions.run'));

    expect(starts).toHaveLength(1);
    expect(starts[0].detail).toEqual({ workflowId: 'wf-1' });
  });

  it('offers step-by-step from its menu, named the same way', () => {
    const stepped: CustomEvent[] = [];
    window.addEventListener('workflowStartStepByStep', (e) => stepped.push(e as CustomEvent));
    renderActions();

    // The chevron opens the mode menu beside the primary Run.
    fireEvent.click(screen.getByLabelText('actions.run'));
    fireEvent.click(screen.getByText('workflowBuilder.canvas.runStepByStep'));

    expect(stepped).toHaveLength(1);
    expect(stepped[0].detail).toEqual({ workflowId: 'wf-1' });
  });
});
