// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const mockPush = vi.hoisted(() => vi.fn());
const mockApiGet = vi.hoisted(() => vi.fn());
const mockGetSuggested = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: mockPush }),
}));

vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ user: { sub: 'u1' }, isLoading: false }),
}));

vi.mock('@/lib/api', () => ({ apiClient: { get: mockApiGet } }));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getSuggestedApplications: mockGetSuggested },
}));

// The card itself is the shared marketplace PublicationCard (tested via the
// marketplace). Stub it so these tests focus on the modal's sequencing/fetch.
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: ({ publication }: { publication: { title: string } }) => (
    <div data-testid="pub-card">{publication.title}</div>
  ),
}));

import SuggestedAppsModal from '../SuggestedAppsModal';
import {
  WELCOME_GIFT_FLAG,
  notifyWelcomeGiftDone,
} from '@/lib/onboarding/welcomeGiftHandoff';

function renderModal() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <SuggestedAppsModal />
    </QueryClientProvider>,
  );
}

const SAMPLE_APP = {
  id: 'pub1',
  title: 'CRM Sync',
  description: 'Keep your CRM in sync',
  category: { id: 'c1', slug: 'sales-crm', name: 'Sales & CRM' },
  nodeIcons: [{ iconSlug: 'salesforce' }],
};

describe('SuggestedAppsModal', () => {
  beforeEach(() => {
    sessionStorage.clear();
    mockPush.mockReset();
    mockApiGet.mockReset();
    mockGetSuggested.mockReset();
    mockApiGet.mockResolvedValue({
      interests: ['sales-crm'],
      useCases: [],
      profession: 'sales',
      primaryGoal: 'lead-generation',
    });
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
  });

  // Also the regression for the hand-off that once went stale: this modal used
  // to wait UNCONDITIONALLY for a `lc:welcome-gift-done` event from a
  // credit-gift modal that had since been deleted, so it armed and then never
  // opened - a silent disappearance, since nothing errors and nothing logs. It
  // waits again today, but only while the flag says something is actually owed.
  it('arms on the onboarding flag alone when no welcome gift is owed', async () => {
    sessionStorage.setItem('lc_show_app_suggestions', '1');
    mockGetSuggested.mockResolvedValue({ count: 1, publications: [SAMPLE_APP] });

    renderModal();

    // The show-flag is consumed on arm so it never re-fires.
    await waitFor(() => expect(sessionStorage.getItem('lc_show_app_suggestions')).toBeNull());
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('CRM Sync')).toBeInTheDocument();
    expect(mockGetSuggested).toHaveBeenCalledWith({
      interests: ['sales-crm'],
      useCases: [],
      profession: 'sales',
      primaryGoal: 'lead-generation',
      limit: 4,
    });
  });

  it('closes the modal when a suggested card is clicked (card Link handles navigation)', async () => {
    sessionStorage.setItem('lc_show_app_suggestions', '1');
    mockGetSuggested.mockResolvedValue({ count: 1, publications: [SAMPLE_APP] });

    renderModal();

    const card = await screen.findByTestId('pub-card');
    fireEvent.click(card);

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('"Browse the marketplace" CTA navigates to the marketplace and closes', async () => {
    sessionStorage.setItem('lc_show_app_suggestions', '1');
    mockGetSuggested.mockResolvedValue({ count: 1, publications: [SAMPLE_APP] });

    renderModal();

    const cta = await screen.findByRole('button', { name: /cta/i });
    fireEvent.click(cta);

    expect(mockPush).toHaveBeenCalledWith('/en/app/marketplace');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('stays hidden when the backend returns no suggestions', async () => {
    sessionStorage.setItem('lc_show_app_suggestions', '1');
    mockGetSuggested.mockResolvedValue({ count: 0, publications: [] });

    renderModal();

    await waitFor(() => expect(mockGetSuggested).toHaveBeenCalled());
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('does not arm or fetch without the onboarding flag', async () => {
    mockGetSuggested.mockResolvedValue({ count: 1, publications: [SAMPLE_APP] });

    renderModal();

    // Give effects a chance to run.
    await Promise.resolve();
    expect(mockGetSuggested).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('waits behind the welcome gift, then opens once the reader is done with it', async () => {
    // Onboarding arms both. Opening on the same paint would put this on top of
    // the card that states what the account's credits and AI allowance are,
    // which is the thing a brand-new account should read first.
    sessionStorage.setItem('lc_show_app_suggestions', '1');
    sessionStorage.setItem(WELCOME_GIFT_FLAG, '1');
    mockGetSuggested.mockResolvedValue({ count: 1, publications: [SAMPLE_APP] });

    renderModal();

    // Real time has to pass here, and that is the whole test. The direct path
    // arms on a setTimeout(0) and then fetches, so an assertion that only
    // flushed microtasks would hold against a modal that does not wait at all -
    // it would simply be running before the timer. Waiting past it is what
    // distinguishes "parked" from "not there yet".
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(mockGetSuggested).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    // And it has not spent its own flag while parked: a reader who reloads
    // while reading the gift still gets both, in the same order.
    expect(sessionStorage.getItem('lc_show_app_suggestions')).toBe('1');

    notifyWelcomeGiftDone();

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('CRM Sync')).toBeInTheDocument();
    // Claimed on arming, so it opens once and not again.
    expect(sessionStorage.getItem('lc_show_app_suggestions')).toBeNull();
  });

  it('claims its flag on the direct path too, so it never re-fires', async () => {
    sessionStorage.setItem('lc_show_app_suggestions', '1');
    mockGetSuggested.mockResolvedValue({ count: 1, publications: [SAMPLE_APP] });

    renderModal();

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(sessionStorage.getItem('lc_show_app_suggestions')).toBeNull();
  });
});
