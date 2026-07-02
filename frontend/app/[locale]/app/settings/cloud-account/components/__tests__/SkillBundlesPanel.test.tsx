// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { SkillBundleSummary, SkillBundleSyncStatus } from '@/lib/api/orchestrator/skill.service';

// --- controllable test state -----------------------------------------------
let mockIsCe = false;
const listSkillBundles = vi.fn();
const buildSkillBundle = vi.fn();
const activateSkillBundle = vi.fn();
const getSkillBundleSyncStatus = vi.fn();
const syncSkillBundlesNow = vi.fn();
const ensureCloudLinked = vi.fn(() => true);

vi.mock('@/lib/edition/edition', () => ({
  get IS_CE() { return mockIsCe; },
  get IS_CLOUD() { return !mockIsCe; },
}));
vi.mock('@/lib/api/orchestrator/skill.service', () => ({
  skillService: {
    listSkillBundles: (...a: unknown[]) => listSkillBundles(...a),
    buildSkillBundle: (...a: unknown[]) => buildSkillBundle(...a),
    activateSkillBundle: (...a: unknown[]) => activateSkillBundle(...a),
    getSkillBundleSyncStatus: (...a: unknown[]) => getSkillBundleSyncStatus(...a),
    syncSkillBundlesNow: (...a: unknown[]) => syncSkillBundlesNow(...a),
  },
}));
vi.mock('@/hooks/useCloudSyncGate', () => ({
  useCloudSyncGate: () => ({ ensureCloudLinked, isCloudLinked: true, syncBlocked: false }),
}));

import SkillBundlesPanel from '../SkillBundlesPanel';

const activeBundle: SkillBundleSummary = {
  id: 1,
  version: 1,
  schemaVersion: 1,
  checksum: 'aaa',
  signingKeyId: 'key-1',
  issuer: 'cloud',
  skillCount: 7,
  rawBytesSize: 2048,
  isActive: true,
  importedAt: '2026-06-29T08:00:00Z',
  activatedAt: '2026-06-29T09:00:00Z',
};
const inactiveBundle: SkillBundleSummary = { ...activeBundle, id: 2, version: 2, skillCount: 9, isActive: false, activatedAt: null };

const ceStatus: SkillBundleSyncStatus = {
  lastAppliedVersion: 5,
  lastAppliedAt: '2026-06-29T10:00:00Z',
  lastFetchAt: '2026-06-29T10:05:00Z',
  lastFetchStatus: 'NOT_LINKED',
  lastFetchError: null,
  consecutiveFailures: 0,
  updatedAt: '2026-06-29T10:05:00Z',
  schedulerEnabled: true,
};

function renderPanel() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as any}>
      <SkillBundlesPanel />
    </NextIntlClientProvider>,
  );
}

describe('SkillBundlesPanel', () => {
  beforeEach(() => {
    mockIsCe = false;
    ensureCloudLinked.mockReturnValue(true);
    listSkillBundles.mockResolvedValue({ bundles: [activeBundle, inactiveBundle], signingKeyId: 'key-1', publicKeyBase64: 'pk' });
    getSkillBundleSyncStatus.mockResolvedValue(ceStatus);
    buildSkillBundle.mockResolvedValue(activeBundle);
    activateSkillBundle.mockResolvedValue({ ...inactiveBundle, isActive: true });
    syncSkillBundlesNow.mockResolvedValue(ceStatus);
  });
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('cloud mode: shows the published history, tints the active row, lists the skill count, and offers Build', async () => {
    renderPanel();

    await waitFor(() => expect(screen.getByText('Published bundles')).toBeInTheDocument());
    expect(screen.getByText('active').closest('tr')).toHaveClass('bg-green-500/5');
    expect(screen.getByText('inactive').closest('tr')).not.toHaveClass('bg-green-500/5');
    // skillCount column renders both bundles' counts.
    expect(screen.getByText('7')).toBeInTheDocument();
    expect(screen.getByText('9')).toBeInTheDocument();
    // Cloud shows Build + signing key, never the CE sync-now control.
    expect(screen.getByText('Build bundle')).toBeInTheDocument();
    expect(screen.getByText(/Signing key/)).toBeInTheDocument();
    expect(screen.queryByText('Sync now')).not.toBeInTheDocument();
    // Sync-status (CE) endpoint must not be hit on cloud.
    expect(getSkillBundleSyncStatus).not.toHaveBeenCalled();
  });

  it('cloud mode: clicking Build calls buildSkillBundle', async () => {
    renderPanel();
    await waitFor(() => expect(screen.getByText('Build bundle')).toBeInTheDocument());
    fireEvent.click(screen.getByText('Build bundle'));
    await waitFor(() => expect(buildSkillBundle).toHaveBeenCalledTimes(1));
  });

  it('cloud mode: clicking Activate on an inactive bundle calls activateSkillBundle with its id', async () => {
    renderPanel();
    await waitFor(() => expect(screen.getByText('Activate')).toBeInTheDocument());
    fireEvent.click(screen.getByText('Activate'));
    await waitFor(() => expect(activateSkillBundle).toHaveBeenCalledWith(2));
  });

  it('CE mode: shows the sync-status panel with Sync now, translates the NOT_LINKED status, and hides Build', async () => {
    mockIsCe = true;
    renderPanel();

    await waitFor(() => expect(screen.getByText('Sync status')).toBeInTheDocument());
    expect(screen.getByText('Sync now')).toBeInTheDocument();
    expect(screen.getByText('not linked')).toBeInTheDocument(); // statusCode.NOT_LINKED
    expect(screen.getByText('Imported bundles')).toBeInTheDocument();
    expect(screen.queryByText('Build bundle')).not.toBeInTheDocument();
    expect(getSkillBundleSyncStatus).toHaveBeenCalled();
  });

  it('CE mode: Sync now triggers syncSkillBundlesNow when cloud-linked', async () => {
    mockIsCe = true;
    renderPanel();
    await waitFor(() => expect(screen.getByText('Sync now')).toBeInTheDocument());
    fireEvent.click(screen.getByText('Sync now'));
    await waitFor(() => expect(syncSkillBundlesNow).toHaveBeenCalledTimes(1));
  });

  it('CE mode: NOT_LINKED detail renders as a friendly BLUE hint, never the raw backend string in red', async () => {
    // Regression: a fresh unlinked install used to show the raw scheduler detail
    // ("this CE install has no active cloud link") inside the red error box -
    // a normal setup state must not look like a failure.
    mockIsCe = true;
    getSkillBundleSyncStatus.mockResolvedValue({
      ...ceStatus,
      lastFetchStatus: 'NOT_LINKED',
      lastFetchError: 'this CE install has no active cloud link',
    });
    renderPanel();

    await waitFor(() => expect(screen.getByText('Sync status')).toBeInTheDocument());
    const hint = await screen.findByText(/normal state for a fresh install/);
    expect(hint).toBeInTheDocument();
    expect(hint.className).toContain('text-blue-800');
    expect(screen.queryByText('this CE install has no active cloud link')).not.toBeInTheDocument();
  });

  it('CE mode: a real fetch failure keeps the raw detail in the RED error box', async () => {
    mockIsCe = true;
    getSkillBundleSyncStatus.mockResolvedValue({
      ...ceStatus,
      lastFetchStatus: 'NETWORK_ERROR',
      lastFetchError: 'I/O error on GET: PKIX path building failed',
      consecutiveFailures: 9,
    });
    renderPanel();

    await waitFor(() => expect(screen.getByText('Sync status')).toBeInTheDocument());
    const detail = await screen.findByText('I/O error on GET: PKIX path building failed');
    expect(detail.className).toContain('text-red-800');
  });
});
