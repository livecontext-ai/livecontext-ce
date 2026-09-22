/**
 * @vitest-environment jsdom
 *
 * The node palette and the run history are side-panel TABS now, not panels
 * floating over the ReactFlow canvas. This pins their contract inside the
 * workflow / application panel tab bar:
 *   - edit mode (no run bound) shows "Add Node", run mode shows "Run";
 *   - the canvas buttons (history / version chip / "+") focus the right tab;
 *   - an embedded or preview surface never offers run-history navigation.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

const runPanelProps = vi.hoisted(() => ({ current: null as any }));
const nodeCreatorProps = vi.hoisted(() => ({ current: null as any }));

// The canvas action cluster (Share / Save / Run) has its own suite; here it is a
// marker so these tests keep exercising the tab bar rather than the publish
// wizard and the version-history fetch it pulls in.
vi.mock('@/components/app/WorkflowPanelActions', () => ({
  WorkflowPanelActions: () => null,
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/workflow/wf-1' }));
// Captures what the panel hands ChatCore, so the model menu it BUILDS can be
// read without the stand-in having to render it.
const chatCoreProps = vi.hoisted(() => ({ last: null as Record<string, unknown> | null }));
const credits = vi.hoisted(() => ({ blocked: false }));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: credits.blocked }),
}));
vi.mock('@/components/chat/ChatCore', () => ({
  ChatCore: (props: Record<string, unknown>) => {
    chatCoreProps.last = props;
    return <div data-testid="chat" />;
  },
}));
vi.mock('@/app/shared/components', () => ({ WelcomeTitle: ({ children }: any) => <>{children}</> }));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({
  ModelSelectorDropdown: () => null,
  PROVIDER_ICON_MAP: {},
}));
vi.mock('@/components/ai/NoProviderCta', () => ({ NoProviderCta: () => null }));
vi.mock('@/components/chat/TriggerTabContent', () => ({ TriggerTabContent: () => null }));
vi.mock('@/components/chat/ApplicationCarousel', () => ({ ApplicationCarousel: () => null }));
vi.mock('@/hooks/useWorkflowChat', () => ({
  useWorkflowChat: () => ({
    conversationId: null, messages: [], isLoading: false,
    sendMessage: vi.fn(), loadConversation: vi.fn(), stopStream: vi.fn(),
  }),
}));
vi.mock('@/hooks/useModels', () => ({
  useVisibleModels: () => ({ models: [], defaultModel: null, isLoading: false, error: null }),
  EMPTY_SELECTED_MODEL: { id: '' },
  modelMatches: () => false,
  selectedModelFromAIModel: () => ({ id: '' }),
  selectedModelEquals: () => true,
  getEffectiveDefaultSelectedModel: () => ({ id: '' }),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({ useUnifiedAppSafe: () => null }));
vi.mock('@/contexts/StreamingContext', () => ({ useStreaming: () => ({ isStreamingConversation: () => false }) }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: any) => <>{children}</>,
  useWorkflowMode: () => ({ isPreviewOnly: false, workflowId: 'wf-1' }),
}));
vi.mock('@/components/workflow/run-panel/RunPanelContent', () => ({
  RunPanelContent: (props: any) => { runPanelProps.current = props; return <div data-testid="run-panel" />; },
}));
vi.mock('@/components/app/NodeCreatorPanelContent', () => ({
  NodeCreatorPanelContent: (props: any) => { nodeCreatorProps.current = props; return <div data-testid="node-creator" />; },
}));

import { panelTabClass } from '@/components/ui/panel-tab';
import {
  NODE_CREATOR_TAB_ID,
  WorkflowPanelContent,
  setPendingActivateTab,
} from '@/components/app/WorkflowPanelContent';
import {
  clearRunPanelCache,
  makeEmptyRunPanelData,
  openNodeCreatorPanel,
  openRunPanel,
  publishRunPanelData,
} from '@/components/workflow/run-panel/runPanelBus';

function publishRun(overrides: Record<string, unknown> = {}) {
  act(() => {
    publishRunPanelData({
      ...makeEmptyRunPanelData('wf-1'),
      runId: 'run-1',
      runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 },
      currentEpoch: 1,
      ...overrides,
    } as any);
  });
}

beforeEach(() => clearRunPanelCache());
afterEach(() => {
  clearRunPanelCache();
  runPanelProps.current = null;
  nodeCreatorProps.current = null;
  cleanup();
});

describe('WorkflowPanelContent - Run / Add Node sub-tabs', () => {
  // This is the only fixture that binds a run, so it is the only place the Run
  // tab renders at all: its shared styling and aria state can be pinned here
  // and nowhere else.
  it('gives the Run tab the same shared sub-tab style and aria state as every other tab', () => {
    publishRun();
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);

    const runTab = screen.getByText('sidePanel.runTab').closest('button')!;
    expect(runTab.getAttribute('data-testid')).toBe('panel-sub-tab');
    expect(runTab.className).toContain(panelTabClass(false, 'sm'));
    expect(runTab.getAttribute('aria-pressed')).toBe('false');

    act(() => { runTab.click(); });

    const active = screen.getByText('sidePanel.runTab').closest('button')!;
    expect(active.getAttribute('aria-pressed')).toBe('true');
    expect(active.className).toContain(panelTabClass(true, 'sm'));
    expect(active.className.split(/\s+/)).toContain('bg-[var(--bg-hover)]');
    // Every rendered sub-tab is reachable through the shared marker.
    expect(container.querySelectorAll('[data-testid="panel-sub-tab"]').length)
      .toBe(container.querySelectorAll('button[aria-pressed]').length);
  });

  it('offers Add Node while editing and Run once a run is bound', () => {
    const { rerender } = render(<WorkflowPanelContent workflowId="wf-1" />);
    // Edit mode: the palette tab, no run tab.
    expect(screen.getByText('workflowBuilder.canvas.addNode')).toBeTruthy();
    expect(screen.queryByText('sidePanel.runTab')).toBeNull();
    expect(screen.queryByText('actions.logs')).toBeNull();

    publishRun();
    rerender(<WorkflowPanelContent workflowId="wf-1" />);

    // Run mode: the run tab replaces the palette (the canvas is read-only then).
    expect(screen.getByText('sidePanel.runTab')).toBeTruthy();
    expect(screen.getByText('actions.logs')).toBeTruthy();
    expect(screen.queryByText('workflowBuilder.canvas.addNode')).toBeNull();
  });

  it('renders the palette when the Add Node tab is selected', () => {
    render(<WorkflowPanelContent workflowId="wf-1" />);
    act(() => { screen.getByText('workflowBuilder.canvas.addNode').click(); });
    expect(screen.getByTestId('node-creator')).toBeTruthy();
    expect(nodeCreatorProps.current.workflowId).toBe('wf-1');
  });

  it('focuses the palette when the canvas "+" fires while this panel is already showing', () => {
    // The page-level handler cannot serve this case: nothing remounts, so only
    // this in-panel listener can react. Without it the click was swallowed.
    render(<WorkflowPanelContent workflowId="wf-1" />);
    expect(screen.queryByTestId('node-creator')).toBeNull();

    act(() => openNodeCreatorPanel({ workflowId: 'wf-1' }));

    expect(screen.getByTestId('node-creator')).toBeTruthy();
  });

  it('refuses a palette request on a workflow the caller may not change, without moving the user', () => {
    // The canvas "+" reaches the listener directly, so the tab-bar guard alone
    // would not hold. Leaving the refusal to the availability effect - which
    // resets the tab a commit later - is not equivalent either: it lands the user
    // on the canvas, i.e. the "+" still took them off the tab they were reading.
    // Staying put is what proves the request was refused where it arrived.
    render(<WorkflowPanelContent workflowId="wf-1" workflowCanvasSlot={<div />} canEditWorkflow={false} />);
    act(() => { screen.getByText('sidePanel.aiChat').closest('button')!.click(); });
    expect(screen.getByTestId('chat')).toBeTruthy();

    act(() => openNodeCreatorPanel({ workflowId: 'wf-1' }));

    expect(screen.queryByTestId('node-creator')).toBeNull();
    expect(
      screen.getByText('sidePanel.aiChat').closest('button')!.getAttribute('aria-pressed'),
      'the request was refused, not bounced back through the canvas',
    ).toBe('true');
  });

  it('refuses a palette activation sent by another surface, without moving the user', () => {
    // The cross-component activate event is a second way in and does not pass
    // through the listener above, so it needs the same refusal - otherwise the
    // canvas toggle in the application view could still send a reader of the AI
    // chat to a palette this panel does not offer.
    render(<WorkflowPanelContent workflowId="wf-1" workflowCanvasSlot={<div />} canEditWorkflow={false} />);
    act(() => { screen.getByText('sidePanel.aiChat').closest('button')!.click(); });

    act(() => {
      window.dispatchEvent(new CustomEvent('workflowPanelActivateTab', {
        detail: { tabId: '__add_node__', workflowId: 'wf-1' },
      }));
    });

    expect(screen.queryByTestId('node-creator')).toBeNull();
    expect(screen.getByText('sidePanel.aiChat').closest('button')!.getAttribute('aria-pressed')).toBe('true');
  });

  it('never MOUNTS the palette for a handoff recorded outside either listener', () => {
    // The pending-tab handoff bypasses both listeners and lands straight in the
    // active tab, so the availability effect only undoes it on the NEXT commit -
    // by which point the palette has already been mounted once. Reading the final
    // DOM cannot see that; reading whether the component ever rendered can.
    setPendingActivateTab(NODE_CREATOR_TAB_ID, 'wf-probe');
    render(<WorkflowPanelContent workflowId="wf-probe" workflowCanvasSlot={<div />} canEditWorkflow={false} />);

    expect(nodeCreatorProps.current, 'mounted for one commit, then taken back').toBeNull();
  });

  it('ignores a palette request aimed at another workflow', () => {
    render(<WorkflowPanelContent workflowId="wf-1" />);
    act(() => openNodeCreatorPanel({ workflowId: 'wf-other' }));
    expect(screen.queryByTestId('node-creator')).toBeNull();
  });

  it('focuses the Run tab on the requested level when the canvas asks for it', () => {
    publishRun();
    render(<WorkflowPanelContent workflowId="wf-1" />);
    expect(screen.queryByTestId('run-panel')).toBeNull();

    act(() => openRunPanel({ workflowId: 'wf-1', view: 'history' }));

    expect(screen.getByTestId('run-panel')).toBeTruthy();
    expect(runPanelProps.current.viewRequest.view).toBe('history');

    // The run-detail shortcut (panel button on the canvas bar) lands on 'run'.
    act(() => openRunPanel({ workflowId: 'wf-1', view: 'run' }));
    expect(runPanelProps.current.viewRequest.view).toBe('run');
  });

  it('ignores an open request addressed to another workflow (sub-workflow tabs)', () => {
    publishRun();
    render(<WorkflowPanelContent workflowId="wf-1" />);
    act(() => openRunPanel({ workflowId: 'wf-other', view: 'history' }));
    expect(screen.queryByTestId('run-panel')).toBeNull();
  });

  it('allows run-history navigation on the standalone workflow page by default', () => {
    publishRun();
    render(<WorkflowPanelContent workflowId="wf-1" />);
    act(() => openRunPanel({ workflowId: 'wf-1' }));
    expect(runPanelProps.current.allowHistory).toBe(true);

    cleanup();

    // Embedded canvas whose host CHOSE the run for it (the application panel):
    // the run is fixed, so the Run tab stays on that run's detail.
    render(<WorkflowPanelContent workflowId="wf-1" workflowCanvasSlot={<div />} />);
    act(() => openRunPanel({ workflowId: 'wf-1' }));
    expect(runPanelProps.current.allowHistory).toBe(false);
  });

  it('hands the Run tab a way back to the canvas when the panel hosts one', () => {
    // The Workflow sub-tab lives at the BOTTOM of the panel, under a run's epochs
    // and steps: from the top of a long run it is off screen, so the run header
    // carries the same jump.
    publishRun();
    render(<WorkflowPanelContent workflowId="wf-1" workflowCanvasSlot={<div />} />);
    act(() => openRunPanel({ workflowId: 'wf-1' }));
    expect(typeof runPanelProps.current.onBackToWorkflow).toBe('function');

    // ...and it really lands on the canvas tab: the Run panel stops rendering.
    act(() => { runPanelProps.current.onBackToWorkflow(); });
    expect(screen.queryByTestId('run-panel')).toBeNull();
    expect(screen.getByText('common.workflow').closest('button')!.getAttribute('aria-pressed')).toBe('true');
  });

  it('withholds that jump when there is no canvas sub-tab to land on', () => {
    // No slot means no Workflow sub-tab, so the control would lead nowhere.
    publishRun();
    render(<WorkflowPanelContent workflowId="wf-1" />);
    act(() => openRunPanel({ workflowId: 'wf-1' }));
    expect(runPanelProps.current.onBackToWorkflow).toBeUndefined();
  });

  it('hands the Run tab the surface id its host gave it, so a pick binds that host', () => {
    // The page passes none (it owns the route); a side-panel workflow tab passes
    // its own. Losing this on the way down sends the tab's picks to the page.
    publishRun();
    render(<WorkflowPanelContent workflowId="wf-1" workflowCanvasSlot={<div />} allowRunHistory runSurfaceId="tab-7" />);
    act(() => openRunPanel({ workflowId: 'wf-1' }));
    expect(runPanelProps.current.surfaceId).toBe('tab-7');

    cleanup();

    render(<WorkflowPanelContent workflowId="wf-1" />);
    act(() => openRunPanel({ workflowId: 'wf-1' }));
    expect(runPanelProps.current.surfaceId).toBeUndefined();
  });

  it('lets an embedded host that can rebind its canvas opt back into the history', () => {
    // The sub-workflow tab: it hosts the canvas AND follows the bind event, so
    // its run detail must keep the back arrow into the list of runs. Without the
    // override, `workflowCanvasSlot` alone denied it and the tab was a dead end.
    publishRun();
    render(<WorkflowPanelContent workflowId="wf-1" workflowCanvasSlot={<div />} allowRunHistory />);
    act(() => openRunPanel({ workflowId: 'wf-1' }));
    expect(runPanelProps.current.allowHistory).toBe(true);
  });

  it('never lets the opt-in unlock the history in marketplace preview', () => {
    // Preview is frozen on a published version: no host may negotiate it.
    publishRun({ isPreviewOnly: true });
    render(<WorkflowPanelContent workflowId="wf-1" isPreviewOnly workflowCanvasSlot={<div />} allowRunHistory />);
    act(() => openRunPanel({ workflowId: 'wf-1' }));
    expect(runPanelProps.current.allowHistory).toBe(false);
  });

  it('withholds the palette on a workflow the caller may not change', () => {
    // An installed application's canvas: not a preview, and it can sit with no
    // run bound, so neither existing guard covered it. Its plan is frozen by the
    // backend, so a node dropped from the palette could never be saved - and the
    // Save button beside it is withheld for exactly that reason.
    render(<WorkflowPanelContent workflowId="wf-1" workflowCanvasSlot={<div />} canEditWorkflow={false} />);
    expect(screen.queryByText('workflowBuilder.canvas.addNode')).toBeNull();

    cleanup();

    // Control: the same surface for someone who MAY change it still gets it.
    render(<WorkflowPanelContent workflowId="wf-1" workflowCanvasSlot={<div />} canEditWorkflow />);
    expect(screen.getByText('workflowBuilder.canvas.addNode')).toBeTruthy();
  });

  it('keeps the Run tab in marketplace preview but never the palette', () => {
    publishRun({ isPreviewOnly: true });
    render(<WorkflowPanelContent workflowId="wf-1" isPreviewOnly workflowCanvasSlot={<div />} />);
    expect(screen.getByText('sidePanel.runTab')).toBeTruthy();
    expect(screen.queryByText('workflowBuilder.canvas.addNode')).toBeNull();
  });

  it('falls back off the Run tab when the run disappears (back to edit mode)', () => {
    publishRun();
    const { rerender } = render(<WorkflowPanelContent workflowId="wf-1" />);
    act(() => openRunPanel({ workflowId: 'wf-1' }));
    expect(screen.getByTestId('run-panel')).toBeTruthy();

    act(() => {
      publishRunPanelData({ ...makeEmptyRunPanelData('wf-1') } as any);
    });
    rerender(<WorkflowPanelContent workflowId="wf-1" />);

    expect(screen.queryByTestId('run-panel')).toBeNull();
    expect(screen.getByTestId('chat')).toBeTruthy();
  });

  /** The model menu the panel builds, read as the element it handed down. */
  function leadingControlProps(): Record<string, unknown> {
    const leading = chatCoreProps.last?.leadingControl as React.ReactElement<Record<string, unknown>>;
    return leading?.props ?? {};
  }

  it('hands the verdict and the notice down to the model menu', () => {
    // The dropdown carries no translations and no data hooks by design, so the
    // panel asks and passes both down. Nothing else would notice one going
    // missing, which is why this is asserted per host rather than once.
    credits.blocked = true;
    render(<WorkflowPanelContent workflowId="wf-1" />);

    const menu = leadingControlProps();
    expect(menu.upgradeRequired).toBe(true);
    const notice = menu.upgradeNotice as React.ReactElement<{ blocked?: boolean }>;
    expect(notice?.props?.blocked).toBe(true);
  });

  it('hands down a clear verdict for an account that can pay', () => {
    credits.blocked = false;
    render(<WorkflowPanelContent workflowId="wf-1" />);

    const menu = leadingControlProps();
    expect(menu.upgradeRequired).toBe(false);
    const notice = menu.upgradeNotice as React.ReactElement<{ blocked?: boolean }>;
    expect(notice?.props?.blocked).toBe(false);
  });
});
