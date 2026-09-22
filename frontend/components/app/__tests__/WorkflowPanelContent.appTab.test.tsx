/**
 * @vitest-environment jsdom
 *
 * Commit-2 regression: in the side-panel workflow (a workflow canvas slot is
 * present), the Application sub-tab must (1) appear automatically once the run
 * exposes an interface, (2) be PERMANENT - a plain button with no close X, like
 * the Trigger sub-tabs, and (3) NOT steal focus from the Workflow canvas tab
 * (the user clicks it to focus it). Mirrors the trigger auto-select skip.
 */
import React from 'react';
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

const runPanelState = vi.hoisted(() => ({
  current: {
    runId: 'run-1',
    runInfo: { runId: 'run-1', status: 'COMPLETED' },
    isPreviewOnly: false,
  } as any,
  bySurface: new Map<string, any>(),
  subscribers: new Set<{ surfaceId?: string; listener: (data: any) => void }>(),
}));

// The composer fetches the verdict once for its model menu. Stubbed:
// these suites are about layout, not billing.
// The canvas action cluster (Share / Save / Run) has its own suite; here it is a
// marker so these tests keep exercising the tab bar rather than the publish
// wizard and the version-history fetch it pulls in.
vi.mock('@/components/app/WorkflowPanelActions', () => ({
  WorkflowPanelActions: () => null,
}));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: false, isLoading: false }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/chat' }));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useWorkflowMode: () => ({ isRunMode: true, isPreviewOnly: false, workflowId: 'wf-1', runId: 'run-1' }),
}));
vi.mock('@/hooks/useWorkflowChat', () => ({
  useWorkflowChat: () => ({
    conversationId: 'c-1', messages: [], isLoading: false,
    sendMessage: vi.fn(), loadConversation: vi.fn(), stopStream: vi.fn(),
  }),
}));
vi.mock('@/hooks/useModels', () => ({
  useModels: () => ({ models: [], defaultModel: undefined }),
  useVisibleModels: () => ({ models: [], defaultModel: undefined }),
  EMPTY_SELECTED_MODEL: {},
  modelMatches: () => false,
  selectedModelFromAIModel: () => ({}),
  selectedModelEquals: () => true,
  getEffectiveDefaultSelectedModel: () => ({}),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({ useUnifiedAppSafe: () => null }));
vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => ({ isStreamingConversation: () => false }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  // Not a VIEWER: these suites are about the panel, not about role gating.
  useCanMutateInCurrentOrg: () => true,
  useCurrentOrgStore: Object.assign(
    (sel: (s: any) => any) => sel({ currentOrgId: 'org-1' }),
    { subscribe: () => () => {} },
  ),
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: Object.assign(
    () => ({}),
    { getState: () => ({ setCarouselIndex: vi.fn() }) },
  ),
  carouselKeyFor: (workflowId?: string | null, runId?: string | null) => `${workflowId ?? ''}:${runId ?? ''}`,
}));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({ normalizeLabel: (s: string) => s }));
// Stubbed to keep the real store (a stateful class with a module-level
// registry) out of a composer test, and EMPTY so that no status reads as
// terminal here. It has to name every constant this tree REACHES, not just
// the ones this file reads: run-panel/runFormatting pulls UNREVIVABLE_STATUSES
// in through WorkflowPanelContent, and a stub missing one does not fail an
// assertion, it fails the whole FILE at import.
vi.mock('@/contexts/workflow-run/RunStateStore', () => ({
  TERMINAL_STATUSES: new Set<string>(),
  UNREVIVABLE_STATUSES: new Set<string>(),
}));

// Child components → simple identifiable stubs.
vi.mock('@/components/chat/ChatCore', () => ({ ChatCore: () => <div data-testid="chat-core" /> }));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({
  ModelSelectorDropdown: () => <div data-testid="model-selector" />,
  PROVIDER_ICON_MAP: {},
}));
vi.mock('@/components/chat/TriggerTabContent', () => ({ TriggerTabContent: () => <div data-testid="trigger-content" /> }));
vi.mock('@/components/chat/ApplicationCarousel', () => ({
  ApplicationCarousel: () => <div data-testid="app-carousel" />,
}));
vi.mock('@/components/workflow/WorkflowLogsPanelContent', () => ({
  WorkflowLogsPanelContent: ({ runId, initialStepAlias, onBack }: { runId: string; initialStepAlias?: string; onBack: () => void }) => (
    <div data-testid="logs-child" data-run-id={runId}>
      <span>{initialStepAlias}</span>
      <button type="button" onClick={onBack}>Back to run</button>
    </div>
  ),
}));
vi.mock('@/components/workflow/run-panel/RunPanelContent', () => ({
  RunPanelContent: ({ onOpenLogs }: { onOpenLogs?: () => void }) => (
    <div data-testid="run-parent">
      {onOpenLogs && <button type="button" onClick={onOpenLogs}>Open logs</button>}
    </div>
  ),
}));
vi.mock('@/components/workflow/run-panel/runPanelBus', () => ({
  clearRunPanelCache: vi.fn(),
  consumeRunPanelViewRequest: vi.fn(),
  getCachedRunPanelData: (_workflowId: string, surfaceId?: string) => (
    runPanelState.bySurface.get(surfaceId ?? '__page__') ?? runPanelState.current
  ),
  getRunPanelViewRequest: () => null,
  subscribeRunPanelData: (_workflowId: string, listener: (data: any) => void, surfaceId?: string) => {
    const subscription = { surfaceId, listener };
    runPanelState.subscribers.add(subscription);
    return () => runPanelState.subscribers.delete(subscription);
  },
  OPEN_NODE_CREATOR_EVENT: 'workflowOpenNodeCreator',
  OPEN_RUN_PANEL_EVENT: 'workflowOpenRunPanel',
}));

import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';
import { WORKFLOW_PANEL_OPEN_LOGS_EVENT } from '@/lib/sidePanel/workflowLogsNavigation';

function dispatchAppConfigs() {
  act(() => {
    window.dispatchEvent(new CustomEvent('workflowPanelApplicationConfigsChange', {
      detail: { workflowId: 'wf-1', configs: [{ interfaceId: 'iface-1', label: 'Search Page', actionMapping: {} }] },
    }));
  });
}

describe('WorkflowPanelContent - Application sub-tab (side-panel workflow)', () => {
  afterEach(() => {
    cleanup();
    runPanelState.current = {
      runId: 'run-1',
      runInfo: { runId: 'run-1', status: 'COMPLETED' },
      isPreviewOnly: false,
    };
    runPanelState.subscribers.clear();
    runPanelState.bySurface.clear();
  });

  it('shows the Application sub-tab automatically once an interface is available', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div data-testid="canvas-slot" />} />);
    expect(screen.queryByText('common.application')).toBeNull(); // none before configs arrive
    dispatchAppConfigs();
    expect(screen.queryByText('common.application')).not.toBeNull();
  });

  it('renders the Application sub-tab as a permanent button with NO close (X) control', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div data-testid="canvas-slot" />} />);
    dispatchAppConfigs();
    const appTab = screen.getByText('common.application').closest('button');
    expect(appTab).not.toBeNull();
    // Permanent like the Trigger tabs: no nested close button.
    expect(appTab!.querySelector('button')).toBeNull();
    expect(appTab!.querySelector('[aria-label="common.close"]')).toBeNull();
  });

  it('does NOT steal focus to the Application tab when a workflow canvas slot is present', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div data-testid="canvas-slot" />} />);
    dispatchAppConfigs();
    // Tab is available...
    expect(screen.queryByText('common.application')).not.toBeNull();
    // ...but the Application carousel content is NOT rendered (focus stayed on the
    // Workflow canvas tab - ApplicationCarousel only mounts when APP_TAB is active).
    expect(screen.queryByTestId('app-carousel')).toBeNull();
    expect(screen.queryByTestId('canvas-slot')).not.toBeNull();
  });

  it('keeps distinct Run and Logs sub-tabs when node logs open and returns through the header', () => {
    render(
      <WorkflowPanelContent
        workflowId="wf-1"
        runId="run-1"
        hostTabId="workflow-run-wf-1-run-1"
        workflowCanvasSlot={<div data-testid="canvas-slot" />}
      />,
    );

    act(() => {
      window.dispatchEvent(new CustomEvent(WORKFLOW_PANEL_OPEN_LOGS_EVENT, {
        detail: {
          targetTabId: 'workflow-run-wf-1-run-1',
          workflowId: 'wf-1',
          runId: 'run-1',
          initialStepAlias: 'mcp:fetch',
        },
      }));
    });

    expect(screen.getByTestId('logs-child')).toHaveTextContent('mcp:fetch');
    const logsTab = screen.getByRole('button', { name: 'actions.logs' });
    expect(logsTab.querySelector('.lucide-file-text')).not.toBeNull();
    expect(logsTab.querySelector('.lucide-play')).toBeNull();
    expect(logsTab).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'sidePanel.runTab' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('canvas-slot').parentElement).toHaveStyle({ display: 'none' });

    act(() => {
      screen.getByRole('button', { name: 'Back to run' }).click();
    });
    expect(screen.queryByTestId('logs-child')).toBeNull();
    expect(screen.getByTestId('run-parent')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'sidePanel.runTab' }).querySelector('.lucide-play')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'actions.logs' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('canvas-slot').parentElement).toHaveStyle({ display: 'none' });

    fireEvent.click(screen.getByRole('button', { name: 'actions.logs' }));
    expect(screen.getByTestId('logs-child')).toHaveTextContent('mcp:fetch');
  });

  it('opens current run logs directly from their sub-tab before any logs request', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div data-testid="canvas-slot" />} />);

    expect(screen.getByRole('button', { name: 'sidePanel.runTab' })).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(screen.getByRole('button', { name: 'actions.logs' }));

    expect(screen.getByTestId('logs-child')).toHaveAttribute('data-run-id', 'run-1');
    expect(screen.getByRole('button', { name: 'actions.logs' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByTestId('run-parent')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'common.workflow' }));
    expect(screen.getByTestId('canvas-slot').parentElement).not.toHaveStyle({ display: 'none' });
    expect(screen.queryByTestId('logs-child')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'sidePanel.runTab' }));
    expect(screen.getByTestId('run-parent')).toBeInTheDocument();
  });

  it('opens the logs child from the run header action', () => {
    render(
      <WorkflowPanelContent
        workflowId="wf-1"
        runId="run-1"
        hostTabId="workflow-run-wf-1-run-1"
        workflowCanvasSlot={<div data-testid="canvas-slot" />}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'sidePanel.runTab' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open logs' }));

    expect(screen.getByTestId('logs-child')).toBeInTheDocument();
    expect(screen.queryByTestId('run-parent')).toBeNull();
  });

  it('returns to the run parent when the workflow binds a different run', () => {
    render(
      <WorkflowPanelContent
        workflowId="wf-1"
        runId="run-1"
        hostTabId="workflow-run-wf-1-run-1"
        workflowCanvasSlot={<div data-testid="canvas-slot" />}
      />,
    );

    act(() => {
      window.dispatchEvent(new CustomEvent(WORKFLOW_PANEL_OPEN_LOGS_EVENT, {
        detail: {
          targetTabId: 'workflow-run-wf-1-run-1',
          workflowId: 'wf-1',
          runId: 'run-1',
        },
      }));
    });
    expect(screen.getByTestId('logs-child')).toBeInTheDocument();

    act(() => {
      runPanelState.current = {
        runId: 'run-2',
        runInfo: { runId: 'run-2', status: 'RUNNING' },
        isPreviewOnly: false,
      };
      runPanelState.subscribers.forEach(({ listener }) => listener(runPanelState.current));
    });

    expect(screen.queryByTestId('logs-child')).toBeNull();
    expect(screen.getByTestId('run-parent')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'actions.logs' }));
    expect(screen.getByTestId('logs-child')).toHaveAttribute('data-run-id', 'run-2');
  });

  it('keeps logs open when the host finishes rebinding to their target run', () => {
    render(
      <WorkflowPanelContent
        workflowId="wf-1"
        runId="run-1"
        hostTabId="workflow-run-wf-1-run-1"
        workflowCanvasSlot={<div />}
      />,
    );

    act(() => {
      window.dispatchEvent(new CustomEvent(WORKFLOW_PANEL_OPEN_LOGS_EVENT, {
        detail: {
          targetTabId: 'workflow-run-wf-1-run-1',
          workflowId: 'wf-1',
          runId: 'run-2',
        },
      }));
    });
    expect(screen.getByTestId('logs-child')).toBeInTheDocument();

    act(() => {
      runPanelState.current = {
        runId: 'run-2',
        runInfo: { runId: 'run-2', status: 'RUNNING' },
        isPreviewOnly: false,
      };
      runPanelState.subscribers.forEach(({ listener }) => listener(runPanelState.current));
    });

    expect(screen.getByTestId('logs-child')).toBeInTheDocument();
  });

  it('clears logs when the pinned panel moves to another workflow', () => {
    const { rerender } = render(
      <WorkflowPanelContent
        workflowId="wf-1"
        runId="run-1"
        hostTabId="workflow-panel"
        workflowCanvasSlot={<div data-testid="canvas-slot" />}
      />,
    );

    act(() => {
      window.dispatchEvent(new CustomEvent(WORKFLOW_PANEL_OPEN_LOGS_EVENT, {
        detail: {
          targetTabId: 'workflow-panel',
          workflowId: 'wf-1',
          runId: 'run-1',
        },
      }));
    });
    expect(screen.getByTestId('logs-child')).toBeInTheDocument();

    rerender(
      <WorkflowPanelContent
        workflowId="wf-2"
        runId="run-2"
        hostTabId="workflow-panel"
        workflowCanvasSlot={<div data-testid="canvas-slot" />}
      />,
    );

    expect(screen.queryByTestId('logs-child')).toBeNull();
    expect(screen.getByTestId('run-parent')).toBeInTheDocument();
  });

  it('keeps logs open when another surface of the same workflow changes run', () => {
    runPanelState.bySurface.set('surface-a', {
      runId: 'run-a',
      runInfo: { runId: 'run-a', status: 'COMPLETED' },
      isPreviewOnly: false,
    });
    runPanelState.bySurface.set('surface-b', {
      runId: 'run-b',
      runInfo: { runId: 'run-b', status: 'RUNNING' },
      isPreviewOnly: false,
    });

    render(
      <>
        <WorkflowPanelContent
          workflowId="wf-1"
          runId="run-a"
          hostTabId="host-a"
          runSurfaceId="surface-a"
          workflowCanvasSlot={<div />}
        />
        <WorkflowPanelContent
          workflowId="wf-1"
          runId="run-b"
          hostTabId="host-b"
          runSurfaceId="surface-b"
          workflowCanvasSlot={<div />}
        />
      </>,
    );

    act(() => {
      window.dispatchEvent(new CustomEvent(WORKFLOW_PANEL_OPEN_LOGS_EVENT, {
        detail: {
          targetTabId: 'host-a',
          workflowId: 'wf-1',
          runId: 'run-a',
        },
      }));
    });
    expect(screen.getByTestId('logs-child')).toBeInTheDocument();

    act(() => {
      const surfaceBUpdate = {
        runId: 'run-b-next',
        runInfo: { runId: 'run-b-next', status: 'RUNNING' },
        isPreviewOnly: false,
      };
      runPanelState.bySurface.set('surface-b', surfaceBUpdate);
      runPanelState.subscribers.forEach(({ surfaceId, listener }) => {
        if (surfaceId === 'surface-b') listener(surfaceBUpdate);
      });
    });

    expect(screen.getByTestId('logs-child')).toBeInTheDocument();
  });
});
