// @vitest-environment jsdom
/**
 * Landing-page marketplace preview data source (2026-06-18).
 *
 * The public landing showcase is now admin-curated through the LANDING
 * highlights bucket. This pins the two-tier behaviour:
 *   1. when an admin has curated the LANDING row, render exactly those apps
 *      in order and DO NOT hit the generic marketplace endpoint;
 *   2. when the curated row is empty (or its endpoint misses), fall back to
 *      the most recent marketplace publications so the section is never empty.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const routerState = vi.hoisted(() => ({ push: vi.fn() }));

vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: routerState.push }),
}));

// Render a minimal identifiable stand-in for the real card so the test asserts
// the DATA SOURCE / ordering / acquire wiring, not the card markup.
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: ({
    publication,
    onAcquire,
  }: {
    publication: { id: string; title: string };
    onAcquire?: (p: unknown) => void;
  }) => (
    <button data-testid="pub-card" onClick={() => onAcquire?.(publication)}>
      {publication.title}
    </button>
  ),
  PublicationCardSkeleton: () => <div data-testid="pub-skeleton" />,
}));

import MarketplacePreview from '../MarketplacePreview';

const HIGHLIGHTS_URL = '/api/proxy/publications/highlights/LANDING';
const MARKETPLACE_URL = '/api/proxy/publications/marketplace?page=0&size=16';

function jsonResponse(body: unknown, ok = true) {
  return Promise.resolve({ ok, json: async () => body } as Response);
}

let calledUrls: string[];

function mockFetch(handler: (url: string) => Promise<Response>) {
  calledUrls = [];
  global.fetch = vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    calledUrls.push(url);
    return handler(url);
  }) as unknown as typeof fetch;
}

beforeEach(() => {
  vi.clearAllMocks();
  routerState.push.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('MarketplacePreview - data source', () => {
  it('renders the admin-curated LANDING apps in order and never queries the generic marketplace', async () => {
    mockFetch((url) => {
      if (url === HIGHLIGHTS_URL) {
        return jsonResponse({
          displayMode: 'LANDING',
          highlights: [
            { rank: 0, publication: { id: 'a', title: 'Curated First', displayMode: 'APPLICATION' } },
            { rank: 1, publication: { id: 'b', title: 'Curated Second', displayMode: 'APPLICATION' } },
          ],
        });
      }
      return jsonResponse({ publications: [] });
    });

    render(<MarketplacePreview />);

    expect((await screen.findAllByText('Curated First')).length).toBeGreaterThan(0);
    expect((await screen.findAllByText('Curated Second')).length).toBeGreaterThan(0);
    // The curated row was sufficient → the generic marketplace endpoint is untouched.
    expect(calledUrls).toContain(HIGHLIGHTS_URL);
    expect(calledUrls).not.toContain(MARKETPLACE_URL);
  });

  it('falls back to the most recent marketplace publications when the LANDING row is empty', async () => {
    mockFetch((url) => {
      if (url === HIGHLIGHTS_URL) {
        return jsonResponse({ displayMode: 'LANDING', highlights: [] });
      }
      return jsonResponse({ publications: [{ id: 'r', title: 'Recent App', displayMode: 'APPLICATION' }] });
    });

    render(<MarketplacePreview />);

    expect((await screen.findAllByText('Recent App')).length).toBeGreaterThan(0);
    expect(calledUrls).toContain(HIGHLIGHTS_URL);
    expect(calledUrls).toContain(MARKETPLACE_URL);
  });

  it('falls back to the marketplace when the highlights endpoint misses (non-ok)', async () => {
    mockFetch((url) => {
      if (url === HIGHLIGHTS_URL) {
        return jsonResponse({}, false); // e.g. 404 / 5xx
      }
      return jsonResponse({ publications: [{ id: 'r', title: 'Recent Fallback', displayMode: 'APPLICATION' }] });
    });

    render(<MarketplacePreview />);

    expect((await screen.findAllByText('Recent Fallback')).length).toBeGreaterThan(0);
    expect(calledUrls).toContain(MARKETPLACE_URL);
  });

  it('routes a curated APPLICATION card to its preview page on Install (sign-in prompt lives there)', async () => {
    mockFetch((url) => {
      if (url === HIGHLIGHTS_URL) {
        return jsonResponse({
          displayMode: 'LANDING',
          highlights: [
            { rank: 0, publication: { id: 'app-1', title: 'Curated App', displayMode: 'APPLICATION' } },
          ],
        });
      }
      return jsonResponse({ publications: [] });
    });

    render(<MarketplacePreview />);

    const cards = await screen.findAllByText('Curated App');
    (cards[0] as HTMLElement).click();

    expect(routerState.push).toHaveBeenCalledWith('/app/marketplace/app-1/preview');
  });

  it('renders nothing when both sources fail (fail-closed, no empty shell)', async () => {
    mockFetch(() => Promise.reject(new Error('network')));

    const { container } = render(<MarketplacePreview />);

    await waitFor(() => {
      expect(calledUrls).toContain(HIGHLIGHTS_URL);
    });
    // error state → component returns null
    await waitFor(() => {
      expect(container.querySelector('[data-testid="pub-card"]')).toBeNull();
    });
  });
});
