/**
 * CE-cloud parity: the public per-publication reads must route to the CE
 * backend's cloud proxy (`/publications/remote/by-id/...`, authenticated) when
 * `remote=true`, and stay on the anonymous local public endpoint
 * (`/publications/by-id/...`, skipAuth) otherwise. A cloud-linked CE browses
 * CLOUD publications whose ids are absent from the local DB, so without this
 * routing every card thumbnail + the detail page 404s.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { PublicationService } from '../publication.service';
import { apiClient } from '../../api-client';

vi.mock('../../api-client', () => ({
  apiClient: {
    get: vi.fn(),
  },
}));

const mockedGet = vi.mocked(apiClient.get);

describe('PublicationService - CE-cloud per-publication read routing', () => {
  let service: PublicationService;

  beforeEach(() => {
    service = new PublicationService();
    vi.clearAllMocks();
    mockedGet.mockResolvedValue({} as any);
  });

  const LOCAL = '/publications/by-id';
  const REMOTE = '/publications/remote/by-id';

  describe('getPublicationByIdPublic', () => {
    it('defaults to the local anonymous endpoint (skipAuth) when remote is omitted', async () => {
      await service.getPublicationByIdPublic('pub_1');
      expect(mockedGet).toHaveBeenCalledWith(`${LOCAL}/pub_1`, { skipAuth: true });
    });

    it('routes to the authenticated cloud proxy when remote=true', async () => {
      await service.getPublicationByIdPublic('pub_1', true);
      expect(mockedGet).toHaveBeenCalledWith(`${REMOTE}/pub_1`, { skipAuth: false });
    });
  });

  describe('getLandingSnapshot', () => {
    it('local + skipAuth by default', async () => {
      await service.getLandingSnapshot('pub_1');
      expect(mockedGet).toHaveBeenCalledWith(`${LOCAL}/pub_1/landing-snapshot`, { skipAuth: true });
    });

    it('cloud proxy + auth when remote', async () => {
      await service.getLandingSnapshot('pub_1', true);
      expect(mockedGet).toHaveBeenCalledWith(`${REMOTE}/pub_1/landing-snapshot`, { skipAuth: false });
    });
  });

  describe('getShowcaseRender', () => {
    it('local + skipAuth + params by default', async () => {
      await service.getShowcaseRender('pub_1', { page: 2, size: 1 });
      expect(mockedGet).toHaveBeenCalledWith(
        `${LOCAL}/pub_1/showcase-render`,
        { params: { page: 2, size: 1 }, skipAuth: true },
      );
    });

    it('cloud proxy + auth + params when remote', async () => {
      await service.getShowcaseRender('pub_1', { page: 0, size: 1, interfaceId: 'iface-9' }, true);
      expect(mockedGet).toHaveBeenCalledWith(
        `${REMOTE}/pub_1/showcase-render`,
        { params: { page: 0, size: 1, interfaceId: 'iface-9' }, skipAuth: false },
      );
    });

    // Acquirer card path: hit the AUTH'D endpoint (no /by-id, no skipAuth) so the
    // receipt bypass admits a publication the caller installed even after the
    // publisher made it non-public. This is the fix for the "card shows a node
    // view instead of the interface" regression on private/unpublished apps.
    it('authenticated=true → auth\'d /publications/{id}/showcase-render (no /by-id, no skipAuth)', async () => {
      await service.getShowcaseRender('pub_1', { page: 0, size: 1, authenticated: true });
      expect(mockedGet).toHaveBeenCalledWith(
        '/publications/pub_1/showcase-render',
        { params: { page: 0, size: 1 } },
      );
    });

    it('authenticated path forwards the epoch param', async () => {
      await service.getShowcaseRender('pub_1', { page: 0, size: 1, epoch: 5, authenticated: true });
      expect(mockedGet).toHaveBeenCalledWith(
        '/publications/pub_1/showcase-render',
        { params: { page: 0, size: 1, epoch: 5 } },
      );
    });

    it('authenticated is ignored when remote=true (cloud-linked CE keeps the by-id proxy)', async () => {
      await service.getShowcaseRender('pub_1', { page: 0, size: 1, authenticated: true }, true);
      expect(mockedGet).toHaveBeenCalledWith(
        `${REMOTE}/pub_1/showcase-render`,
        { params: { page: 0, size: 1 }, skipAuth: false },
      );
      expect(mockedGet).not.toHaveBeenCalledWith('/publications/pub_1/showcase-render', expect.anything());
    });
  });

  describe('getAgentSnapshot', () => {
    it('local + skipAuth by default', async () => {
      await service.getAgentSnapshot('pub_1');
      expect(mockedGet).toHaveBeenCalledWith(`${LOCAL}/pub_1/agent-snapshot`, { skipAuth: true });
    });

    it('cloud proxy + auth when remote', async () => {
      await service.getAgentSnapshot('pub_1', true);
      expect(mockedGet).toHaveBeenCalledWith(`${REMOTE}/pub_1/agent-snapshot`, { skipAuth: false });
    });
  });

  describe('getShowcaseAggregatedSteps', () => {
    it('local + skipAuth, epoch param forwarded', async () => {
      await service.getShowcaseAggregatedSteps('pub_1', 3);
      expect(mockedGet).toHaveBeenCalledWith(
        `${LOCAL}/pub_1/aggregated-steps`,
        { params: { epoch: '3' }, skipAuth: true },
      );
    });

    it('cloud proxy + auth when remote', async () => {
      await service.getShowcaseAggregatedSteps('pub_1', 3, true);
      expect(mockedGet).toHaveBeenCalledWith(
        `${REMOTE}/pub_1/aggregated-steps`,
        { params: { epoch: '3' }, skipAuth: false },
      );
    });
  });

  describe('getShowcaseEpochState / getShowcaseEpochSignals', () => {
    it('local + skipAuth by default', async () => {
      await service.getShowcaseEpochState('pub_1', 4);
      await service.getShowcaseEpochSignals('pub_1', 4);
      expect(mockedGet).toHaveBeenNthCalledWith(1, `${LOCAL}/pub_1/epochs/4/state`, { skipAuth: true });
      expect(mockedGet).toHaveBeenNthCalledWith(2, `${LOCAL}/pub_1/epochs/4/signals`, { skipAuth: true });
    });

    it('cloud proxy + auth when remote', async () => {
      await service.getShowcaseEpochState('pub_1', 4, true);
      await service.getShowcaseEpochSignals('pub_1', 4, true);
      expect(mockedGet).toHaveBeenNthCalledWith(1, `${REMOTE}/pub_1/epochs/4/state`, { skipAuth: false });
      expect(mockedGet).toHaveBeenNthCalledWith(2, `${REMOTE}/pub_1/epochs/4/signals`, { skipAuth: false });
    });
  });

  describe('getShowcaseRunState (coalescer keys by mode too)', () => {
    it('local + skipAuth by default', async () => {
      await service.getShowcaseRunState('pub_1');
      expect(mockedGet).toHaveBeenCalledWith(`${LOCAL}/pub_1/run-state`, { skipAuth: true });
    });

    it('cloud proxy + auth when remote', async () => {
      await service.getShowcaseRunState('pub_1', true);
      expect(mockedGet).toHaveBeenCalledWith(`${REMOTE}/pub_1/run-state`, { skipAuth: false });
    });

    it('does NOT coalesce a local and a remote read of the same id (distinct calls)', async () => {
      // Two never-resolving fetches so both stay in-flight simultaneously.
      mockedGet.mockReturnValue(new Promise(() => {}) as any);
      void service.getShowcaseRunState('pub_1');       // local
      void service.getShowcaseRunState('pub_1', true); // remote
      expect(mockedGet).toHaveBeenCalledTimes(2);
    });
  });
});
