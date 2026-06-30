// @vitest-environment jsdom
//
// REPRODUCTION (Problem 1): the installed-application info panel should surface
// missing credentials (e.g. "serpapi") with the amber "Setup required" block +
// badge. User report: "il me manque serpapi et rien, pas d'info."
//
// Drives the REAL PublicationInfoPanel with an acquired APPLICATION whose plan
// needs a serpapi (api_key) credential the user has NOT connected.
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// --- mocks ---------------------------------------------------------------
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
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ numericUserId: 42, isLoading: false }),
}));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getCommentCount: vi.fn().mockResolvedValue(0),
    getReviews: vi.fn().mockResolvedValue({ reviews: [], totalPages: 0, totalElements: 0 }),
    getMyReview: vi.fn().mockResolvedValue(null),
  },
}));

// User has NO credentials at all → serpapi must surface as missing.
vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getAllCredentials: vi.fn().mockResolvedValue([]),
  },
}));

vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: {
    getWorkflow: vi.fn().mockResolvedValue({ plan: null }),
  },
}));

// Tool batch resolves serpapi with a REQUIRED credential + iconSlug.
vi.mock('@/app/workflows/builder/services/workflowPlanImporter/ToolDataService', () => ({
  ToolDataService: {
    fetchToolsBatch: vi.fn(async () =>
      new Map([
        ['search', { toolData: { iconSlug: 'serpapi', apiName: 'SerpApi', credentials: [{ isRequired: true }] } }],
      ]),
    ),
  },
}));

// Stub heavy children so the panel mounts in jsdom.
vi.mock('@/components/credentials/CredentialWizard', () => ({
  CredentialWizard: () => null,
}));
vi.mock('@/components/applications/ApplicationActivationButton', () => ({
  ApplicationActivationButton: () => null,
}));

import { PublicationInfoPanel } from '../PublicationInfoPanel';

const publication: any = {
  id: 'pub-1',
  title: 'SerpApi Search App',
  description: 'Searches the web.',
  displayMode: 'APPLICATION',
  publisherId: '999',
  publisherName: 'Publisher',
  averageRating: 0,
  reviewCount: 0,
  // Scrubbed published snapshot: serpapi MCP node, no selected credential.
  planSnapshot: {
    mcps: [{ id: 'serpapi/search', label: 'Search', params: {} }],
  },
};

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <NextIntlClientProvider
        locale="en"
        messages={{} as any}
        onError={() => {}}
        getMessageFallback={({ key }) => key}
      >
        <PublicationInfoPanel publication={publication} acquiredWorkflowId="acquired-wf-1" />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('PublicationInfoPanel - surfaces missing serpapi credential (repro)', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows the Setup-required block listing SerpApi when the user has no matching credential', async () => {
    renderPanel();
    // The amber block lists wizardable service names joined by " · " - SerpApi
    // must appear once detection + surfacing resolve.
    expect(await screen.findByText(/SerpApi/i, undefined, { timeout: 4000 })).toBeTruthy();
  });
});
