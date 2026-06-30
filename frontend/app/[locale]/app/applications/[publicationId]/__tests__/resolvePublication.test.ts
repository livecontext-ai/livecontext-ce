/**
 * Regression - pins the cloud-linked-CE remote fallback for resolving the
 * publication behind an installed application.
 *
 * Bug ("l'application installée ne s'affiche pas"): in a cloud-linked CE an
 * acquired CLOUD publication has no local catalog row, so the detail layout's
 * `getPublicationById` 404s, the layout fell into its error state, and NO run was
 * ever created (DB showed 0 runs for the cloned workflow). The fix falls back to
 * the cloud-parity remote by-id proxy so the installed app still opens; the clone
 * (workflowId / plan / interfaces) is local, so only this metadata read is remote.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

const svc = vi.hoisted(() => ({
  getPublicationById: vi.fn(),
  getPublicationByIdPublic: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: svc }));

// IS_CE is a module-level const; the mock returns a mutable object so each test
// can flip the edition (mirrors MarketplacePage.ceCloudParity.test.tsx).
const editionState = vi.hoisted(() => ({ IS_CE: true }));
vi.mock('@/lib/edition', () => editionState);

import { resolveApplicationPublication } from '../resolvePublication';

describe('resolveApplicationPublication', () => {
  beforeEach(() => {
    svc.getPublicationById.mockReset();
    svc.getPublicationByIdPublic.mockReset();
    editionState.IS_CE = true;
  });

  it('returns the LOCAL publication when getPublicationById succeeds (no remote call)', async () => {
    svc.getPublicationById.mockResolvedValue({ id: 'p1', title: 'Local app' });

    const pub = await resolveApplicationPublication('p1');

    expect(pub).toEqual({ id: 'p1', title: 'Local app' });
    expect(svc.getPublicationById).toHaveBeenCalledWith('p1');
    expect(svc.getPublicationByIdPublic).not.toHaveBeenCalled();
  });

  it('CE: falls back to the REMOTE by-id proxy when the local lookup 404s AND stamps remote:true', async () => {
    svc.getPublicationById.mockRejectedValue(Object.assign(new Error('Not Found'), { status: 404 }));
    // The cloud remote by-id proxy returns the metadata WITHOUT any `remote` flag (the backend
    // has no concept of the frontend's remote-rendering); the resolver must stamp it.
    svc.getPublicationByIdPublic.mockResolvedValue({ id: 'cloud-1', title: 'Cloud app', publisherId: '16' });

    const pub = await resolveApplicationPublication('cloud-1');

    // The fix: route the metadata read through the cloud proxy (remote=true) AND stamp remote:true
    // on the result so the detail view threads it to PublisherAvatar - a cloud publisherId resolves
    // via /publications/remote/users/{id}/avatar, not the local namespace (which returns a default
    // placeholder = the "image not available in the Info tab" symptom).
    expect(svc.getPublicationByIdPublic).toHaveBeenCalledWith('cloud-1', true);
    expect(pub).toEqual({ id: 'cloud-1', title: 'Cloud app', publisherId: '16', remote: true });
  });

  it('non-CE (cloud build): rethrows the local error, NEVER hits a remote proxy', async () => {
    editionState.IS_CE = false;
    const err = Object.assign(new Error('Not Found'), { status: 404 });
    svc.getPublicationById.mockRejectedValue(err);

    await expect(resolveApplicationPublication('p1')).rejects.toBe(err);
    expect(svc.getPublicationByIdPublic).not.toHaveBeenCalled();
  });
});
