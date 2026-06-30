// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('@/hooks/useUserProfile', () => ({
  useUserProfile: () => ({ profile: { id: 'viewer-1' } }),
}));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/',
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ numericUserId: 999 }),
}));
vi.mock('@/hooks/useMissingCredentials', () => ({
  useMissingCredentials: () => ({ count: 0, wizardable: [], manual: [], refetch: vi.fn() }),
}));

vi.mock('@/components/ui/tabs', async () => {
  const ReactLib = await import('react');
  const TabsContext = ReactLib.createContext<any>(null);
  return {
    Tabs: ({ children, value, onValueChange }: any) =>
      ReactLib.createElement(TabsContext.Provider, { value: { value, onValueChange } },
        ReactLib.createElement('div', null, children)),
    TabsList: ({ children }: any) => ReactLib.createElement('div', { role: 'tablist' }, children),
    TabsTrigger: ({ children, value }: any) => {
      const ctx = ReactLib.useContext(TabsContext);
      return ReactLib.createElement('button', {
        type: 'button', role: 'tab', 'aria-selected': ctx?.value === value,
        onClick: () => ctx?.onValueChange(value),
      }, children);
    },
    TabsContent: ({ children, value }: any) => {
      const ctx = ReactLib.useContext(TabsContext);
      return ctx?.value === value
        ? ReactLib.createElement('div', { role: 'tabpanel', 'data-value': value }, children)
        : null;
    },
  };
});

const publicationServiceMocks = vi.hoisted(() => ({
  getCommentCount: vi.fn(),
  getMyReview: vi.fn(),
  getReviews: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getCommentCount: (...a: unknown[]) => publicationServiceMocks.getCommentCount(...a),
    getMyReview: (...a: unknown[]) => publicationServiceMocks.getMyReview(...a),
    getReviews: (...a: unknown[]) => publicationServiceMocks.getReviews(...a),
  },
}));
vi.mock('@/components/credentials/CredentialWizard', () => ({ CredentialWizard: () => null }));
vi.mock('@/components/applications/ApplicationActivationButton', () => ({ ApplicationActivationButton: () => null }));

import { PublicationInfoPanel } from '../PublicationInfoPanel';

function publication(): WorkflowPublication {
  return {
    id: 'pub-1',
    workflowId: 'workflow-1',
    title: 'Weather App',
    description: 'Forecast workflow',
    creditsPerUse: 0,
    publisherId: 'publisher-1',
    publisherName: 'Publisher Co',
    status: 'ACTIVE',
    visibility: 'PUBLIC',
    useCount: 12,
    totalCreditsEarned: 0,
    averageRating: 4.5,
    reviewCount: 3,
    publishedAt: '2026-05-22T10:00:00Z',
  } as WorkflowPublication;
}

describe('PublicationInfoPanel floating click-outside guard', () => {
  beforeEach(() => {
    publicationServiceMocks.getCommentCount.mockResolvedValue(0);
    publicationServiceMocks.getMyReview.mockResolvedValue(null);
    publicationServiceMocks.getReviews.mockResolvedValue({ reviews: [], totalPages: 0, totalElements: 0 });
  });
  afterEach(() => {
    cleanup();
    document.querySelectorAll('[data-test-portal]').forEach((el) => el.remove());
  });

  it('does NOT close when the click lands inside a portaled Radix popper (the publisher menu)', async () => {
    render(<PublicationInfoPanel publication={publication()} defaultOpen floating />);
    // The Info tab description proves the panel is open.
    expect(await screen.findByText('Forecast workflow')).toBeInTheDocument();

    // Simulate the publisher "View profile / Send message" menu, which Radix
    // Popover portals to document.body OUTSIDE the panel's floating wrapper.
    const popper = document.createElement('div');
    popper.setAttribute('data-radix-popper-content-wrapper', '');
    popper.setAttribute('data-test-portal', '');
    const item = document.createElement('button');
    popper.appendChild(item);
    document.body.appendChild(popper);

    fireEvent.mouseDown(item);

    // Pre-fix: the panel unmounted here, killing the menu before its click fired.
    expect(screen.getByText('Forecast workflow')).toBeInTheDocument();
  });

  it('still closes on a genuine outside click (control - guard is not a blanket no-op)', async () => {
    render(<PublicationInfoPanel publication={publication()} defaultOpen floating />);
    expect(await screen.findByText('Forecast workflow')).toBeInTheDocument();

    const outside = document.createElement('div');
    outside.setAttribute('data-test-portal', '');
    document.body.appendChild(outside);

    fireEvent.mouseDown(outside);

    await waitFor(() => expect(screen.queryByText('Forecast workflow')).not.toBeInTheDocument());
  });
});
