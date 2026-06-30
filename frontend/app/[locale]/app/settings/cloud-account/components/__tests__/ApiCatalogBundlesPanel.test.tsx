// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { ApiCatalogBundleSummary } from '@/lib/api/catalog-bundles.service';

// --- controllable test state -----------------------------------------------
let mockIsCe = false;
const listBundles = vi.fn();
const buildBundle = vi.fn();
const activateBundle = vi.fn();
const getSyncStatus = vi.fn();
const syncNow = vi.fn();
// Catalog-bundle sync is gated on an active cloud link; mock the gate (default:
// linked → allow). This also stubs the hook's transitive @/i18n/navigation import.
const ensureCloudLinked = vi.fn(() => true);

// IS_CE is the panel's single source of truth for cloud-publisher vs
// CE-subscriber mode (mirrors the model CatalogBundlesPanel contract).
vi.mock('@/lib/edition', () => ({
  get IS_CE() { return mockIsCe; },
  get IS_CLOUD() { return !mockIsCe; },
}));
vi.mock('@/lib/api/catalog-bundles.service', () => ({
  catalogBundlesService: {
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

import ApiCatalogBundlesPanel from '../ApiCatalogBundlesPanel';

const activeBundle: ApiCatalogBundleSummary = {
  id: 1,
  version: 1,
  schemaVersion: 1,
  checksum: 'aaa',
  signingKeyId: 'key-1',
  issuer: 'cloud',
  apiCount: 600,
  toolCount: 4500,
  rawBytesSize: 2048,
  importedAt: '2026-06-09T08:00:00Z',
  activatedAt: '2026-06-09T09:00:00Z',
  isActive: true,
};

const inactiveBundle: ApiCatalogBundleSummary = {
  ...activeBundle,
  id: 2,
  version: 2,
  apiCount: 612,
  toolCount: 4810,
  activatedAt: null,
  isActive: false,
};

function renderPanel() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as any}>
      <ApiCatalogBundlesPanel />
    </NextIntlClientProvider>,
  );
}

describe('ApiCatalogBundlesPanel', () => {
  beforeEach(() => {
    mockIsCe = false;
    ensureCloudLinked.mockReturnValue(true);
    listBundles.mockResolvedValue({ bundles: [activeBundle, inactiveBundle] });
    buildBundle.mockResolvedValue(inactiveBundle);
    activateBundle.mockResolvedValue({ ...inactiveBundle, isActive: true });
    getSyncStatus.mockResolvedValue({
      lastAppliedVersion: 1,
      lastAppliedAt: '2026-06-10T09:00:00Z',
      lastFetchAt: '2026-06-10T09:30:00Z',
      lastFetchStatus: 'OK',
      lastFetchError: null,
      consecutiveFailures: 0,
    });
    syncNow.mockResolvedValue({
      lastAppliedVersion: 2,
      lastAppliedAt: '2026-06-10T10:00:00Z',
      lastFetchAt: '2026-06-10T10:00:00Z',
      lastFetchStatus: 'OK',
      lastFetchError: null,
      consecutiveFailures: 0,
    });
  });
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  describe('cloud mode (publisher)', () => {
    it('renders the publish card + bundle history with API and tool counts', async () => {
      renderPanel();

      await waitFor(() => expect(screen.getByText('Publish a new bundle')).toBeInTheDocument());
      expect(screen.getByText('Published bundles')).toBeInTheDocument();
      // apiCount / toolCount of both bundles are shown
      expect(screen.getByText('600')).toBeInTheDocument();
      expect(screen.getByText('4500')).toBeInTheDocument();
      expect(screen.getByText('612')).toBeInTheDocument();
      expect(screen.getByText('4810')).toBeInTheDocument();
      // active bundle highlighted with the "active" badge
      expect(screen.getByText('active')).toBeInTheDocument();
      // cloud mode never fetches the CE sync status
      expect(getSyncStatus).not.toHaveBeenCalled();
      expect(screen.queryByText('Sync now')).not.toBeInTheDocument();
    });

    it('Build bundle calls the build endpoint then refreshes the list', async () => {
      renderPanel();
      await waitFor(() => expect(screen.getByText('Build bundle')).toBeInTheDocument());

      fireEvent.click(screen.getByText('Build bundle'));

      await waitFor(() => expect(buildBundle).toHaveBeenCalledTimes(1));
      // initial load + post-build refresh
      await waitFor(() => expect(listBundles).toHaveBeenCalledTimes(2));
    });

    it('Activate asks for confirmation first, then calls activate with the bundle id', async () => {
      renderPanel();
      await waitFor(() => expect(screen.getByRole('button', { name: 'Activate' })).toBeInTheDocument());

      // Only the inactive bundle (id=2) has an Activate action
      fireEvent.click(screen.getByRole('button', { name: 'Activate' }));

      const dialog = await screen.findByRole('dialog');
      expect(within(dialog).getByText('Activate this bundle?')).toBeInTheDocument();
      // nothing happens until the admin confirms
      expect(activateBundle).not.toHaveBeenCalled();

      fireEvent.click(within(dialog).getByRole('button', { name: 'Activate' }));

      await waitFor(() => expect(activateBundle).toHaveBeenCalledWith(2));
      await waitFor(() => expect(listBundles).toHaveBeenCalledTimes(2));
    });

    it('cancelling the confirmation dialog does NOT activate', async () => {
      renderPanel();
      await waitFor(() => expect(screen.getByRole('button', { name: 'Activate' })).toBeInTheDocument());

      fireEvent.click(screen.getByRole('button', { name: 'Activate' }));
      const dialog = await screen.findByRole('dialog');

      fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(activateBundle).not.toHaveBeenCalled();
      expect(listBundles).toHaveBeenCalledTimes(1);
    });

    it('shows the load error when the bundle list cannot be fetched', async () => {
      listBundles.mockRejectedValue(new Error('boom'));
      renderPanel();

      await waitFor(() =>
        expect(screen.getByText('Failed to load API catalog bundles.')).toBeInTheDocument(),
      );
    });
  });

  describe('CE mode (subscriber)', () => {
    beforeEach(() => {
      mockIsCe = true;
    });

    it('renders the sync-status card instead of the publish card', async () => {
      renderPanel();

      await waitFor(() => expect(screen.getByText('Sync status')).toBeInTheDocument());
      expect(getSyncStatus).toHaveBeenCalledTimes(1);
      expect(screen.getByText('Last fetch status')).toBeInTheDocument();
      expect(screen.getByText('Imported bundles')).toBeInTheDocument();
      // no publishing affordances on CE
      expect(screen.queryByText('Build bundle')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Activate' })).not.toBeInTheDocument();
    });

    it('Sync now calls the sync endpoint and refreshes status + list', async () => {
      renderPanel();
      await waitFor(() => expect(screen.getByText('Sync now')).toBeInTheDocument());

      fireEvent.click(screen.getByText('Sync now'));

      await waitFor(() => expect(syncNow).toHaveBeenCalledTimes(1));
      // initial load + post-sync refresh
      await waitFor(() => expect(listBundles).toHaveBeenCalledTimes(2));
    });

    it('Sync now is blocked (no sync) when the cloud link is missing - admin is sent to Connect first', async () => {
      ensureCloudLinked.mockReturnValue(false); // unlinked CE → gate redirects
      renderPanel();
      await waitFor(() => expect(screen.getByText('Sync now')).toBeInTheDocument());

      fireEvent.click(screen.getByText('Sync now'));

      await waitFor(() => expect(ensureCloudLinked).toHaveBeenCalled());
      expect(syncNow).not.toHaveBeenCalled(); // the cloud→CE sync never fired
    });

    it('surfaces the last fetch error returned by the scheduler', async () => {
      getSyncStatus.mockResolvedValue({
        lastAppliedVersion: null,
        lastAppliedAt: null,
        lastFetchAt: '2026-06-10T09:30:00Z',
        lastFetchStatus: 'SIGNATURE_INVALID',
        lastFetchError: 'Ed25519 signature mismatch',
        consecutiveFailures: 3,
      });
      renderPanel();

      await waitFor(() => expect(screen.getByText('Ed25519 signature mismatch')).toBeInTheDocument());
      expect(screen.getByText('signature invalid')).toBeInTheDocument();
      expect(screen.getByText('3')).toBeInTheDocument();
    });
  });
});
