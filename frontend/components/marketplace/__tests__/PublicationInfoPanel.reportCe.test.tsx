// @vitest-environment jsdom
/**
 * CE variant of the report-tab test: a cloud-linked CE renders CLOUD-hosted
 * publications, so the report link, the page URL embedded in the ticket, and the
 * Terms link must all point at the cloud origin (https://livecontext.ai) - never
 * the local self-hosted install (localhost). The cloud-build (no-op) behaviour is
 * covered by PublicationInfoPanel.report.test.tsx; the rewriter logic itself by
 * lib/edition/__tests__/cloudWebUrl.test.ts.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

// Force CE edition and provide a faithful cloudWebUrl (the real logic lives in
// lib/edition and is unit-tested separately). This proves the component routes
// every report-tab link through cloudWebUrl, landing them on the cloud origin.
vi.mock('@/lib/edition', () => ({
  IS_CE: true,
  cloudWebUrl: (pathOrUrl: string) => {
    if (!pathOrUrl) return pathOrUrl;
    try {
      const resolved = new URL(pathOrUrl, 'http://localhost:3000');
      return `https://livecontext.ai${resolved.pathname}${resolved.search}${resolved.hash}`;
    } catch {
      return pathOrUrl;
    }
  },
}));

vi.mock('@/hooks/useUserProfile', () => ({
  useUserProfile: () => ({ profile: { id: 'viewer-1' } }),
}));
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/',
}));
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
      reportSeeTerms: 'reportSeeTerms',
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
  useMissingCredentials: () => ({ count: 0, wizardable: [], manual: [], refetch: vi.fn() }),
}));

vi.mock('@/components/ui/tabs', async () => {
  const ReactMod = await import('react');
  const TabsContext = ReactMod.createContext<any>(null);
  return {
    Tabs: ({ children, value, onValueChange }: { children: React.ReactNode; value: string; onValueChange: (value: string) => void }) =>
      ReactMod.createElement(TabsContext.Provider, { value: { value, onValueChange } },
        ReactMod.createElement('div', null, children)),
    TabsList: ({ children }: { children: React.ReactNode }) =>
      ReactMod.createElement('div', { role: 'tablist' }, children),
    TabsTrigger: ({ children, value }: { children: React.ReactNode; value: string }) => {
      const ctx = ReactMod.useContext(TabsContext);
      return ReactMod.createElement('button', {
        type: 'button',
        role: 'tab',
        'aria-selected': ctx?.value === value,
        onClick: () => ctx?.onValueChange(value),
      }, children);
    },
    TabsContent: ({ children, value }: { children: React.ReactNode; value: string }) => {
      const ctx = ReactMod.useContext(TabsContext);
      return ctx?.value === value
        ? ReactMod.createElement('div', { role: 'tabpanel', 'data-value': value }, children)
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
  };
}

describe('PublicationInfoPanel report tab - CE points at the cloud, not localhost', () => {
  beforeEach(() => {
    publicationServiceMocks.getCommentCount.mockResolvedValue(0);
    publicationServiceMocks.getMyReview.mockResolvedValue(null);
    publicationServiceMocks.getReviews.mockResolvedValue({ reviews: [], totalPages: 0, totalElements: 0 });
    window.history.pushState({}, '', '/en/app/marketplace/pub-1/preview');
  });

  it('routes the report contact link to the cloud origin', async () => {
    render(<PublicationInfoPanel publication={publication()} defaultOpen hideActivationButton />);

    fireEvent.click(screen.getByRole('tab', { name: /reportTab/i }));

    const link = await screen.findByRole('link', { name: /reportEmailButton/i });
    const href = link.getAttribute('href') ?? '';
    expect(href.startsWith('https://livecontext.ai/contact?category=abuse&message=')).toBe(true);
    expect(href).not.toContain('localhost');

    // The page URL embedded in the prefilled ticket is the cloud URL of this
    // publication, not the local install's localhost URL.
    const encodedMessage = new URL(href).searchParams.get('message') ?? '';
    expect(encodedMessage).toContain('URL: https://livecontext.ai/en/app/marketplace/pub-1/preview');
    expect(encodedMessage).not.toContain('localhost');
  });

  it('routes the "see Terms" link to the cloud origin', async () => {
    render(<PublicationInfoPanel publication={publication()} defaultOpen hideActivationButton />);

    fireEvent.click(screen.getByRole('tab', { name: /reportTab/i }));

    const termsLink = await screen.findByRole('link', { name: /reportSeeTerms/i });
    expect(termsLink.getAttribute('href')).toBe('https://livecontext.ai/legal/terms');

    await waitFor(() => {
      expect(publicationServiceMocks.getCommentCount).toHaveBeenCalledWith('pub-1');
    });
  });
});
