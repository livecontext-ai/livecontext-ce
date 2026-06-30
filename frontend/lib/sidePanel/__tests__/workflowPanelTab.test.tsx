/**
 * @vitest-environment jsdom
 *
 * Regression tests for the Workflow Panel auto-register hook.
 *
 * Background: clicking the workflow-panel toggle button was a no-op when the
 * panel was already open (the handler only registered the tab when the panel
 * was closed). And after navigating between workflows, the tab survived the
 * scope filter but its content stayed bound to the OLD workflowId.
 *
 * Coverage:
 * (a) Registers the workflow-panel tab on mount of a workflow page.
 * (b) Re-registers (replaces the tab content) when workflowId changes.
 * (c) No churn - same workflowId across re-renders does not re-register.
 * (d) Is a no-op when not on a workflow page.
 * (e) Re-registers after the tab is dropped by the SidePanel scope filter
 *     (cross-group navigation away from /app/workflow/* and back).
 */
import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import * as React from 'react';

const pathHolder = vi.hoisted(() => ({ current: '/app/workflow/A' }));
vi.mock('next/navigation', () => ({
  usePathname: () => pathHolder.current,
}));

vi.mock('@/hooks/useMobileDetection', () => ({
  useMobileDetection: () => false,
}));

vi.mock('@/components/app/WorkflowPanelContent', () => ({
  WorkflowPanelContent: ({ workflowId }: { workflowId: string }) =>
    React.createElement('div', { 'data-testid': 'wfp-content' }, workflowId),
}));

import { SidePanelProvider, useSidePanelSafe } from '@/contexts/SidePanelContext';
import {
  WORKFLOW_PANEL_TAB_ID,
  useAutoRegisterWorkflowPanelTab,
} from '@/lib/sidePanel/workflowPanelTab';

type Spy = {
  tabIds: string[];
  workflowPanelContent: React.ReactNode | undefined;
};

const spyRef: { current: Spy | null } = { current: null };

function Harness({
  shouldRegister,
  workflowId,
}: {
  shouldRegister: boolean;
  workflowId: string | null;
}) {
  useAutoRegisterWorkflowPanelTab(shouldRegister, workflowId);
  const sidePanel = useSidePanelSafe();
  spyRef.current = {
    tabIds: sidePanel?.tabs.map(t => t.id) ?? [],
    workflowPanelContent: sidePanel?.tabs.find(t => t.id === WORKFLOW_PANEL_TAB_ID)?.content,
  };
  return null;
}

describe('useAutoRegisterWorkflowPanelTab', () => {
  it('(a) registers the workflow-panel tab on mount', () => {
    pathHolder.current = '/app/workflow/A';
    spyRef.current = null;
    render(
      <SidePanelProvider>
        <Harness shouldRegister={true} workflowId="A" />
      </SidePanelProvider>
    );
    expect(spyRef.current?.tabIds).toContain(WORKFLOW_PANEL_TAB_ID);
  });

  it('(b) re-registers with fresh content when workflowId changes', () => {
    pathHolder.current = '/app/workflow/A';
    spyRef.current = null;
    const { rerender } = render(
      <SidePanelProvider>
        <Harness shouldRegister={true} workflowId="A" />
      </SidePanelProvider>
    );
    // The tab's `content` is the unrendered React element
    // <WorkflowPanelContent workflowId={workflowId}/> - read the prop directly.
    const beforeContent = spyRef.current!.workflowPanelContent as React.ReactElement<{ workflowId: string }>;
    expect(beforeContent.props.workflowId).toBe('A');

    pathHolder.current = '/app/workflow/B';
    rerender(
      <SidePanelProvider>
        <Harness shouldRegister={true} workflowId="B" />
      </SidePanelProvider>
    );
    const afterContent = spyRef.current!.workflowPanelContent as React.ReactElement<{ workflowId: string }>;
    expect(afterContent.props.workflowId).toBe('B');
    expect(spyRef.current!.tabIds.filter(id => id === WORKFLOW_PANEL_TAB_ID).length).toBe(1);
  });

  it('(c) does not re-register when workflowId stays the same across re-renders', () => {
    pathHolder.current = '/app/workflow/A';
    spyRef.current = null;
    const { rerender } = render(
      <SidePanelProvider>
        <Harness shouldRegister={true} workflowId="A" />
      </SidePanelProvider>
    );
    const first = spyRef.current!.workflowPanelContent;
    rerender(
      <SidePanelProvider>
        <Harness shouldRegister={true} workflowId="A" />
      </SidePanelProvider>
    );
    const second = spyRef.current!.workflowPanelContent;
    // Same tab object - the hook's ref-guard short-circuited.
    expect(second).toBe(first);
    expect(spyRef.current!.tabIds.filter(id => id === WORKFLOW_PANEL_TAB_ID).length).toBe(1);
  });

  it('(d) is a no-op when shouldRegister is false (non-workflow page)', () => {
    pathHolder.current = '/app/profile';
    spyRef.current = null;
    render(
      <SidePanelProvider>
        <Harness shouldRegister={false} workflowId={null} />
      </SidePanelProvider>
    );
    expect(spyRef.current?.tabIds).not.toContain(WORKFLOW_PANEL_TAB_ID);
  });

  it('(e) re-registers after the SidePanel scope filter drops the tab on cross-group nav', () => {
    pathHolder.current = '/app/workflow/A';
    spyRef.current = null;
    const { rerender } = render(
      <SidePanelProvider>
        <Harness shouldRegister={true} workflowId="A" />
      </SidePanelProvider>
    );
    expect(spyRef.current!.tabIds).toContain(WORKFLOW_PANEL_TAB_ID);

    // Cross-group navigation away. SidePanelContext's pathname effect drops
    // the workflow-panel tab (scope `/app/workflow/*` doesn't match `/app/profile`).
    pathHolder.current = '/app/profile';
    rerender(
      <SidePanelProvider>
        <Harness shouldRegister={false} workflowId={null} />
      </SidePanelProvider>
    );
    expect(spyRef.current!.tabIds).not.toContain(WORKFLOW_PANEL_TAB_ID);

    // Returning to the same workflow page - the ref still holds 'A' from the
    // initial registration, but the tab is gone. The hook MUST notice the
    // tab's absence and re-register, otherwise the workflow panel stays missing.
    pathHolder.current = '/app/workflow/A';
    rerender(
      <SidePanelProvider>
        <Harness shouldRegister={true} workflowId="A" />
      </SidePanelProvider>
    );
    expect(spyRef.current!.tabIds).toContain(WORKFLOW_PANEL_TAB_ID);
    // The re-registered tab carries fresh content bound to 'A' - guards against
    // the stale-content regression where a same-id readd would skip the merge.
    const reAddedContent = spyRef.current!.workflowPanelContent as React.ReactElement<{ workflowId: string }>;
    expect(reAddedContent.props.workflowId).toBe('A');
  });
});
