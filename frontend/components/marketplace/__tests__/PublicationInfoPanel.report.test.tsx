// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

// UserActionMenu (publisher/reviewer chips) pulls the locale-aware navigation;
// next-intl's createNavigation cannot resolve next/navigation under vitest.
// UserActionMenu resolves "is this me?" through the profile hook.
vi.mock('@/hooks/useUserProfile', () => ({
  useUserProfile: () => ({ profile: { id: 'viewer-1' } }),
}));
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/',
}));
// UserActionMenu deliberately uses plain next/navigation (see its header comment).
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/',
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, string>) => {
    const translations: Record<string, string> = {
      infoTab: 'infoTab',
      commentsTab: 'commentsTab',
      reportTab: 'reportTab',
      reportEmailButton: 'reportEmailButton',
      reportBodyPublication: `Publication: ${values?.title ?? ''}`,
      reportBodyId: `ID: ${values?.id ?? ''}`,
      reportBodyUrl: `URL: ${values?.url ?? ''}`,
      reportBodyPublisher: `Publisher: ${values?.publisher ?? ''}`,
      reportBodyReasonHeading: 'Reason for reporting:',
      reportBodyReasonPlaceholder: '[Please describe the alleged infringement.]',
      reportBodyReporterHeading: 'Reporter identity (required):',
      reportBodyReporterPlaceholder: '[Full name / company, address, contact details]',
      reportUntitled: '(untitled)',
      reportUnknownPublisher: '(unknown)',
    };
    return translations[key] ?? key;
  },
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ numericUserId: 999 }),
}));

vi.mock('@/hooks/useMissingCredentials', () => ({
  useMissingCredentials: () => ({
    count: 0,
    wizardable: [],
    manual: [],
    refetch: vi.fn(),
  }),
}));

vi.mock('@/components/ui/tabs', async () => {
  const React = await import('react');
  const TabsContext = React.createContext<any>(null);
  return {
    Tabs: ({ children, value, onValueChange }: { children: React.ReactNode; value: string; onValueChange: (value: string) => void }) =>
      React.createElement(TabsContext.Provider, { value: { value, onValueChange } },
        React.createElement('div', null, children)),
    TabsList: ({ children }: { children: React.ReactNode }) =>
      React.createElement('div', { role: 'tablist' }, children),
    TabsTrigger: ({ children, value }: { children: React.ReactNode; value: string }) => {
      const ctx = React.useContext(TabsContext);
      return React.createElement('button', {
        type: 'button',
        role: 'tab',
        'aria-selected': ctx?.value === value,
        onClick: () => ctx?.onValueChange(value),
      }, children);
    },
    TabsContent: ({ children, value }: { children: React.ReactNode; value: string }) => {
      const ctx = React.useContext(TabsContext);
      return ctx?.value === value
        ? React.createElement('div', { role: 'tabpanel', 'data-value': value }, children)
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
    getCommentCount: (...args: unknown[]) => publicationServiceMocks.getCommentCount(...args),
    getMyReview: (...args: unknown[]) => publicationServiceMocks.getMyReview(...args),
    getReviews: (...args: unknown[]) => publicationServiceMocks.getReviews(...args),
  },
}));

vi.mock('@/components/credentials/CredentialWizard', () => ({
  CredentialWizard: () => null,
}));

vi.mock('@/components/applications/ApplicationActivationButton', () => ({
  ApplicationActivationButton: () => null,
}));

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
  };
}

describe('PublicationInfoPanel report tab', () => {
  beforeEach(() => {
    publicationServiceMocks.getCommentCount.mockResolvedValue(0);
    publicationServiceMocks.getMyReview.mockResolvedValue(null);
    publicationServiceMocks.getReviews.mockResolvedValue({ reviews: [], totalPages: 0, totalElements: 0 });
    window.history.pushState({}, '', '/marketplace/pub-1');
  });

  it('builds an abuse contact link with publication id, URL and publisher context', async () => {
    render(
      <PublicationInfoPanel
        publication={publication()}
        defaultOpen
        hideActivationButton
      />,
    );

    fireEvent.click(screen.getByRole('tab', { name: /reportTab/i }));

    const link = await screen.findByRole('link', { name: /reportEmailButton/i });
    expect(link.getAttribute('href')).toContain('/contact?category=abuse&message=');

    const href = link.getAttribute('href') ?? '';
    const encodedMessage = new URL(href, window.location.href).searchParams.get('message') ?? '';
    expect(encodedMessage).toContain('Publication: Weather App');
    expect(encodedMessage).toContain('ID: pub-1');
    expect(encodedMessage).toContain('URL: http://localhost:3000/marketplace/pub-1');
    expect(encodedMessage).toContain('Publisher: Publisher Co');
    expect(encodedMessage).toContain('Reason for reporting:');

    await waitFor(() => {
      expect(publicationServiceMocks.getCommentCount).toHaveBeenCalledWith('pub-1');
    });
  });
});
