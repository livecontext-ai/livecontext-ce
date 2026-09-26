'use client';

/**
 * ChatPanelContent - Embedded AI chat for the side panel.
 *
 * Uses the same ChatCore component as ChatPageV2 and WorkflowMessagesPanel.
 * Creates/reuses a dedicated conversation for the side panel chat.
 */

import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { ChatCore } from '@/components/chat/ChatCore';
import { GenerateEntryButton } from '@/components/chat/GenerateEntryButton';
import { CreateGenerationModal } from '@/components/chat/CreateGenerationModal';
import { WelcomeTitle } from '@/app/shared/components';
import { ModelSelectorDropdown, PROVIDER_ICON_MAP } from '@/components/chat/ModelSelectorDropdown';
import { modelFilterLabelsFrom } from '@/components/chat/modelFilterLabels';
import { NoProviderCta } from '@/components/ai/NoProviderCta';
import { UpgradeRequiredNotice } from '@/components/billing/UpgradeRequiredBadge';
import { FreeTierBadge } from '@/components/billing/FreeTierBadge';
import { useMonthlyCreditsCannotPay } from '@/lib/hooks/useMonthlyCreditsCannotPay';
import { resolveFreeTierPreferredModel } from '@/lib/models/freeTierModel';
import { usePreferFreeTierModel } from '@/lib/hooks/usePreferFreeTierModel';
import { useStreaming } from '@/contexts/StreamingContext';
import { useVisibleModels, AIModel, SelectedModel, EMPTY_SELECTED_MODEL, modelMatches, selectedModelFromAIModel, selectedModelEquals, getEffectiveDefaultSelectedModel } from '@/hooks/useModels';
import { useUnifiedAppSafe } from '@/contexts/UnifiedAppContext';
import { conversationApi, type Message } from '@/lib/api/conversationApi';
import { reconcileMessageIdentity } from '@/lib/utils/messageUtils';
import { consumeDraftChatConfig, usePrimeUserChatDefaults } from '@/hooks/useChatConfig';
import { useTranslations } from 'next-intl';
import { usePathname } from 'next/navigation';
import type { AttachmentRef } from '@/lib/api/attachmentApi';
import { subscribeAiChatMessages } from '@/lib/sidePanelChat';
import { isOrbiChat } from '@/components/chat/orbi/isOrbiChat';
import type { Conversation } from '@/lib/api/conversationApi';

// Storage key prefix - suffixed with page context for per-page conversations
const SIDE_PANEL_CONVERSATION_PREFIX = 'livecontext_side_panel_conversation_id';

/**
 * Build a contextual storage key from the current pathname.
 * e.g. /en/app/tables/5 → "livecontext_side_panel_conversation_id:tables:5"
 *      /en/app/workflows/abc/run/xyz → "livecontext_side_panel_conversation_id:workflows:abc"
 *      /en/app → "livecontext_side_panel_conversation_id:home"
 */
function buildStorageKey(pathname: string | null): string {
  if (!pathname) return `${SIDE_PANEL_CONVERSATION_PREFIX}:home`;
  // Strip locale prefix (e.g. /en/app/... → /app/...)
  const stripped = pathname.replace(/^\/[a-z]{2}(?=\/)/, '');
  // Extract meaningful segments after /app/
  const match = stripped.match(/^\/app\/([^/]+)(?:\/([^/]+))?/);
  if (!match) return `${SIDE_PANEL_CONVERSATION_PREFIX}:home`;
  const section = match[1]; // e.g. "tables", "workflows", "agents"
  const id = match[2];      // e.g. "5", "abc-uuid"
  return id
    ? `${SIDE_PANEL_CONVERSATION_PREFIX}:${section}:${id}`
    : `${SIDE_PANEL_CONVERSATION_PREFIX}:${section}`;
}

export function ChatPanelContent() {
  const t = useTranslations();
  const streaming = useStreaming();
  const { models, defaultModel, isLoading: modelsLoading, error: modelsError } = useVisibleModels();
  // Same gate as ModelPicker: never show the no-provider empty state while the
  // catalog is loading or after a fetch error - only once it RESOLVED empty.
  const modelsResolvedEmpty = !modelsLoading && !modelsError;
  // Asked once for the whole menu: the answer is about the account, not
  // about any one model.
  const { blocked: creditsCannotPay, blockedForModel, freeTierForModel, prefersFreeTierModels, verdictReady } =
    useMonthlyCreditsCannotPay();
  const appContext = useUnifiedAppSafe();
  const pathname = usePathname();
  // Seed the side-panel composer's new conversations with the user's per-workspace defaults (V312).
  usePrimeUserChatDefaults();
  const storageKey = useMemo(() => buildStorageKey(pathname), [pathname]);
  const setSelectedModel = appContext?.setSelectedModel ?? ((_: SelectedModel) => {});
  const appSelectedModel: SelectedModel = appContext?.state.selectedModel ?? EMPTY_SELECTED_MODEL;

  // A Free account opens on a model its monthly credits cover, when one
  // exists. Without this the composer opens on the admin's global #1 and the very
  // first turn of a fresh signup can be refused.
  const defaultAIModel: AIModel | undefined = useMemo(
    () => resolveFreeTierPreferredModel(
      models,
      (defaultModel ? models.find(m => m.id === defaultModel) : undefined) ?? models[0],
      prefersFreeTierModels,
    ),
    [models, defaultModel, prefersFreeTierModels],
  );
  const effectiveDefault: SelectedModel = useMemo(
    () => (defaultAIModel ? selectedModelFromAIModel(defaultAIModel) : getEffectiveDefaultSelectedModel()),
    [defaultAIModel],
  );
  const isValidModel = models.length > 0 && !!appSelectedModel.id && models.some(m => modelMatches(m, appSelectedModel));
  const selectedModel: SelectedModel = isValidModel ? appSelectedModel : effectiveDefault;

  useEffect(() => {
    if (!appContext || isValidModel || !effectiveDefault.id) return;
    // V494: wait for the plan verdict before WRITING. prefersFreeTierModels is false
    // while the balance request is in flight, which is indistinguishable from a paid
    // account - and if the models land first, this effect pins the catalogue default,
    // isValidModel flips true, and the free-tier answer arriving a tick later never
    // applies. The selection is persisted, so that wrong default is permanent.
    if (!verdictReady) return;
    if (!selectedModelEquals(appSelectedModel, effectiveDefault)) {
      setSelectedModel(effectiveDefault);
    }
  }, [isValidModel, effectiveDefault, appSelectedModel, setSelectedModel, appContext, verdictReady]);

  // A Free account opens on the free tier's best-ranked model even when the browser
  // restored another one (the stored selection is not scoped to the account).
  usePreferFreeTierModel(models);

  const [showModelSelector, setShowModelSelector] = useState(false);

  const availableModels = useMemo(() => {
    // Spread the full AIModel so the dropdown's enriched display
    // (capability icons, context window, deprecation, rate-limit popover)
    // has the data it needs without a second round-trip.
    return models.map((model: AIModel) => ({
      ...model,
      provider: model.provider.charAt(0).toUpperCase() + model.provider.slice(1),
      providerSlug: model.provider.toLowerCase(),
      iconSlug: PROVIDER_ICON_MAP[model.provider.toLowerCase()] || model.provider.toLowerCase(),
    }));
  }, [models]);

  const selectedModelData = availableModels.find(m => modelMatches(m, selectedModel));

  // Model selector now lives in the composer (left of the mic). ModelSelectorDropdown
  // owns its own outside-click handling, so no effect is needed here.
  const leadingControl = (
    <ModelSelectorDropdown
      showModelSelector={showModelSelector}
      setShowModelSelector={setShowModelSelector}
      selectedModel={selectedModel}
      selectedModelData={selectedModelData}
      availableModels={availableModels}
      setSelectedModel={setSelectedModel}
      changeModelTitle={t('actions.changeModel')}
      noModelsLabel={modelsResolvedEmpty ? t('aiProviders.noProviderCta.noModels') : undefined}
      filterLabels={modelFilterLabelsFrom(t)}
      emptyState={modelsResolvedEmpty ? <NoProviderCta variant="menu" /> : undefined}
      upgradeRequired={creditsCannotPay}
      blockedForModel={blockedForModel}
      freeTierForModel={freeTierForModel}
      prefersFreeTierModels={prefersFreeTierModels}
      upgradeNotice={<UpgradeRequiredNotice blocked={creditsCannotPay} />}
      freeTierBadge={<FreeTierBadge covered />}
    />
  );

  const [conversationId, setConversationId] = useState<string | null>(null);
  // What the panel knows about its conversation, for the Orbi gate: a stored id can point at a
  // conversation this panel did not create, so it is read, never assumed.
  const [conversationMeta, setConversationMeta] = useState<Pick<Conversation, 'id' | 'kind' | 'agentId'> | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const conversationIdRef = useRef<string | null>(null);
  const loadedKeyRef = useRef<string | null>(null);

  // Keep ref in sync
  useEffect(() => {
    conversationIdRef.current = conversationId;
  }, [conversationId]);

  // Load the side-panel conversation - re-runs when storageKey changes (page navigation)
  useEffect(() => {
    if (loadedKeyRef.current === storageKey) return;
    loadedKeyRef.current = storageKey;

    // Reset state for new page context
    setConversationId(null);
    setConversationMeta(null);
    conversationIdRef.current = null;
    setMessages([]);

    const loadConversation = async () => {
      setIsLoading(true);
      try {
        // Try to reuse a previously stored conversation
        const storedId = typeof window !== 'undefined'
          ? sessionStorage.getItem(storageKey)
          : null;

        if (storedId) {
          try {
            const conv = await conversationApi.getConversation(storedId) as any;
            if (conv?.id) {
              setConversationId(conv.id);
              setConversationMeta({ id: conv.id, kind: conv.kind, agentId: conv.agentId });
              conversationIdRef.current = conv.id;
              const msgs = await conversationApi.getRecentMessagesAsc(conv.id);
              if (Array.isArray(msgs)) setMessages(msgs);
              streaming.checkAndReconnect(conv.id, {
                onStreamComplete: async (cid) => {
                  const reloaded = await conversationApi.getRecentMessagesAsc(cid);
                  // Identity-preserving: the reconciliation must not repaint the thread the
                  // user is reading (see reconcileMessageIdentity).
                  if (Array.isArray(reloaded)) setMessages(prev => reconcileMessageIdentity(prev, reloaded));
                },
              });
              return;
            }
          } catch {
            // Stored conversation no longer exists, create a new one
            sessionStorage.removeItem(storageKey);
          }
        }
        // No stored conversation - will be created on first message
      } catch (err) {
        console.error('[ChatPanelContent] Error loading conversation:', err);
      } finally {
        setIsLoading(false);
      }
    };

    loadConversation();
  }, [streaming, storageKey]);

  const handleSendMessage = useCallback(async (content?: string, attachments?: AttachmentRef[], defaultSkillIds?: string[], opts?: { keepPendingActions?: boolean }) => {
    if (!content?.trim()) return;

    // Bare model id (no "provider:" prefix) is what the backend expects in
    // the `model` field - anything else would make the pricing lookup miss
    // and the pre-flight gate would 402 with "Insufficient credits" even
    // for a well-funded user (the original bridge-chat bug).
    const currentModel = selectedModel.id;
    const currentProvider = selectedModel.provider;

    // Get or create conversation
    let cid = conversationIdRef.current;
    if (!cid) {
      try {
        // Seed the draft config (user-edited in the Options panel before any conversation
        // existed) into the new conversation, then clear it so the next fresh chat starts
        // blank. Returns undefined when no draft → field is simply omitted from the body.
        const draftChatConfig = consumeDraftChatConfig();
        const newConv = await conversationApi.createConversation({
          title: 'Side Panel Chat',
          model: currentModel,
          provider: currentProvider,
          ...(draftChatConfig ? { chatConfig: draftChatConfig } : {}),
        }) as any;
        if (newConv?.id) {
          cid = newConv.id;
          setConversationId(cid);
          setConversationMeta({ id: newConv.id, kind: newConv.kind, agentId: newConv.agentId });
          conversationIdRef.current = cid;
          if (typeof window !== 'undefined') {
            sessionStorage.setItem(storageKey, cid);
          }
        }
      } catch (err) {
        console.error('[ChatPanelContent] Failed to create conversation:', err);
        return;
      }
    }

    if (!cid) return;

    // Optimistically add user message. `pendingLocal: true` lets ChatCore's
    // auto-scroll effect distinguish this user-initiated append (always scroll)
    // from a streamed assistant chunk (gated by "near bottom"). Stripped before
    // any backend serialization by toWireMessage.
    const userMessage: Message = {
      id: `temp-${Date.now()}`,
      conversationId: cid,
      role: 'user',
      content: content.trim(),
      model: currentModel,
      timestamp: new Date().toISOString(),
      pendingLocal: true,
      attachments: attachments?.map(a => ({
        storageId: a.storageId,
        type: a.type as 'IMAGE' | 'PDF' | 'TEXT' | 'OTHER',
        fileName: a.fileName,
        mimeType: a.mimeType,
      })),
    };
    setMessages(prev => [...prev, userMessage]);

    try {
      await streaming.sendMessage(
        {
          message: content.trim(),
          model: currentModel,
          provider: currentProvider,
          conversationId: cid,
          history: messages.map(m => ({ role: m.role, content: m.content })),
          defaultSkillIds,
          keepPendingActions: opts?.keepPendingActions,
          attachments: attachments?.map(a => ({
            storageId: a.storageId,
            type: a.type as 'IMAGE' | 'PDF' | 'TEXT' | 'OTHER',
            fileName: a.fileName,
            mimeType: a.mimeType,
          })),
        },
        {
          onStreamComplete: async (convId) => {
            try {
              const reloaded = await conversationApi.getRecentMessagesAsc(convId);
              // Identity-preserving: an unchanged thread reconciles to the same array, so the
              // end-of-stream reconciliation commits nothing and is invisible.
              if (Array.isArray(reloaded)) setMessages(prev => reconcileMessageIdentity(prev, reloaded));
            } catch (err) {
              console.error('[ChatPanelContent] Failed to reload messages:', err);
            }
          },
          onError: (err) => {
            console.error('[ChatPanelContent] Stream error:', err);
          },
        },
      );
    } catch (err) {
      console.error('[ChatPanelContent] Error sending message:', err);
    }
  }, [selectedModel, messages, streaming]);

  const handleStopStream = useCallback(() => {
    if (conversationIdRef.current) {
      streaming.stopStream(conversationIdRef.current);
    }
  }, [streaming]);

  // Programmatic message bridge - callers (e.g. "Get AI help" button on the
  // Custom APIs settings page) can call queueAiChatMessage() to push a
  // pre-built prompt into this chat. We subscribe with stable refs so the
  // subscription doesn't churn while loading state changes.
  const handleSendMessageRef = useRef(handleSendMessage);
  const isLoadingRef = useRef(isLoading);
  const pendingProgrammaticMessageRef = useRef<string | null>(null);

  useEffect(() => {
    handleSendMessageRef.current = handleSendMessage;
  }, [handleSendMessage]);

  useEffect(() => {
    isLoadingRef.current = isLoading;
    if (!isLoading && pendingProgrammaticMessageRef.current) {
      const msg = pendingProgrammaticMessageRef.current;
      pendingProgrammaticMessageRef.current = null;
      handleSendMessageRef.current(msg);
    }
  }, [isLoading]);

  useEffect(() => {
    return subscribeAiChatMessages((msg) => {
      if (isLoadingRef.current) {
        pendingProgrammaticMessageRef.current = msg;
      } else {
        handleSendMessageRef.current(msg);
      }
    });
  }, []);

  /**
   * Generating from the panel opens a DIALOG, it does not travel.
   *
   * <p>The studio is a route, and the panel is docked beside whatever the reader is working on -
   * a workflow, a table, an application. Sending them to another page to make one image would
   * throw away the thing the panel exists to sit next to. So the panel keeps the wand it always
   * had, and the wand opens the generation dialog in place.
   *
   * <p>That is also why the panel carries no chat/studio switch: the switch NAVIGATES, which is
   * the one thing this surface must not do.
   */
  const [generationOpen, setGenerationOpen] = useState(false);
  const tChat = useTranslations('chat');

  return (
    <div className="h-full flex flex-col min-w-0 overflow-hidden">
      <ChatCore
        // At the END of the leading group, to the right of the tools button - not at its head,
        // which is where a surface-level switch belongs and generating is not one.
        // GenerateEntryButton renders NOTHING for a reader who cannot generate, or on an install
        // that serves no generation, so the gate lives in the control rather than here.
        trailingLeadingAction={(
          <GenerateEntryButton
            variant="icon"
            label={tChat('generateAsset')}
            onOpen={() => setGenerationOpen(true)}
          />
        )}
        conversationId={conversationId}
        messages={messages}
        isLoading={isLoading}
        onSendMessage={handleSendMessage}
        onStopStream={handleStopStream}
        hideWorkflowToggle
        hideDataSourceToggle
        className="flex-1 min-h-0 min-w-0"
        leadingControl={leadingControl}
        welcomeLayout
        welcomeTitle={<WelcomeTitle>{t('sidePanel.welcomeTitle')}</WelcomeTitle>}
        showOrbi={isOrbiChat({ conversationId, conversation: conversationMeta, agentId: null }) ? 'compact' : false}
      />

      {/* Mounted only while open: the dialog asks the catalogue for its models, and a panel that
          is never used for generation should cost that request nothing. */}
      {generationOpen && (
        <CreateGenerationModal isOpen onClose={() => setGenerationOpen(false)} />
      )}
    </div>
  );
}
