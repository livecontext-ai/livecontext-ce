/**
 * @vitest-environment jsdom
 *
 * The hook that fetches ONE catalogue category, for a surface running a kind of model the
 * chat catalogue does not carry.
 *
 * It exists because the shared `useModels` cache answers the chat slice and only that, and
 * teaching it to hold several categories would mean reworking a de-duplication and a TTL
 * that both carry scars. What that trade buys has to be held in place: it must cache per
 * category, de-duplicate concurrent callers, and fail quiet so a failing endpoint costs a
 * surface its extra family rather than its whole picker.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';

const h = vi.hoisted(() => ({
  get: vi.fn(),
  // What the hook asks for: the token itself, through the accessor that WAITS for the auth
  // bootstrap. A mock that only offered the raw provider getter left the real call
  // returning undefined, and every test here hung for fifteen seconds on a promise that
  // could never settle - a shape production would reproduce exactly.
  token: null as string | null,
}));

vi.mock('@/lib/api', () => ({
  apiClient: {
    get: h.get,
    getAuthToken: async () => h.token,
  },
}));

import { useCategoryModels, clearCategoryModelsCache } from '@/hooks/useCategoryModels';

const PAYLOAD = { providers: [{ name: 'typesafe', models: [] }], defaultProvider: null, defaultModel: null };

describe('useCategoryModels', () => {
  beforeEach(() => {
    clearCategoryModelsCache();
    h.get.mockReset();
    h.token = null;
  });
  afterEach(() => vi.clearAllMocks());

  it('asks the models endpoint for the category it was given', async () => {
    h.get.mockResolvedValue(PAYLOAD);

    const { result } = renderHook(() => useCategoryModels('classification'));

    await waitFor(() => expect(result.current.data).toEqual(PAYLOAD));
    expect(h.get).toHaveBeenCalledWith('/v3/chat/models', {
      params: { category: 'classification' },
      skipAuth: true,
    });
  });

  it('sends the request authenticated when a token is available', async () => {
    // Same contract as the shared hook: an anonymous read answers a trimmed catalogue, so
    // a signed-in user must not be served one by accident.
    h.token = 'a-token';
    h.get.mockResolvedValue(PAYLOAD);

    const { result } = renderHook(() => useCategoryModels('classification'));

    await waitFor(() => expect(result.current.data).toEqual(PAYLOAD));
    expect(h.get).toHaveBeenCalledWith('/v3/chat/models', {
      params: { category: 'classification' },
      skipAuth: false,
    });
  });

  it('fetches a category once and serves the rest from cache', async () => {
    h.get.mockResolvedValue(PAYLOAD);

    const first = renderHook(() => useCategoryModels('classification'));
    await waitFor(() => expect(first.result.current.data).toEqual(PAYLOAD));
    const second = renderHook(() => useCategoryModels('classification'));
    await waitFor(() => expect(second.result.current.data).toEqual(PAYLOAD));

    expect(h.get).toHaveBeenCalledTimes(1);
  });

  it('de-duplicates callers that mount while the first request is still open', async () => {
    let release: (value: unknown) => void = () => {};
    h.get.mockReturnValue(new Promise(resolve => { release = resolve; }));

    const a = renderHook(() => useCategoryModels('classification'));
    const b = renderHook(() => useCategoryModels('classification'));
    release(PAYLOAD);

    await waitFor(() => expect(a.result.current.data).toEqual(PAYLOAD));
    await waitFor(() => expect(b.result.current.data).toEqual(PAYLOAD));
    // One request, two subscribers. A picker mounted twice on one screen is ordinary.
    expect(h.get).toHaveBeenCalledTimes(1);
  });

  it('keeps categories apart rather than serving one cache to all of them', async () => {
    h.get.mockResolvedValue(PAYLOAD);

    const a = renderHook(() => useCategoryModels('classification'));
    await waitFor(() => expect(a.result.current.data).toEqual(PAYLOAD));
    const b = renderHook(() => useCategoryModels('image_generation'));
    await waitFor(() => expect(b.result.current.data).toEqual(PAYLOAD));

    expect(h.get).toHaveBeenCalledTimes(2);
  });

  it('fetches nothing at all when no category is asked for', async () => {
    const { result } = renderHook(() => useCategoryModels(null));

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.data).toBeNull();
    // Every ordinary picker passes null. It must cost no request.
    expect(h.get).not.toHaveBeenCalled();
  });

  it('fails quiet, so a failing endpoint costs a surface one family and not its picker', async () => {
    h.get.mockRejectedValue(new Error('gateway down'));

    const { result } = renderHook(() => useCategoryModels('classification'));

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.data).toBeNull();
  });

  it('does not cache a failure, so a later mount tries again', async () => {
    h.get.mockRejectedValueOnce(new Error('gateway down')).mockResolvedValue(PAYLOAD);

    const first = renderHook(() => useCategoryModels('classification'));
    await waitFor(() => expect(first.result.current.isLoading).toBe(false));

    const second = renderHook(() => useCategoryModels('classification'));
    await waitFor(() => expect(second.result.current.data).toEqual(PAYLOAD));
  });
});
