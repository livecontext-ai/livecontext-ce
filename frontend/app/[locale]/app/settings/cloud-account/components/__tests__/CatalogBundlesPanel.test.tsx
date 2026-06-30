// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { CatalogBundleSummary } from '@/lib/api/model-config.service';

// --- controllable test state -----------------------------------------------
let mockIsCe = false;
const listBundles = vi.fn();
const buildBundle = vi.fn();
const activateBundle = vi.fn();
const getSyncStatus = vi.fn();
const syncNow = vi.fn();
const ensureCloudLinked = vi.fn(() => true);

vi.mock('@/lib/edition', () => ({
  get IS_CE() { return mockIsCe; },
  get IS_CLOUD() { return !mockIsCe; },
}));
vi.mock('@/lib/api/model-config.service', () => ({
  modelConfigService: {
    listBundles: (...a: unknown[]) => listBundles(...a),
    buildBundle: (...a: unknown[]) => buildBundle(...a),
    activateBundle: (...a: unknown[]) => activateBundle(...a),
    getSyncStatus: (...a: unknown[]) => getSyncStatus(...a),
    syncNow: (...a: unknown[]) => syncNow(...a),
  },
}));
vi.mock('@/hooks/useCloudSyncGate', () => ({
  useCloudSyncGate: () => ({ ensureCloudLinked, isCloudLinked: true, syncBlocked: false }),
}));
// The cloud "Step 1" sync panel makes its own API calls on mount - stub it out so
// this test isolates the bundle-history table.
vi.mock('../CatalogSyncPanel', () => ({
  CatalogSyncPanel: () => <div data-testid="catalog-sync-panel" />,
}));

import CatalogBundlesPanel from '../CatalogBundlesPanel';

const activeBundle: CatalogBundleSummary = {
  id: 1,
  version: 1,
  schemaVersion: 1,
  checksum: 'aaa',
  signingKeyId: 'key-1',
  issuer: 'cloud',
  modelCount: 42,
  rawBytesSize: 2048,
  isActive: true,
  importedAt: '2026-06-09T08:00:00Z',
  activatedAt: '2026-06-09T09:00:00Z',
};

const inactiveBundle: CatalogBundleSummary = {
  ...activeBundle,
  id: 2,
  version: 2,
  modelCount: 50,
  isActive: false,
  activatedAt: null,
};

function renderPanel() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as any}>
      <CatalogBundlesPanel />
    </NextIntlClientProvider>,
  );
}

describe('CatalogBundlesPanel - Published bundles table', () => {
  beforeEach(() => {
    mockIsCe = false;
    ensureCloudLinked.mockReturnValue(true);
    listBundles.mockResolvedValue({ bundles: [activeBundle, inactiveBundle], signingKeyId: 'key-1' });
  });
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  // Mirrors the MCP-catalog (ApiCatalogBundlesPanel) table: the ACTIVE bundle row
  // is tinted green, inactive rows are not. Pre-fix the model table had no row tint.
  it('tints the active bundle row green and leaves inactive rows untinted', async () => {
    renderPanel();

    await waitFor(() => expect(screen.getByText('Published bundles')).toBeInTheDocument());

    const activeRow = screen.getByText('active').closest('tr');
    const inactiveRow = screen.getByText('inactive').closest('tr');

    expect(activeRow).toHaveClass('bg-green-500/5');
    expect(inactiveRow).not.toHaveClass('bg-green-500/5');
  });
});
