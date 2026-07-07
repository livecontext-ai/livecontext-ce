/**
 * @vitest-environment jsdom
 *
 * AI-providers page - model-cache invalidation on key changes. Saving the
 * first provider API key (or deleting the last one) changes which models
 * /v3/chat/models returns, and useModels holds a module-level cache with a
 * 5-minute TTL: without an explicit clearModelsCache() the pickers keep
 * showing the stale catalog - a just-onboarded admin who added a key from the
 * NoProviderCta would come back to pickers still claiming "no models". Pins
 * that BOTH the save and the delete path drop the cache.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

const h = vi.hoisted(() => ({
  clearModelsCache: vi.fn(),
  savePlatformCredential: vi.fn(),
  deletePlatformCredential: vi.fn(),
  invalidateLlmCache: vi.fn(),
  getLlmProviderStatus: vi.fn(),
  // onSave/onDelete captured from the first rendered ProviderCard.
  captured: { onSave: null as null | ((i: string, k: string) => Promise<void>), onDelete: null as null | ((i: string) => Promise<void>) },
}));

vi.mock('next-intl', () => {
  const cache = new Map<string, (k: string) => string>();
  return {
    useTranslations: (ns?: string) => {
      const key = ns ?? '';
      if (!cache.has(key)) cache.set(key, (k: string) => `${key}.${k}`);
      return cache.get(key)!;
    },
  };
});
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false, isLoading: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ loginWithRedirect: vi.fn(), hasRole: () => true }),
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: h.clearModelsCache }));
vi.mock('@/lib/api/orchestrator/credential.service', () => ({
  credentialService: {
    getLlmProviderStatus: (...a: unknown[]) => h.getLlmProviderStatus(...a),
    savePlatformCredential: (...a: unknown[]) => h.savePlatformCredential(...a),
    deletePlatformCredential: (...a: unknown[]) => h.deletePlatformCredential(...a),
    invalidateLlmCache: (...a: unknown[]) => h.invalidateLlmCache(...a),
  },
}));
vi.mock('@/lib/api/cloud-link.service', () => ({
  cloudLinkService: {
    getStatus: vi.fn().mockResolvedValue({ linked: false, llmSource: 'BYOK' }),
  },
}));
vi.mock('@/lib/edition', () => ({ IS_CE: true, IS_CLOUD: false }));
// Capture the page's save/delete handlers from the first ProviderCard.
vi.mock('../components/ProviderCard', () => ({
  default: ({ onSave, onDelete }: { onSave: (i: string, k: string) => Promise<void>; onDelete: (i: string) => Promise<void> }) => {
    if (!h.captured.onSave) {
      h.captured.onSave = onSave;
      h.captured.onDelete = onDelete;
    }
    return <div data-testid="provider-card" />;
  },
}));
vi.mock('../components/BridgeSetupPanel', () => ({ default: () => null }));
vi.mock('../components/BridgeAccessPanel', () => ({ default: () => null }));
vi.mock('../components/ModelManagementPanel', () => ({ default: () => null }));
vi.mock('../components/ModelExecutionLinksPanel', () => ({ default: () => null }));
vi.mock('../components/ModelBundleSyncButton', () => ({ ModelBundleSyncButton: () => null }));

import AiProvidersPage from '../page';

describe('AiProvidersPage - models cache invalidation on key changes', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    h.captured.onSave = null;
    h.captured.onDelete = null;
    // The page intersects PROVIDER_DEFINITIONS with the backend status list -
    // at least one advertised provider is needed for a ProviderCard to render.
    h.getLlmProviderStatus.mockResolvedValue([
      { providerName: 'anthropic', configured: false, source: 'none' },
    ]);
    h.savePlatformCredential.mockResolvedValue(undefined);
    h.deletePlatformCredential.mockResolvedValue(undefined);
    h.invalidateLlmCache.mockResolvedValue(undefined);
  });
  afterEach(cleanup);

  async function renderAndCaptureHandlers() {
    render(<AiProvidersPage />);
    await waitFor(() => expect(screen.queryAllByTestId('provider-card').length).toBeGreaterThan(0));
    expect(h.captured.onSave).not.toBeNull();
    expect(h.captured.onDelete).not.toBeNull();
  }

  it('drops the models cache after saving a provider API key', async () => {
    await renderAndCaptureHandlers();

    await h.captured.onSave!('llm_anthropic', 'sk-ant-test');

    expect(h.savePlatformCredential).toHaveBeenCalledTimes(1);
    expect(h.clearModelsCache).toHaveBeenCalledTimes(1);
  });

  it('drops the models cache after deleting a provider API key (symmetric)', async () => {
    await renderAndCaptureHandlers();

    await h.captured.onDelete!('llm_anthropic');

    expect(h.deletePlatformCredential).toHaveBeenCalledTimes(1);
    expect(h.clearModelsCache).toHaveBeenCalledTimes(1);
  });
});
