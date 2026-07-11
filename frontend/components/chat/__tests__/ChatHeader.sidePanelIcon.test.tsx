// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The MOBILE header toggle (single button - the panel is a fixed overlay on mobile,
 * so there are no per-dock buttons there) must mirror the active dock position:
 * 'right' -> PanelRight, and EVERY bottom variant ('bottom', 'bottom-full') -> PanelBottom.
 * This pins that mapping (the 'bottom-full' case is the one previously missed).
 * The desktop dock buttons are covered by ChatHeader.dockButtons.test.tsx.
 */
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  usePathname: () => '/en/app/c/conv-1',
}));
vi.mock('next/image', () => ({
  // eslint-disable-next-line @next/next/no-img-element
  default: ({ src, alt }: { src: string; alt: string }) => <img src={src} alt={alt} />,
}));
vi.mock('@/hooks/useModels', () => ({
  modelMatches: () => false,
  selectedModelFromAIModel: (m: unknown) => m,
}));
vi.mock('@/components/ai/ModelInfo', () => ({ ModelOptionDisplay: () => null, ModelInfoPopover: () => null }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ user: null, isAuthenticated: true, isLoading: false }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ isRunMode: false }) }));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
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
  AvatarDisplay: ({ name }: { name?: string }) => <div>{name}</div>,
}));

import { ChatHeader } from '../ChatHeader';
import { SidePanelLayoutProvider } from '@/contexts/SidePanelLayoutContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

const baseProps = {
  selectedModel: { provider: 'openai', id: 'gpt-4' },
  onModelChange: vi.fn(),
  availableModels: [],
  showModelSelector: false,
  onShowModelSelector: vi.fn(),
  sidebarOpen: false,
  onSidebarToggle: vi.fn(),
  onToggleAgentConfigPanel: vi.fn(),
  showAgentConfigPanel: false,
} as unknown as React.ComponentProps<typeof ChatHeader>;

beforeEach(() => {
  window.localStorage.clear();
  act(() => useCurrentOrgStore.getState().clear());
});
afterEach(cleanup);

/** The lucide icon class inside the MOBILE panel-toggle button (title = sidePanel.addTab key). */
function toggleIconClass(): string {
  const btn = screen.getAllByTitle('sidePanel.addTab')[0];
  return (btn.querySelector('svg') as SVGElement).getAttribute('class') || '';
}

function renderWithPosition(position?: string) {
  if (position) window.localStorage.setItem('lc.sidePanel.position:personal', position);
  render(
    <SidePanelLayoutProvider>
      <ChatHeader {...baseProps} />
    </SidePanelLayoutProvider>,
  );
}

describe('ChatHeader right-panel toggle icon mirrors the dock position', () => {
  it('right (default): PanelRight', () => {
    renderWithPosition();
    expect(toggleIconClass()).toContain('lucide-panel-right');
  });

  it('bottom: PanelBottom', () => {
    renderWithPosition('bottom');
    expect(toggleIconClass()).toContain('lucide-panel-bottom');
  });

  it('bottom-full: PanelBottom (not PanelRight)', () => {
    renderWithPosition('bottom-full');
    const cls = toggleIconClass();
    expect(cls).toContain('lucide-panel-bottom');
    expect(cls).not.toContain('lucide-panel-right');
  });
});
