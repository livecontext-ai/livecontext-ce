// @vitest-environment jsdom
/**
 * RBAC hardening (2026-07-02): the workflow Run button is hidden for org
 * VIEWERs. POST /v2/workflows/dag/execute auto-saves the plan, so the backend
 * (fail-closed canWrite gate) rejects VIEWER with 403 "Workflow access is
 * read-only" - showing Run would dead-end. MEMBER (and the personal workspace)
 * keeps it, and clicking it still dispatches the `workflowViewStart` event the
 * execution hook listens for.
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

beforeEach(() => {
  orgMutationGate.canMutate = true;
});
afterEach(cleanup);

describe('ChatHeader - workflow Run button VIEWER gate', () => {
  it('MEMBER: the Run button renders (desktop + mobile) and dispatches workflowViewStart', () => {
    const dispatched: CustomEvent[] = [];
    const listener = (e: Event) => dispatched.push(e as CustomEvent);
    window.addEventListener('workflowViewStart', listener);

    render(<ChatHeader {...baseProps} />);
    const runButtons = screen.getAllByTitle('actions.run');
    expect(runButtons.length).toBeGreaterThan(0);

    fireEvent.click(runButtons[0]);
    expect(dispatched).toHaveLength(1);
    expect(dispatched[0].detail).toMatchObject({ workflowId: 'wf-1' });

    window.removeEventListener('workflowViewStart', listener);
  });

  it('VIEWER: no Run button at all (the execute endpoint 403s read-only roles)', () => {
    orgMutationGate.canMutate = false;
    render(<ChatHeader {...baseProps} />);
    expect(screen.queryByTitle('actions.run')).not.toBeInTheDocument();
  });

  it('VIEWER: the read affordances of the workflow header stay (Share button)', () => {
    orgMutationGate.canMutate = false;
    render(<ChatHeader {...baseProps} />);
    expect(screen.getAllByTitle('actions.share').length).toBeGreaterThan(0);
  });
});
