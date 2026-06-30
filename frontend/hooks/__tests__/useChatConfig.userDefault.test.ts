import { afterEach, describe, expect, it, vi } from 'vitest';

// useChatConfig.ts imports the API clients + react-query at module load; stub them so the
// pure module functions under test (cache + consumeDraftChatConfig) import cleanly.
vi.mock('@/lib/api/orchestrator', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/api/conversationApi', () => ({ conversationApi: {} }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));

import {
  consumeDraftChatConfig,
  setUserDefaultChatConfigCache,
  clearUserDefaultChatConfigCache,
  clearDraftChatConfig,
} from '@/hooks/useChatConfig';

describe('useChatConfig - per-(user, workspace) default seeding', () => {
  afterEach(() => {
    clearUserDefaultChatConfigCache();
    clearDraftChatConfig();
  });

  it('seedsNewConversationFromThePersistedWorkspaceDefault', () => {
    setUserDefaultChatConfigCache({ webSearch: false, temperature: 0.3 });

    const body = consumeDraftChatConfig();

    expect(body).toMatchObject({ webSearch: false, temperature: 0.3 });
  });

  it('keepsSeedingEveryNewConversation (consume does not clear the persisted default)', () => {
    setUserDefaultChatConfigCache({ webSearch: false });

    const first = consumeDraftChatConfig();
    const second = consumeDraftChatConfig();

    expect(first).toMatchObject({ webSearch: false });
    expect(second).toMatchObject({ webSearch: false }); // default still applies to the next one
  });

  it('seedsImageGenerationDefault (regression: it was editable/persisted but not seeded)', () => {
    setUserDefaultChatConfigCache({ imageGeneration: { enabled: true } });

    const body = consumeDraftChatConfig();

    expect(body).toMatchObject({ imageGeneration: { enabled: true } });
  });

  it('returnsUndefinedWhenNoDefaultAndNoDraft (so the create body omits chatConfig)', () => {
    expect(consumeDraftChatConfig()).toBeUndefined();
  });

  it('clearingTheCacheStopsSeeding (e.g. on workspace switch)', () => {
    setUserDefaultChatConfigCache({ webSearch: false });
    clearUserDefaultChatConfigCache();

    expect(consumeDraftChatConfig()).toBeUndefined();
  });
});
