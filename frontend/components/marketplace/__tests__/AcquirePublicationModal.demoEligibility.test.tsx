// @vitest-environment jsdom
/**
 * The single wire that makes admin demo mode real: this modal is the ONLY entry
 * point into the install machine, so whether it forwards `demo` decides whether
 * a demo install acquires for real. Nothing else in the feature can catch that
 * line going missing, which is why it is pinned here on its own.
 *
 * It also pins the opt-in: `demoEligible` defaults to false, so the chat's
 * agent-driven install (which answers a real tool authorization and would DENY
 * it on close, because a demo success leaves no acquired id) never simulates.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));

const svc = vi.hoisted(() => ({
  acquireRemotePublication: vi.fn(),
  acquireAgentPublication: vi.fn(),
  acquireResourcePublication: vi.fn(),
  acquirePublication: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: svc }));

const demoMode = vi.hoisted(() => ({ on: false }));
vi.mock('@/lib/marketplace/demoInstallMode', () => ({
  useMarketplaceDemoInstall: () => demoMode.on,
}));

const startInstall = vi.hoisted(() => vi.fn(() => true));
vi.mock('@/lib/stores/marketplace-install-store', () => {
  const state = { active: null, startInstall, clear: vi.fn(), consumeSuccess: vi.fn() };
  // The component both selects from the hook AND reads getState() in an effect.
  const useStore = (selector: (s: unknown) => unknown) => selector(state);
  useStore.getState = () => state;
  return { useMarketplaceInstallStore: useStore };
});

import AcquirePublicationModal from '../AcquirePublicationModal';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'Invoice to Client',
    creditsPerUse: 0,
    publicationType: 'WORKFLOW',
    displayMode: 'WORKFLOW',
    ...overrides,
  } as WorkflowPublication;
}

function confirm(props: Partial<React.ComponentProps<typeof AcquirePublicationModal>> = {}) {
  render(<AcquirePublicationModal isOpen onClose={() => {}} publication={pub()} {...props} />);
  fireEvent.click(screen.getByRole('button', { name: 'addToApplications' }));
}

describe('AcquirePublicationModal - demo eligibility', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    startInstall.mockReturnValue(true);
    demoMode.on = false;
  });
  afterEach(() => cleanup());

  it('forwards demo:false when the mode is off, so the install acquires for real', () => {
    confirm({ demoEligible: true });
    expect(startInstall).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ demo: false }));
  });

  it('forwards demo:true on an eligible surface when the mode is on', () => {
    demoMode.on = true;
    confirm({ demoEligible: true });
    expect(startInstall).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ demo: true }));
  });

  it('does NOT simulate on a surface that did not opt in, even with the mode on', () => {
    // The default. ChatCore relies on it: a simulated success there would leave
    // onSuccess unfired and closing the modal would deny the tool authorization.
    demoMode.on = true;
    confirm();
    expect(startInstall).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ demo: false }));
  });

  it('does not simulate when the surface opts in but the mode is off', () => {
    confirm({ demoEligible: true });
    expect(startInstall).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ demo: false }));
  });
});
