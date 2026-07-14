// @vitest-environment jsdom
/**
 * Header Run split button (2026-07-13 rework): the chevron menu offers the two
 * EXECUTION modes only - Auto and Step-by-step. The former run-level mock
 * overrides ("Run without mocks" / "Dry run") are gone: mocking is decided
 * per node (the node's mock block), never at run launch. Primary click stays
 * the default auto run and must dispatch a detail WITHOUT any mockMode key.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const orgMutationGate = vi.hoisted(() => ({ canMutate: true }));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  usePathname: () => '/en/app/workflow/wf-1',
}));
vi.mock('next/image', () => ({
  // eslint-disable-next-line @next/next/no-img-element
  default: ({ src, alt }: { src: string; alt: string }) => <img src={src} alt={alt} />,
}));
vi.mock('@/hooks/useModels', () => ({
  modelMatches: () => false,
  selectedModelFromAIModel: (m: unknown) => m,
}));
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: () => null,
  ModelInfoPopover: () => null,
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ user: null, isAuthenticated: true, isLoading: false }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: false }),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => orgMutationGate.canMutate,
}));
vi.mock('@/components/chat/workflowUtils', () => ({ isWorkflowMessage: () => false }));
vi.mock('@/components/chat/DataSourceMessage', () => ({ isDataSourceMessage: () => false }));
vi.mock('@/app/workflows/builder/components/inspector/StepDataTable', () => ({ StepDataTable: () => null }));
vi.mock('@/components/WorkflowRunResultModalContent', () => ({ WorkflowRunResultModalContent: () => null }));
vi.mock('@/components/workflow/ShareWorkflowModal', () => ({ PublishWorkflowModal: () => null }));
vi.mock('@/components/workflow/WorkflowVersionHistory', () => ({ WorkflowSaveWithVersions: () => null }));
vi.mock('@/components/marketplace/MarketplaceHeaderActions', () => ({ MarketplaceHeaderActions: () => null }));
vi.mock('@/components/chat/NotificationBell', () => ({ NotificationBell: () => null }));
vi.mock('@/components/applications/ApplicationActivationButton', () => ({ ApplicationActivationButton: () => null }));
vi.mock('@/components/agents/AvatarPicker', () => ({
  AvatarDisplay: ({ name }: { name?: string }) => <div data-testid="slot-avatar">{name}</div>,
}));

import { ChatHeader } from '../ChatHeader';

const baseProps = {
  selectedModel: { provider: 'openai', id: 'gpt-4' },
  onModelChange: vi.fn(),
  availableModels: [],
  showModelSelector: false,
  onShowModelSelector: vi.fn(),
  sidebarOpen: false,
  onSidebarToggle: vi.fn(),
  isWorkflowPage: true,
  workflowId: 'wf-1',
} as unknown as React.ComponentProps<typeof ChatHeader>;

function openRunMenu() {
  const chevrons = screen
    .getAllByRole('button')
    .filter((b) => b.getAttribute('aria-haspopup') === 'menu');
  expect(chevrons.length).toBeGreaterThan(0);
  fireEvent.click(chevrons[0]);
}

beforeEach(() => {
  orgMutationGate.canMutate = true;
});
afterEach(cleanup);

describe('ChatHeader - Run split button menu (execution modes only)', () => {
  it('primary Run click dispatches workflowViewStart with no mockMode key', () => {
    const dispatched: CustomEvent[] = [];
    const listener = (e: Event) => dispatched.push(e as CustomEvent);
    window.addEventListener('workflowViewStart', listener);

    render(<ChatHeader {...baseProps} />);
    fireEvent.click(screen.getAllByTitle('actions.run')[0]);

    expect(dispatched).toHaveLength(1);
    expect(dispatched[0].detail).toEqual({ workflowId: 'wf-1' });
    expect(dispatched[0].detail).not.toHaveProperty('mockMode');

    window.removeEventListener('workflowViewStart', listener);
  });

  it('the menu offers Auto and Step-by-step, and the old mock entries are gone', () => {
    render(<ChatHeader {...baseProps} />);
    openRunMenu();

    expect(screen.getByText('workflowBuilder.canvas.runAuto')).toBeInTheDocument();
    expect(screen.getByText('workflowBuilder.canvas.runStepByStep')).toBeInTheDocument();
    // Absence of the removed run-level mock overrides.
    expect(screen.queryByText('actions.runWithoutMocks')).not.toBeInTheDocument();
    expect(screen.queryByText('actions.dryRunAllMcp')).not.toBeInTheDocument();
  });

  it('Auto menu entry dispatches the same default workflowViewStart', () => {
    const dispatched: CustomEvent[] = [];
    const listener = (e: Event) => dispatched.push(e as CustomEvent);
    window.addEventListener('workflowViewStart', listener);

    render(<ChatHeader {...baseProps} />);
    openRunMenu();
    fireEvent.click(screen.getByText('workflowBuilder.canvas.runAuto'));

    expect(dispatched).toHaveLength(1);
    expect(dispatched[0].detail).toEqual({ workflowId: 'wf-1' });

    window.removeEventListener('workflowViewStart', listener);
  });

  it('Step-by-step menu entry dispatches workflowStartStepByStep', () => {
    const dispatchedSbs: CustomEvent[] = [];
    const dispatchedAuto: CustomEvent[] = [];
    const sbsListener = (e: Event) => dispatchedSbs.push(e as CustomEvent);
    const autoListener = (e: Event) => dispatchedAuto.push(e as CustomEvent);
    window.addEventListener('workflowStartStepByStep', sbsListener);
    window.addEventListener('workflowViewStart', autoListener);

    render(<ChatHeader {...baseProps} />);
    openRunMenu();
    fireEvent.click(screen.getByText('workflowBuilder.canvas.runStepByStep'));

    expect(dispatchedSbs).toHaveLength(1);
    expect(dispatchedSbs[0].detail).toEqual({ workflowId: 'wf-1' });
    expect(dispatchedAuto).toHaveLength(0);

    window.removeEventListener('workflowStartStepByStep', sbsListener);
    window.removeEventListener('workflowViewStart', autoListener);
  });
});
