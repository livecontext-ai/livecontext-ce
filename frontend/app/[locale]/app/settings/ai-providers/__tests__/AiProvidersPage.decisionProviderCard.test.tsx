/**
 * @vitest-environment jsdom
 *
 * AI-providers page - a provider is keyable only if BOTH halves name it.
 *
 * The page intersects the frontend `PROVIDER_DEFINITIONS` metadata list with the
 * backend status list, and the file says out loud that a provider the backend does
 * not advertise is dropped. The converse is just as true and was not: a provider the
 * backend DOES advertise but this file omits is dropped too, in silence, and there is
 * then nowhere in the product to enter its key. That is exactly how the decision
 * provider shipped keyless - the bean was registered, the catalogue served its model,
 * the Classify node offered it, and the only screen that takes an API key never drew
 * a card for it.
 *
 * So the first test is the regression, and the last one pins the mechanism that makes
 * it possible, so a reader who deletes the entry finds out why it mattered.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import type { LlmProviderDefinition } from '@/lib/api/orchestrator/types';

const h = vi.hoisted(() => ({
  getLlmProviderStatus: vi.fn(),
  savePlatformCredential: vi.fn(),
  deletePlatformCredential: vi.fn(),
  invalidateLlmCache: vi.fn(),
  // Every definition the page actually rendered a card for, in order.
  //
  // One entry per RENDER, because that is what a mocked component can observe. The assertions
  // below therefore read it through `cardedProviders()`, which de-duplicates: React may render a
  // card more than once (a state settling after the status fetch, StrictMode) and the question
  // these tests ask is WHICH providers got a card, never how many times React drew them. Asserted
  // on the raw array, they failed intermittently with ['anthropic', 'anthropic'].
  rendered: [] as LlmProviderDefinition[],
  saveHandlers: new Map<string, (i: string, k: string) => Promise<void>>(),
}));

vi.mock('next-intl', () => {
  const cache = new Map<string, (k: string) => string>();
  return {
    // The page reads the locale to route to the own-keys tab, so the mock has to answer it.
    useLocale: () => 'en',
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
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
vi.mock('@/lib/api/orchestrator/credential.service', () => ({
  credentialService: {
    getLlmProviderStatus: (...a: unknown[]) => h.getLlmProviderStatus(...a),
    savePlatformCredential: (...a: unknown[]) => h.savePlatformCredential(...a),
    deletePlatformCredential: (...a: unknown[]) => h.deletePlatformCredential(...a),
    invalidateLlmCache: (...a: unknown[]) => h.invalidateLlmCache(...a),
  },
}));
vi.mock('@/lib/api/cloud-link.service', () => ({
  cloudLinkService: { getStatus: vi.fn().mockResolvedValue({ linked: false, llmSource: 'BYOK' }) },
}));
vi.mock('@/lib/edition', () => ({ IS_CE: true, IS_CLOUD: false }));
vi.mock('../components/ProviderCard', () => ({
  default: ({ definition, onSave }: { definition: LlmProviderDefinition; onSave: (i: string, k: string) => Promise<void> }) => {
    h.rendered.push(definition);
    h.saveHandlers.set(definition.providerName, onSave);
    return <div data-testid={`provider-card-${definition.providerName}`} />;
  },
}));
vi.mock('../components/BridgeSetupPanel', () => ({ default: () => null }));
vi.mock('../components/BridgeAccessPanel', () => ({ default: () => null }));
vi.mock('../components/ModelManagementPanel', () => ({ default: () => null }));
vi.mock('../components/ModelExecutionLinksPanel', () => ({ default: () => null }));
vi.mock('../components/ModelBundleSyncButton', () => ({ ModelBundleSyncButton: () => null }));

import AiProvidersPage from '../page';

const advertise = (...providerNames: string[]) =>
  h.getLlmProviderStatus.mockResolvedValue(
    providerNames.map((providerName) => ({
      providerName,
      integrationName: `llm_${providerName}`,
      configured: false,
      hasDbKey: false,
      source: 'none',
    })),
  );

/** The providers that got a card, each once, whatever React did about re-rendering. */
const cardedProviders = () => [...new Set(h.rendered.map((d) => d.providerName))];

const renderPage = async () => {
  render(<AiProvidersPage />);
  await waitFor(() => expect(h.getLlmProviderStatus).toHaveBeenCalled());
};

describe('AiProvidersPage - the decision provider is keyable', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    h.rendered.length = 0;
    h.saveHandlers.clear();
    h.savePlatformCredential.mockResolvedValue(undefined);
    h.invalidateLlmCache.mockResolvedValue(undefined);
  });
  afterEach(cleanup);

  it('draws a key card for typesafe when the backend advertises it', async () => {
    advertise('anthropic', 'typesafe');

    await renderPage();

    await waitFor(() => expect(screen.queryByTestId('provider-card-typesafe')).toBeInTheDocument());
  });

  it('labels that card with the credential name the backend reads the key from', async () => {
    // The runtime resolves the key by provider name through the `llm_` convention
    // (LlmCredentialRepository.INTEGRATION_PREFIX). A card saving under any other
    // name stores a credential nothing will ever look up: the save succeeds, the
    // page shows a key, and every classification still fails unconfigured.
    advertise('typesafe');

    await renderPage();

    await waitFor(() => expect(cardedProviders()).toHaveLength(1));
    expect(h.rendered[0].integrationName).toBe('llm_typesafe');
  });

  it('saves through that credential name', async () => {
    advertise('typesafe');
    await renderPage();
    await waitFor(() => expect(h.saveHandlers.has('typesafe')).toBe(true));

    await h.saveHandlers.get('typesafe')!('llm_typesafe', 'ts-key');

    expect(h.savePlatformCredential).toHaveBeenCalledTimes(1);
    expect(h.savePlatformCredential.mock.calls[0][0]).toMatchObject({
      integrationName: 'llm_typesafe',
    });
    // The catalogue caches "is this provider configured"; a key saved behind a stale
    // cache leaves the node refusing to run for as long as the entry lives.
    expect(h.invalidateLlmCache).toHaveBeenCalledWith('typesafe');
  });

  it('drops a backend-advertised provider this file does not define, in silence', async () => {
    // The mechanism behind the bug: no error, no placeholder, no way to key it.
    advertise('anthropic', 'a-provider-with-no-definition');

    await renderPage();

    await waitFor(() => expect(screen.queryByTestId('provider-card-anthropic')).toBeInTheDocument());
    expect(screen.queryByTestId('provider-card-a-provider-with-no-definition')).toBeNull();
    expect(cardedProviders()).toEqual(['anthropic']);
  });
});
