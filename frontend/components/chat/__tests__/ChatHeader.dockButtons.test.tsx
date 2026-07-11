// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Desktop header dock buttons: the header always shows TWO panel buttons - bottom
 * dock, then right dock at the far edge. Behavior under test:
 *  - both buttons render whenever a toggle handler is provided
 *  - clicking a button while the panel is CLOSED sets the dock position (bottom uses
 *    the bottomMode preference, default 'bottom-full') and calls the toggle (opens)
 *  - clicking the ACTIVE dock's button while open calls the toggle (closes)
 *  - clicking the OTHER dock's button while open only repositions (no toggle call)
 *  - the active dock's button is aria-pressed while the panel is open
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

const POSITION_KEY = 'lc.sidePanel.position:personal';
const BOTTOM_MODE_KEY = 'lc.sidePanel.bottomMode:personal';

const onToggle = vi.fn();

const baseProps = {
  selectedModel: { provider: 'openai', id: 'gpt-4' },
  onModelChange: vi.fn(),
  availableModels: [],
  showModelSelector: false,
  onShowModelSelector: vi.fn(),
  sidebarOpen: false,
  onSidebarToggle: vi.fn(),
  onToggleAgentConfigPanel: onToggle,
  showAgentConfigPanel: false,
} as unknown as React.ComponentProps<typeof ChatHeader>;

beforeEach(() => {
  onToggle.mockClear();
  window.localStorage.clear();
  act(() => useCurrentOrgStore.getState().clear());
});
afterEach(cleanup);

function renderHeader(props: Partial<React.ComponentProps<typeof ChatHeader>> = {}) {
  render(
    <SidePanelLayoutProvider>
      <ChatHeader {...baseProps} {...props} />
    </SidePanelLayoutProvider>,
  );
}

const bottomBtn = () => screen.getByTestId('dock-toggle-bottom');
const rightBtn = () => screen.getByTestId('dock-toggle-right');

describe('ChatHeader desktop dock buttons', () => {
  it('renders both dock buttons when a toggle handler is provided', () => {
    renderHeader();
    expect(bottomBtn()).toBeInTheDocument();
    expect(rightBtn()).toBeInTheDocument();
  });

  it('renders the bottom button immediately LEFT of the right button (DOM order)', () => {
    renderHeader();
    // DOCUMENT_POSITION_FOLLOWING (4): rightBtn comes after bottomBtn in the DOM,
    // so the right-dock button sits at the far edge of the header.
    expect(bottomBtn().compareDocumentPosition(rightBtn()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('shows an open tooltip when closed and a close tooltip on the active dock when open', () => {
    renderHeader({ isSidePanelOpen: true });
    // default position 'right' -> right button closes, bottom button opens
    expect(rightBtn()).toHaveAttribute('title', 'common.close');
    expect(bottomBtn()).toHaveAttribute('title', 'sidePanel.openBottom');
  });

  it('renders neither dock button without a toggle handler', () => {
    renderHeader({ onToggleAgentConfigPanel: undefined });
    expect(screen.queryByTestId('dock-toggle-bottom')).not.toBeInTheDocument();
    expect(screen.queryByTestId('dock-toggle-right')).not.toBeInTheDocument();
  });

  it('keeps both dock buttons visible while the panel is open', () => {
    renderHeader({ isSidePanelOpen: true, showAgentConfigPanel: true });
    expect(bottomBtn()).toBeInTheDocument();
    expect(rightBtn()).toBeInTheDocument();
  });

  it('closed + right click: docks right and opens via the toggle', () => {
    renderHeader();
    act(() => rightBtn().click());
    expect(window.localStorage.getItem(POSITION_KEY)).toBe('right');
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it('closed + bottom click: docks bottom-full (default bottomMode) and opens', () => {
    renderHeader();
    act(() => bottomBtn().click());
    expect(window.localStorage.getItem(POSITION_KEY)).toBe('bottom-full');
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it('closed + bottom click honors a stored bottomMode of content-width bottom', () => {
    window.localStorage.setItem(BOTTOM_MODE_KEY, 'bottom');
    renderHeader();
    act(() => bottomBtn().click());
    expect(window.localStorage.getItem(POSITION_KEY)).toBe('bottom');
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it('open on right + bottom click: repositions to the bottom dock WITHOUT toggling', () => {
    window.localStorage.setItem(POSITION_KEY, 'right');
    renderHeader({ isSidePanelOpen: true });
    act(() => bottomBtn().click());
    expect(window.localStorage.getItem(POSITION_KEY)).toBe('bottom-full');
    expect(onToggle).not.toHaveBeenCalled();
  });

  it('open on bottom + right click: repositions to the right dock WITHOUT toggling', () => {
    window.localStorage.setItem(POSITION_KEY, 'bottom-full');
    renderHeader({ isSidePanelOpen: true });
    act(() => rightBtn().click());
    expect(window.localStorage.getItem(POSITION_KEY)).toBe('right');
    expect(onToggle).not.toHaveBeenCalled();
  });

  it('open on right + right click: calls the toggle (close)', () => {
    window.localStorage.setItem(POSITION_KEY, 'right');
    renderHeader({ isSidePanelOpen: true });
    act(() => rightBtn().click());
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it('open on a bottom dock + bottom click: calls the toggle (close)', () => {
    window.localStorage.setItem(POSITION_KEY, 'bottom');
    window.localStorage.setItem(BOTTOM_MODE_KEY, 'bottom');
    renderHeader({ isSidePanelOpen: true });
    act(() => bottomBtn().click());
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it('marks the active dock button aria-pressed while open (right)', () => {
    window.localStorage.setItem(POSITION_KEY, 'right');
    renderHeader({ isSidePanelOpen: true });
    expect(rightBtn()).toHaveAttribute('aria-pressed', 'true');
    expect(bottomBtn()).toHaveAttribute('aria-pressed', 'false');
  });

  it('marks the active dock button aria-pressed while open (bottom-full)', () => {
    window.localStorage.setItem(POSITION_KEY, 'bottom-full');
    renderHeader({ isSidePanelOpen: true });
    expect(bottomBtn()).toHaveAttribute('aria-pressed', 'true');
    expect(rightBtn()).toHaveAttribute('aria-pressed', 'false');
  });

  it('neither button is pressed while the panel is closed', () => {
    renderHeader();
    expect(bottomBtn()).toHaveAttribute('aria-pressed', 'false');
    expect(rightBtn()).toHaveAttribute('aria-pressed', 'false');
  });
});
