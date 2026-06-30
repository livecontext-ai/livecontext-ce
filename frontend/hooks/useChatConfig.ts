'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { orchestratorApi } from '@/lib/api/orchestrator';
import { conversationApi } from '@/lib/api/conversationApi';
import type { Agent } from '@/lib/api/orchestrator/types';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';

/**
 * Chat configuration, unified across agent conversations and general chats.
 *
 * - For agent conversations (agentId provided) the backing store is AgentEntity columns:
 *   temperature, systemPrompt, maxTokens, maxIterations, executionTimeout,
 *   toolsConfig.webSearch, and the V100 guard-override columns
 *   (maxPerResourcePerTurn, loopIdenticalStop, loopConsecutiveStop).
 *   `maxPerResourcePerTurn` is applied uniformly across all tracked resource types
 *   (agent / skill / sub_agent / interface / workflow / table).
 * - For general chats (no agentId, just conversationId) the backing store is
 *   conversation.chatConfig JSONB. Flat keys (temperature / systemPrompt / maxTokens /
 *   maxIterations / executionTimeout / toolsMode / webSearch) persist at the top level;
 *   turn-limit overrides persist under chatConfig.turnLimits.{key} - same 3 keys as the
 *   agent scope. The backend (AgentContextBuilder.buildChatConfig) mirrors this shape
 *   and feeds the resolved values into AgentLoopContext + tool-module credentials.
 */
/**
 * Image-generation tool config block (opt-IN).
 * Mirrored on AgentEntity.toolsConfig.imageGeneration AND on
 * conversation chat_config. Backend resolution lives in
 * {@code AgentModuleResolver.isImageGenerationEnabled} which accepts both
 * {@code true} and {@code { enabled: true, ... }} shapes - the frontend
 * always emits the object shape for forward-compat with future fields
 * (per-conversation budget cap, default size, etc).
 */
export interface ImageGenerationConfig {
  enabled: boolean;
  /** Default provider when the agent doesn't specify (e.g. 'openai' / 'google'). */
  provider?: string;
  /** Default model id (e.g. 'gpt-image-1.5' / 'gemini-2.5-flash-image'). */
  model?: string;
  /** Default quality tier for OpenAI models: 'low' | 'medium' | 'high'. */
  quality?: string;
}

export interface ChatConfig {
  temperature?: number;
  systemPrompt?: string;
  maxTokens?: number;
  maxIterations?: number;
  executionTimeout?: number;
  /** Inactivity watchdog window in seconds. 0 disables it; 10-7200 sets a custom window; omit for the platform default (5 min). Independent of executionTimeout. */
  inactivityTimeout?: number;
  /** 'all' | 'none' | 'custom' */
  toolsMode?: string;
  webSearch?: boolean;
  /** Image generation toggle (opt-IN - default OFF). */
  imageGeneration?: ImageGenerationConfig;
  /**
   * Per-conversation blanket grant: run sensitive tool actions (install / execute /
   * agent / catalog) without showing the authorization card. Opt-IN - default OFF.
   * The backend turns this into a "*" wildcard in __approvedToolActions__.
   */
  autoAuthorizeTools?: boolean;
  defaultSkillIds?: string[];
  /** Advanced turn limits - top-level AgentEntity columns (agents) or conversation.chatConfig.turnLimits (general chats) */
  maxPerResourcePerTurn?: number;
  loopIdenticalStop?: number;
  loopConsecutiveStop?: number;
  /**
   * Context compaction (COLD-summary) overrides - advanced.
   * Agent scope: top-level AgentEntity columns (compactionEnabled / compactionAfterTurns).
   * Conversation scope: conversation.chatConfig.compaction.{enabled,afterTurns}.
   * undefined ⇒ inherit (the next tier, then the platform default).
   */
  compactionEnabled?: boolean;
  compactionAfterTurns?: number;
}

export interface UseChatConfigOptions {
  agentId?: string | null;
  conversationId?: string | null;
  /**
   * When true, target the persisted per-(user, workspace) defaults (V312) - GET/PUT
   * /v3/chat/defaults - instead of agent/conversation/draft. Used by the account
   * Preferences page so the user can set the defaults that seed new conversations.
   */
  userDefault?: boolean;
  /** Debounce delay in ms for saves (default 500) */
  debounceMs?: number;
  /** Registers a promise that resolves after the current debounced save attempt finishes. */
  onPendingSave?: (save: Promise<void>) => void;
}

export interface UseChatConfigResult {
  config: ChatConfig;
  updateConfig: (partial: Partial<ChatConfig>) => void;
  isLoading: boolean;
  isSaving: boolean;
  error: string | null;
  /**
   * Where writes go:
   *   - 'agent'        → PUT /agents/{agentId}
   *   - 'conversation' → PUT /conversations/{conversationId}
   *   - 'user-default' → PUT /v3/chat/defaults (persisted per-(user, workspace))
   *   - 'draft'        → in-memory one-shot config for the next conversation create
   */
  target: 'agent' | 'conversation' | 'user-default' | 'draft';
}

/**
 * In-memory draft chat config used only until a conversation resource exists.
 * Shared with {@link consumeDraftChatConfig}.
 */
let draftChatConfig: ChatConfig = {};

/**
 * Persisted per-(user, workspace) defaults (V312), cached in-memory so a brand-new
 * conversation inherits them even when the composer Options panel was never opened.
 * Populated by {@link usePrimeUserChatDefaults} and the 'user-default' useChatConfig
 * target; cleared on workspace switch (useOrgScopedReset). The draft (user's per-next-
 * conversation edits) is layered ON TOP of this in {@link consumeDraftChatConfig}.
 */
let userDefaultChatConfig: ChatConfig = {};

export function setUserDefaultChatConfigCache(config: ChatConfig | null | undefined): void {
  userDefaultChatConfig = config ? { ...config } : {};
}

export function clearUserDefaultChatConfigCache(): void {
  userDefaultChatConfig = {};
}

function readDraftConfig(): ChatConfig {
  return { ...draftChatConfig };
}

function writeDraftConfig(config: ChatConfig): void {
  draftChatConfig = { ...config };
}

/**
 * Build the `chatConfig` body fragment (top-level flat keys + optional `turnLimits`
 * sub-object) expected by POST /conversations - same shape as
 * {@link buildConversationPatch}'s `chatConfig` payload, minus the outer wrapper.
 * Returns {@code undefined} when the draft is empty so callers can skip the field.
 *
 * The single consumer pattern: pass the returned value as `chatConfig` on the
 * create-conversation body, then call {@link clearDraftChatConfig}.
 */
export function buildDraftChatConfigBody(
  draft: ChatConfig | null | undefined,
): Record<string, unknown> | undefined {
  if (!draft) return undefined;
  const body: Record<string, unknown> = {};
  const simpleKeys: (keyof ChatConfig)[] = [
    'temperature',
    'systemPrompt',
    'maxTokens',
    'maxIterations',
    'executionTimeout',
    'inactivityTimeout',
    'toolsMode',
    'webSearch',
    // imageGeneration is editable as a workspace default AND accepted by the conversation
    // patch - seed it too so an image-gen default actually reaches new conversations.
    'imageGeneration',
    'autoAuthorizeTools',
    'defaultSkillIds',
  ];
  for (const key of simpleKeys) {
    const v = draft[key];
    if (v !== undefined) body[key] = v;
  }
  const turnLimits: Record<string, number> = {};
  for (const key of AGENT_TURN_LIMIT_KEYS) {
    const v = draft[key];
    if (typeof v === 'number' && Number.isFinite(v)) {
      turnLimits[key] = v;
    }
  }
  if (Object.keys(turnLimits).length > 0) body.turnLimits = turnLimits;
  const compaction = buildCompactionBlock(draft.compactionEnabled, draft.compactionAfterTurns);
  if (compaction) body.compaction = compaction;
  return Object.keys(body).length > 0 ? body : undefined;
}

/**
 * Read the in-memory draft config, build the POST body fragment, then clear it.
 * Call this from the one place that creates a conversation so the draft is consumed
 * exactly once.
 */
export function consumeDraftChatConfig(): Record<string, unknown> | undefined {
  // Persisted per-(user, workspace) defaults are the base; the in-memory draft (this
  // user's per-next-conversation tweaks) overrides them. So a new conversation always
  // inherits the user's saved defaults unless they edited the composer Options first.
  const draft = { ...userDefaultChatConfig, ...readDraftConfig() };
  const body = buildDraftChatConfigBody(draft);
  draftChatConfig = {};
  return body;
}

/** Clear the draft config (e.g. after cancelling or explicit reset). */
export function clearDraftChatConfig(): void {
  draftChatConfig = {};
}

type TurnLimits = Pick<
  ChatConfig,
  'maxPerResourcePerTurn' | 'loopIdenticalStop' | 'loopConsecutiveStop'
>;

const TURN_LIMIT_KEYS: readonly (keyof TurnLimits)[] = [
  'maxPerResourcePerTurn',
  'loopIdenticalStop',
  'loopConsecutiveStop',
] as const;

function extractTurnLimits(source: Record<string, unknown> | null | undefined): TurnLimits {
  if (!source) return {};
  const out: TurnLimits = {};
  for (const key of TURN_LIMIT_KEYS) {
    const val = source[key];
    if (typeof val === 'number' && Number.isFinite(val)) {
      out[key] = val;
    }
  }
  return out;
}

type CompactionConfig = Pick<ChatConfig, 'compactionEnabled' | 'compactionAfterTurns'>;

/**
 * Read the compaction override from a {@code chatConfig.compaction} sub-object
 * ({enabled, afterTurns}). Missing/invalid fields are omitted so the UI inherits.
 */
function extractCompaction(source: Record<string, unknown> | null | undefined): CompactionConfig {
  const comp = (source?.compaction ?? {}) as Record<string, unknown>;
  const out: CompactionConfig = {};
  if (typeof comp.enabled === 'boolean') out.compactionEnabled = comp.enabled;
  if (typeof comp.afterTurns === 'number' && Number.isFinite(comp.afterTurns)) {
    out.compactionAfterTurns = comp.afterTurns;
  }
  return out;
}

/**
 * Build the {@code compaction} sub-object ({enabled?, afterTurns?}) for a
 * conversation chatConfig / new-conversation seed. Returns {@code undefined}
 * when neither field is set so callers can skip the key entirely.
 */
function buildCompactionBlock(
  enabled: boolean | undefined,
  afterTurns: number | undefined,
): { enabled?: boolean; afterTurns?: number } | undefined {
  const block: { enabled?: boolean; afterTurns?: number } = {};
  if (typeof enabled === 'boolean') block.enabled = enabled;
  if (typeof afterTurns === 'number' && Number.isFinite(afterTurns)) block.afterTurns = afterTurns;
  return Object.keys(block).length > 0 ? block : undefined;
}

/**
 * Reads {@code imageGeneration} from a {@code toolsConfig} JSONB blob.
 * Tolerates both shapes (boolean true OR {enabled, ...}); returns
 * {@code undefined} when absent so the UI falls back to the default
 * (disabled).
 */
function extractImageGeneration(source: Record<string, unknown> | null | undefined): ImageGenerationConfig | undefined {
  if (!source) return undefined;
  const raw = source.imageGeneration;
  if (raw === undefined || raw === null) return undefined;
  if (typeof raw === 'boolean') return { enabled: raw };
  if (typeof raw === 'object') {
    const obj = raw as Record<string, unknown>;
    return {
      enabled: obj.enabled === true,
      provider: typeof obj.provider === 'string' ? (obj.provider as string) : undefined,
      model: typeof obj.model === 'string' ? (obj.model as string) : undefined,
      quality: typeof obj.quality === 'string' ? (obj.quality as string) : undefined,
    };
  }
  return undefined;
}

export function configFromAgent(agent: Agent): ChatConfig {
  const toolsCfg = (agent.toolsConfig ?? {}) as Record<string, unknown>;
  return {
    temperature: typeof agent.temperature === 'number' ? agent.temperature : undefined,
    systemPrompt: agent.systemPrompt,
    maxTokens: typeof agent.maxTokens === 'number' ? agent.maxTokens : undefined,
    maxIterations: typeof agent.maxIterations === 'number' ? agent.maxIterations : undefined,
    executionTimeout: typeof agent.executionTimeout === 'number' ? agent.executionTimeout : undefined,
    inactivityTimeout: typeof agent.inactivityTimeout === 'number' ? agent.inactivityTimeout : undefined,
    toolsMode: typeof toolsCfg.mode === 'string' ? (toolsCfg.mode as string) : undefined,
    webSearch: toolsCfg.webSearch === false ? false : true,
    imageGeneration: extractImageGeneration(toolsCfg),
    compactionEnabled: typeof agent.compactionEnabled === 'boolean' ? agent.compactionEnabled : undefined,
    compactionAfterTurns: typeof agent.compactionAfterTurns === 'number' ? agent.compactionAfterTurns : undefined,
    ...extractTurnLimits(agent as unknown as Record<string, unknown>),
  };
}

export function configFromConversation(raw: { chatConfig?: Record<string, unknown> } | null | undefined): ChatConfig {
  const cfg = (raw?.chatConfig ?? {}) as Record<string, unknown>;
  const turnLimitsRaw = (cfg.turnLimits ?? {}) as Record<string, unknown>;
  return {
    temperature: typeof cfg.temperature === 'number' ? (cfg.temperature as number) : undefined,
    systemPrompt: typeof cfg.systemPrompt === 'string' ? (cfg.systemPrompt as string) : undefined,
    maxTokens: typeof cfg.maxTokens === 'number' ? (cfg.maxTokens as number) : undefined,
    maxIterations: typeof cfg.maxIterations === 'number' ? (cfg.maxIterations as number) : undefined,
    executionTimeout: typeof cfg.executionTimeout === 'number' ? (cfg.executionTimeout as number) : undefined,
    inactivityTimeout: typeof cfg.inactivityTimeout === 'number' ? (cfg.inactivityTimeout as number) : undefined,
    toolsMode: typeof cfg.toolsMode === 'string' ? (cfg.toolsMode as string) : undefined,
    webSearch: cfg.webSearch === false ? false : cfg.webSearch === true ? true : undefined,
    autoAuthorizeTools: cfg.autoAuthorizeTools === true ? true : cfg.autoAuthorizeTools === false ? false : undefined,
    imageGeneration: extractImageGeneration(cfg),
    defaultSkillIds: Array.isArray(cfg.defaultSkillIds)
      ? cfg.defaultSkillIds.filter((id): id is string => typeof id === 'string')
      : undefined,
    ...extractTurnLimits(turnLimitsRaw),
    ...extractCompaction(cfg),
  };
}

/**
 * Build the body sent to PUT /agents/{id} from a ChatConfig partial.
 * Turn-limit fields map to top-level AgentEntity columns (V100 unified cap).
 */
const AGENT_TURN_LIMIT_KEYS: readonly (keyof TurnLimits)[] = [
  'maxPerResourcePerTurn',
  'loopIdenticalStop',
  'loopConsecutiveStop',
] as const;

export function buildAgentPatch(
  current: Agent,
  partial: Partial<ChatConfig>,
): Record<string, unknown> {
  const patch: Record<string, unknown> = {
    // Backend validation requires `name` to be echoed back on PUT.
    name: current.name,
  };

  if (partial.temperature !== undefined) patch.temperature = partial.temperature;
  if (partial.systemPrompt !== undefined) patch.systemPrompt = partial.systemPrompt;
  if (partial.maxTokens !== undefined) patch.maxTokens = partial.maxTokens;
  if (partial.maxIterations !== undefined) patch.maxIterations = partial.maxIterations;
  if (partial.executionTimeout !== undefined) patch.executionTimeout = partial.executionTimeout;
  if (partial.inactivityTimeout !== undefined) patch.inactivityTimeout = partial.inactivityTimeout;

  if (partial.toolsMode !== undefined || partial.webSearch !== undefined || partial.imageGeneration !== undefined) {
    const existingTools = (current.toolsConfig ?? {}) as Record<string, unknown>;
    patch.toolsConfig = {
      ...existingTools,
      ...(partial.toolsMode !== undefined ? { mode: partial.toolsMode } : {}),
      ...(partial.webSearch !== undefined ? { webSearch: partial.webSearch } : {}),
      ...(partial.imageGeneration !== undefined ? { imageGeneration: partial.imageGeneration } : {}),
    };
  }

  for (const key of AGENT_TURN_LIMIT_KEYS) {
    if (partial[key] !== undefined) {
      patch[key] = partial[key];
    }
  }

  // Compaction overrides map to top-level AgentEntity columns (V350). The backend
  // patch-setter treats a present key as set (null clears, back to inherit).
  if (partial.compactionEnabled !== undefined) patch.compactionEnabled = partial.compactionEnabled;
  if (partial.compactionAfterTurns !== undefined) patch.compactionAfterTurns = partial.compactionAfterTurns;

  return patch;
}

/**
 * Build the body sent to PUT /conversations/{id} for chatConfig updates.
 * Flat keys persist at the top level of chatConfig; turn-limit overrides persist
 * under chatConfig.turnLimits.{key} (same 3 keys as the agent scope; backend
 * {@code GuardOverrides} validates the shape).
 */
export function buildConversationPatch(
  currentConfig: ChatConfig,
  partial: Partial<ChatConfig>,
): Record<string, unknown> {
  const mergedFlat: Record<string, unknown> = {};
  const simpleKeys: (keyof ChatConfig)[] = [
    'temperature',
    'systemPrompt',
    'maxTokens',
    'maxIterations',
    'executionTimeout',
    'inactivityTimeout',
    'toolsMode',
    'webSearch',
    'imageGeneration',
    'autoAuthorizeTools',
    'defaultSkillIds',
  ];
  for (const key of simpleKeys) {
    const val = partial[key] !== undefined ? partial[key] : currentConfig[key];
    if (val !== undefined) mergedFlat[key] = val;
  }

  // Turn-limit overrides live under chatConfig.turnLimits. Merge current + partial so a
  // single-field edit (e.g. raise loopIdenticalStop) keeps the rest untouched.
  const mergedTurnLimits: Record<string, number> = {};
  for (const key of AGENT_TURN_LIMIT_KEYS) {
    const val = partial[key] !== undefined ? partial[key] : currentConfig[key];
    if (typeof val === 'number' && Number.isFinite(val)) {
      mergedTurnLimits[key] = val;
    }
  }
  if (Object.keys(mergedTurnLimits).length > 0) {
    mergedFlat.turnLimits = mergedTurnLimits;
  }

  // Compaction override lives under chatConfig.compaction.{enabled,afterTurns}.
  // Merge current + partial so a single-field edit keeps the other untouched.
  const compaction = buildCompactionBlock(
    partial.compactionEnabled !== undefined ? partial.compactionEnabled : currentConfig.compactionEnabled,
    partial.compactionAfterTurns !== undefined ? partial.compactionAfterTurns : currentConfig.compactionAfterTurns,
  );
  if (compaction) mergedFlat.compaction = compaction;

  return {
    chatConfig: mergedFlat,
  };
}

/**
 * Centralized hook for reading and writing chat/agent configuration.
 *
 * Write routing:
 *   - If agentId is provided → PUT /agents/{agentId}
 *   - Else if conversationId is provided → PUT /conversations/{conversationId}
 *   - Otherwise → in-memory draft, seeded into POST /conversations by
 *     {@link consumeDraftChatConfig} when the user creates their first conversation.
 */
export function useChatConfig(options: UseChatConfigOptions): UseChatConfigResult {
  const { agentId = null, conversationId = null, userDefault = false, debounceMs = 500, onPendingSave } = options;
  const queryClient = useQueryClient();

  const target: 'agent' | 'conversation' | 'user-default' | 'draft' = userDefault
    ? 'user-default'
    : agentId
      ? 'agent'
      : conversationId
        ? 'conversation'
        : 'draft';

  // Fetch the persisted per-(user, workspace) defaults (when target === 'user-default').
  const userDefaultsQuery = useQuery<Record<string, unknown>>({
    queryKey: ['user-chat-defaults'],
    queryFn: () => conversationApi.getUserChatDefaults(),
    enabled: target === 'user-default',
    staleTime: 30_000,
  });

  // Fetch agent (when target === 'agent')
  const agentQuery = useQuery<Agent | null>({
    queryKey: ['agent-config', agentId],
    queryFn: async () => (agentId ? orchestratorApi.getAgent(agentId) : null),
    enabled: !!agentId,
    staleTime: 30_000,
  });

  // Fetch conversation (when target === 'conversation' and no agentId)
  const conversationQuery = useQuery<{ chatConfig?: Record<string, unknown> } | null>({
    queryKey: ['conversation-config', conversationId],
    queryFn: async () =>
      conversationId
        ? ((await conversationApi.getConversation(conversationId)) as { chatConfig?: Record<string, unknown> } | null)
        : null,
    enabled: !agentId && !!conversationId,
    staleTime: 30_000,
  });

  // Derive config from whichever source is loaded. For the draft target we hydrate once
  // from the in-memory one-shot draft.
  const [localConfig, setLocalConfig] = useState<ChatConfig>(() =>
    !agentId && !conversationId ? readDraftConfig() : {},
  );
  const hydratedRef = useRef(false);

  useEffect(() => {
    if (target === 'agent' && agentQuery.data) {
      setLocalConfig(configFromAgent(agentQuery.data));
      hydratedRef.current = true;
    } else if (target === 'conversation' && conversationQuery.data) {
      setLocalConfig(configFromConversation(conversationQuery.data));
      hydratedRef.current = true;
    } else if (target === 'user-default' && userDefaultsQuery.data) {
      setLocalConfig(userDefaultsQuery.data as ChatConfig);
      setUserDefaultChatConfigCache(userDefaultsQuery.data as ChatConfig);
      hydratedRef.current = true;
    } else if (target === 'draft' && !hydratedRef.current) {
      // Show the persisted per-(user, workspace) defaults as the base (populated by
      // usePrimeUserChatDefaults), with any in-memory per-next-conversation edits on top.
      setLocalConfig({ ...userDefaultChatConfig, ...readDraftConfig() });
      hydratedRef.current = true;
    }
  }, [target, agentQuery.data, conversationQuery.data, userDefaultsQuery.data]);

  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingRef = useRef<Partial<ChatConfig>>({});
  const flushSaveRef = useRef<() => Promise<void>>(async () => {});
  const pendingSaveWaitersRef = useRef<Array<() => void>>([]);

  const registerPendingSave = useCallback(() => {
    const save = new Promise<void>((resolve) => {
      pendingSaveWaitersRef.current.push(resolve);
    });
    onPendingSave?.(save);
  }, [onPendingSave]);

  const resolvePendingSaveWaiters = useCallback((waiters: Array<() => void>) => {
    for (const resolve of waiters) resolve();
  }, []);

  useOrgScopedReset(() => {
    if (target === 'user-default') {
      // Defaults are per (user, workspace): on workspace switch, drop the cache and
      // refetch for the new workspace.
      clearUserDefaultChatConfigCache();
      void queryClient.invalidateQueries({ queryKey: ['user-chat-defaults'] });
      return;
    }
    if (target !== 'draft') return;
    draftChatConfig = {};
    pendingRef.current = {};
    if (saveTimerRef.current) {
      clearTimeout(saveTimerRef.current);
      saveTimerRef.current = null;
    }
    setLocalConfig({});
  });

  const flushSave = useCallback(async () => {
    const patch = pendingRef.current;
    if (Object.keys(patch).length === 0) {
      const waiters = pendingSaveWaitersRef.current;
      pendingSaveWaitersRef.current = [];
      resolvePendingSaveWaiters(waiters);
      return;
    }

    if (target === 'agent' && (!agentId || !agentQuery.data)) {
      return;
    }
    if (target === 'conversation' && !conversationId) {
      return;
    }

    pendingRef.current = {};
    const waiters = pendingSaveWaitersRef.current;
    pendingSaveWaitersRef.current = [];

    setIsSaving(true);
    setError(null);
    try {
      if (target === 'agent' && agentId && agentQuery.data) {
        const body = buildAgentPatch(agentQuery.data, patch);
        const updated = await orchestratorApi.updateAgent(agentId, body as Partial<Agent>);
        queryClient.setQueryData(['agent-config', agentId], updated);
      } else if (target === 'conversation' && conversationId) {
        const body = buildConversationPatch(localConfig, patch);
        await conversationApi.updateConversation(conversationId, body);
        await queryClient.invalidateQueries({ queryKey: ['conversation-config', conversationId] });
      } else if (target === 'user-default') {
        // Persist the full merged config to /v3/chat/defaults (sanitized server-side).
        const merged = { ...localConfig, ...patch };
        const saved = await conversationApi.updateUserChatDefaults(merged as Record<string, unknown>);
        setUserDefaultChatConfigCache(saved as ChatConfig);
        queryClient.setQueryData(['user-chat-defaults'], saved);
      } else if (target === 'draft') {
        // Draft target: persist the merged config (not just the patch) in memory so
        // the seed for the next conversation reflects the full edited state.
        writeDraftConfig({ ...localConfig, ...patch });
      }
    } catch (err) {
      pendingRef.current = { ...patch, ...pendingRef.current };
      console.error('[useChatConfig] save failed', err);
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setIsSaving(false);
      resolvePendingSaveWaiters(waiters);
    }
  }, [agentId, conversationId, target, agentQuery.data, localConfig, queryClient, resolvePendingSaveWaiters]);

  useEffect(() => {
    flushSaveRef.current = flushSave;
  }, [flushSave]);

  useEffect(() => {
    const hasPending = Object.keys(pendingRef.current).length > 0;
    const ready =
      target === 'draft' ||
      target === 'user-default' ||
      (target === 'conversation' && !!conversationId) ||
      (target === 'agent' && !!agentId && !!agentQuery.data);
    if (hasPending && ready && !saveTimerRef.current) {
      void flushSave();
    }
  }, [agentId, conversationId, target, agentQuery.data, flushSave]);

  const updateConfig = useCallback(
    (partial: Partial<ChatConfig>) => {
      setLocalConfig(prev => ({ ...prev, ...partial }));
      pendingRef.current = { ...pendingRef.current, ...partial };
      registerPendingSave();

      if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
      saveTimerRef.current = setTimeout(() => {
        saveTimerRef.current = null;
        void flushSave();
      }, debounceMs);
    },
    [debounceMs, flushSave, registerPendingSave],
  );

  // Flush on unmount
  useEffect(() => {
    return () => {
      if (saveTimerRef.current) {
        clearTimeout(saveTimerRef.current);
        saveTimerRef.current = null;
      }
      if (Object.keys(pendingRef.current).length > 0) {
        void flushSaveRef.current();
      }
    };
  }, []);

  return {
    config: localConfig,
    updateConfig,
    isLoading:
      (target === 'agent' && agentQuery.isLoading) ||
      (target === 'conversation' && conversationQuery.isLoading) ||
      (target === 'user-default' && userDefaultsQuery.isLoading),
    isSaving,
    error,
    target,
  };
}

/**
 * Prime the in-memory user-default cache so brand-new conversations inherit the
 * persisted per-(user, workspace) defaults (V312) even when the composer Options panel
 * is never opened. Mount once on a chat surface (ChatPageV2 / side panel). Re-fetches +
 * clears on workspace switch.
 */
export function usePrimeUserChatDefaults(): void {
  const queryClient = useQueryClient();
  const { data } = useQuery<Record<string, unknown>>({
    queryKey: ['user-chat-defaults'],
    queryFn: () => conversationApi.getUserChatDefaults(),
    staleTime: 30_000,
  });

  useEffect(() => {
    if (data) setUserDefaultChatConfigCache(data as ChatConfig);
  }, [data]);

  useOrgScopedReset(() => {
    clearUserDefaultChatConfigCache();
    void queryClient.invalidateQueries({ queryKey: ['user-chat-defaults'] });
  });
}
