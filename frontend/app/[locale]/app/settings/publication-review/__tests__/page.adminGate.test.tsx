// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';

// --- controllable test state -----------------------------------------------
let mockIsCe = false;
let mockHasRole: (role: string) => boolean = () => false;
const getPendingPublications = vi.fn();
const getModerationStats = vi.fn();
const getOrganizations = vi.fn(); // must NEVER be called by the admin gate

vi.mock('@/lib/edition', () => ({
  get IS_CE() { return mockIsCe; },
  get IS_CLOUD() { return !mockIsCe; },
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ hasRole: (r: string) => mockHasRole(r) }),
}));
// Regression guard: the page must NOT resolve admin access from org membership.
vi.mock('@/lib/api', () => ({
  organizationApi: { getOrganizations: (...a: unknown[]) => getOrganizations(...a) },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getPendingPublications: (...a: unknown[]) => getPendingPublications(...a),
    getModerationStats: (...a: unknown[]) => getModerationStats(...a),
  },
}));
vi.mock('@/components/settings/PageHeader', () => ({
  PageHeader: ({ title }: { title: string }) => <h1>{title}</h1>,
}));
vi.mock('@/components/Toast', () => ({
  default: () => null,
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('./components/PublicationComparisonView', () => ({ default: () => null }));

import PublicationReviewPage from '../page';

function renderPage() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as any}>
      <PublicationReviewPage />
    </NextIntlClientProvider>,
  );
}

const UNAUTHORIZED = 'Only administrators can review publications';
const CE_BODY = 'Publication review is a cloud-only feature.';

describe('PublicationReviewPage - admin access gate', () => {
  beforeEach(() => {
    mockIsCe = false;
    mockHasRole = () => false;
    getPendingPublications.mockResolvedValue({ publications: [], totalPages: 0 });
    getModerationStats.mockResolvedValue({ pendingCount: 0 });
    getOrganizations.mockResolvedValue([]);
  });
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('grants access to a platform ADMIN (cloud) - backend gates on the platform role, so the UI must too', async () => {
    mockHasRole = (r) => r === 'ADMIN';
    renderPage();
    // Header renders → not the unauthorized screen.
    await waitFor(() => expect(screen.getByText('Publication Review')).toBeInTheDocument());
    expect(screen.queryByText(UNAUTHORIZED)).not.toBeInTheDocument();
    expect(screen.queryByText(CE_BODY)).not.toBeInTheDocument();
  });

  it('regression: does NOT consult org membership (getOrganizations) to decide admin access', async () => {
    mockHasRole = (r) => r === 'ADMIN';
    renderPage();
    await waitFor(() => expect(screen.getByText('Publication Review')).toBeInTheDocument());
    // The previous bug gated on defaultOrg.currentUserRole - a platform admin on
    // a non-owned workspace was wrongly denied. The fix must never call this.
    expect(getOrganizations).not.toHaveBeenCalled();
  });

  it('denies a non-admin (cloud) with the unauthorized screen', async () => {
    mockHasRole = () => false;
    renderPage();
    await waitFor(() => expect(screen.getByText(UNAUTHORIZED)).toBeInTheDocument());
    expect(screen.queryByText('Publication Review')).not.toBeInTheDocument();
  });

  it('hides the feature in CE (cloud-only) even for a platform ADMIN', async () => {
    mockIsCe = true;
    mockHasRole = (r) => r === 'ADMIN';
    renderPage();
    await waitFor(() => expect(screen.getByText(CE_BODY)).toBeInTheDocument());
    // Never the moderation queue, never the org-role "Admin access required" screen.
    expect(screen.queryByText('Publication Review')).not.toBeInTheDocument();
    expect(screen.queryByText(UNAUTHORIZED)).not.toBeInTheDocument();
    expect(getPendingPublications).not.toHaveBeenCalled();
  });
});
