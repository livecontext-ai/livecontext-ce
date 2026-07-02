// @vitest-environment jsdom
/**
 * Regression for the fresh-install UX of the model-catalog Bundles panel:
 * an unlinked CE reported NOT_LINKED and the panel rendered (a) the RAW i18n
 * key path "aiProviders.bundles.statusCode.NOT_LINKED" as the status badge
 * (key was missing from the locale files) and (b) the scheduler's machine
 * detail ("this CE install has no active cloud link") inside the red error
 * box. Being unlinked is the NORMAL state of a fresh install: the badge must
 * translate and the detail must render as a friendly blue hint. Real fetch
 * failures keep the red box with the raw detail.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { CatalogBundleSyncStatus } from '@/lib/api/model-config.service';

let mockIsCe = true;
const getSyncStatus = vi.fn();
const listBundles = vi.fn();

vi.mock('@/lib/edition/edition', () => ({
  get IS_CE() { return mockIsCe; },
  get IS_CLOUD() { return !mockIsCe; },
}));
vi.mock('@/lib/api/model-config.service', () => ({
  modelConfigService: {
    getSyncStatus: (...a: unknown[]) => getSyncStatus(...a),
    listBundles: (...a: unknown[]) => listBundles(...a),
    buildBundle: vi.fn(),
    activateBundle: vi.fn(),
    syncNow: vi.fn(),
  },
}));
vi.mock('@/hooks/useCloudSyncGate', () => ({
  useCloudSyncGate: () => ({ ensureCloudLinked: () => true, isCloudLinked: false, syncBlocked: false }),
}));
vi.mock('../CatalogSyncPanel', () => ({ default: () => null, CatalogSyncPanel: () => null }));

import CatalogBundlesPanel from '../CatalogBundlesPanel';

const notLinkedStatus: CatalogBundleSyncStatus = {
  lastAppliedVersion: null,
  lastAppliedAt: null,
  lastFetchAt: '2026-07-02T14:30:00Z',
  lastFetchStatus: 'NOT_LINKED',
  lastFetchError: 'this CE install has no active cloud link',
  consecutiveFailures: 0,
  updatedAt: '2026-07-02T14:30:00Z',
  schedulerEnabled: true,
};

function renderPanel() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as any}>
      <CatalogBundlesPanel />
    </NextIntlClientProvider>,
  );
}

describe('CatalogBundlesPanel fresh-install (NOT_LINKED) UX', () => {
  beforeEach(() => {
    mockIsCe = true;
    getSyncStatus.mockResolvedValue(notLinkedStatus);
    listBundles.mockResolvedValue({ bundles: [], signingKeyId: null, publicKeyBase64: null });
  });
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('translates the NOT_LINKED badge instead of showing the raw key path', async () => {
    renderPanel();

    await waitFor(() => expect(screen.getByText('not linked')).toBeInTheDocument());
    expect(screen.queryByText('aiProviders.bundles.statusCode.NOT_LINKED')).not.toBeInTheDocument();
  });

  it('renders the unlinked detail as a friendly BLUE hint, not the raw machine string in red', async () => {
    renderPanel();

    const hint = await screen.findByText(/normal state for a fresh install/);
    expect(hint.className).toContain('text-blue-800');
    expect(screen.queryByText('this CE install has no active cloud link')).not.toBeInTheDocument();
  });

  it('keeps a real fetch failure in the RED error box with the raw detail', async () => {
    getSyncStatus.mockResolvedValue({
      ...notLinkedStatus,
      lastFetchStatus: 'NETWORK_ERROR',
      lastFetchError: 'I/O error on GET: PKIX path building failed',
      consecutiveFailures: 9,
    });
    renderPanel();

    const detail = await screen.findByText('I/O error on GET: PKIX path building failed');
    expect(detail.className).toContain('text-red-800');
  });
});
