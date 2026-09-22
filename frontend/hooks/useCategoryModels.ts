'use client';

import { useState, useEffect } from 'react';
import { apiClient } from '@/lib/api';
import type { ModelsData } from '@/hooks/useModels';

/**
 * The models of ONE catalogue category, for a surface that runs a kind of model the chat
 * catalogue does not carry.
 *
 * <p><b>Why this is separate from {@code useModels}.</b> That hook answers the chat slice
 * and caches it once for the whole page, which is right: nearly every surface wants the
 * same list. A decision model is deliberately absent from that slice - it cannot hold a
 * conversation, so the server strips it from the category-less answer before the client
 * sees anything, and no client-side filter can add back what never arrived. Teaching the
 * shared cache to hold several categories would mean reworking its de-duplication and TTL,
 * which are subtle and carry their own scars. A small cache of its own, keyed by category,
 * costs one extra request on one screen and touches none of that.
 *
 * <p>Fails quiet: an error leaves {@code models} empty, so the caller simply offers the
 * categories it already had. A surface that unions this with the chat list therefore
 * degrades to the chat list alone rather than to an empty picker.
 */
const cache = new Map<string, ModelsData>();
const inFlight = new Map<string, Promise<ModelsData>>();

/** Clear the per-category cache, for tests and for an explicit catalogue refresh. */
export function clearCategoryModelsCache(): void {
  cache.clear();
  inFlight.clear();
}

async function fetchCategory(category: string): Promise<ModelsData> {
  const cached = cache.get(category);
  if (cached) return cached;

  const pending = inFlight.get(category);
  if (pending) return pending;

  const request = (async () => {
    // getAuthToken, not the provider: during the auth bootstrap the provider is not yet
    // installed, so reading it returns undefined and the request goes out anonymously.
    // getAuthToken waits for the bootstrap instead, and still returns null for a genuinely
    // signed-out visitor, which is what the skipAuth decision wants.
    //
    // The anonymous request does NOT come back 401, and that is what made this quiet: this
    // endpoint answers a signed-out caller with the PUBLIC catalogue, measured at 39 models
    // against the tenant full set. The classify picker would have offered a shorter list of
    // decision models for no visible reason and cached it for five minutes.
    //
    // useModels reads the provider deliberately and is on the guard allow-list, because the
    // landing and marketplace call this endpoint signed out and the wait would only delay a
    // request that was always going to be anonymous. This hook has no such caller: it is
    // reached from the workflow-builder inspector alone, where a session always exists.
    const token = await apiClient.getAuthToken().catch(() => null);
    const data = await apiClient.get<ModelsData>('/v3/chat/models', {
      params: { category },
      skipAuth: !token,
    });
    cache.set(category, data);
    return data;
  })();

  const tracked: Promise<ModelsData> = request.finally(() => {
    if (inFlight.get(category) === tracked) {
      inFlight.delete(category);
    }
  });
  inFlight.set(category, tracked);
  return tracked;
}

export interface UseCategoryModelsResult {
  data: ModelsData | null;
  isLoading: boolean;
}

/**
 * Load one category's models. Pass {@code null} to load nothing, so a component can call
 * this unconditionally and still opt out.
 */
export function useCategoryModels(category: string | null | undefined): UseCategoryModelsResult {
  const [data, setData] = useState<ModelsData | null>(
    category ? cache.get(category) ?? null : null,
  );
  const [isLoading, setIsLoading] = useState(Boolean(category) && !cache.has(category ?? ''));

  useEffect(() => {
    if (!category) {
      setData(null);
      setIsLoading(false);
      return;
    }
    let cancelled = false;
    setIsLoading(true);
    fetchCategory(category)
      .then(result => {
        if (!cancelled) setData(result);
      })
      .catch(() => {
        // Quiet on purpose: the caller falls back to the categories it already has,
        // which is a smaller list rather than a broken screen.
        if (!cancelled) setData(null);
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [category]);

  return { data, isLoading };
}
