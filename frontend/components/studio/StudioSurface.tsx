'use client';

import * as React from 'react';
import { AlertCircle, Loader2 } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter } from '@/i18n/navigation';
import { conversationApi, conversationKind, type Conversation } from '@/lib/api/conversationApi';
import { useGenerationModels } from '@/hooks/useGenerationModels';
import { useStudioTurn } from '@/hooks/useStudioTurn';
import { buildStudioTurns } from '@/lib/generation/studioThread';
import { pickDefaultModel } from '@/lib/generation/defaultModel';
import { takeStudioRecipe, recipeToRequest } from '@/lib/generation/studioHandoff';
import { STUDIO_MESSAGE_TYPE, generationWasCharged, type StudioRequestEnvelope } from '@/lib/generation/studioMessage';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';
import { StudioComposer } from '@/components/studio/StudioComposer';
import { StudioBackdrop } from '@/components/studio/StudioBackdrop';
import { useMobileDetection } from '@/hooks/useMobileDetection';
import { StudioTurnCard } from '@/components/studio/StudioTurnCard';
import { StudioApps } from '@/components/studio/StudioApps';
import { GenerationHistoryList } from '@/components/generation/GenerationHistoryList';
import { StudioDynamicTitle } from '@/components/studio/StudioDynamicTitle';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { HomeModeSwitch } from '@/components/chat/HomeModeSwitch';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { openFilesPanel } from '@/lib/sidePanel/openFilesPanel';
import type { GenerationHistoryEntry } from '@/lib/api/storage-api';
import {
  outcomeOfGenerationResult,
  trackStudioGenerationSubmitted,
  trackStudioModelSelected,
} from '@/lib/generation/studioAnalytics';
import type { StudioTurnErrorCode } from '@/hooks/useStudioTurn';

/**
 * The studio: a composer, and the thread of what has been made with it.
 *
 * <p><b>Why this is not the chat surface with a different composer.</b> A chat thread carries tool
 * activity, streaming, approval cards, compaction dividers, agent avatars and per-conversation
 * configuration. A studio thread has one shape - a prompt, a model, an asset - and none of that
 * machinery applies to it. Rendering studio turns through the chat renderer would mean teaching
 * every one of those features to ignore a message shape it has no opinion about, and each of them
 * is a place the ignoring can be forgotten.
 *
 * <p><b>The mode cannot be left from inside.</b> There is no control here that turns this into a
 * chat, because the conversation's kind is fixed when it is created and the server refuses a
 * change. What CAN change is the model: a studio conversation carries no context between turns, so
 * switching model invalidates nothing, and each turn records the model that ran it.
 */

export interface StudioSurfaceProps {
  /** The studio conversation being read, or null for a new one. */
  conversationId?: string | null;
}

/** How many messages of a studio thread are read at once. Each turn is two. */
const STUDIO_PAGE_SIZE = 60;

export function StudioSurface({ conversationId = null }: StudioSurfaceProps) {
  const t = useTranslations('studio');
  const router = useRouter();
  const queryClient = useQueryClient();
  const canGenerate = useCanMutateInCurrentOrg();
  // Safe, not required: this surface renders in tests and in any host that has no panel,
  // and a thrown context here would take the whole studio down to open a file.
  const sidePanel = useSidePanelSafe();
  const { models, availability, isLoading: modelsLoading } = useGenerationModels(canGenerate);

  const [selectedModel, setSelectedModel] = React.useState<GenerationModel | null>(null);
  // A prompt handed back by "reuse", consumed once by the composer.
  const [reused, setReused] = React.useState<StudioRequestEnvelope | null>(null);
  // A conversation created by a turn whose answer never arrived, or by the turn that has just been
  // submitted. Every read below is driven off it as well as off the prop, so a studio that created
  // its conversation is not left reading nothing while the route still says it has no id.
  const [createdConversationId, setCreatedConversationId] = React.useState<string | null>(null);
  // The turn currently in flight, drawn by this component rather than read back from the
  // conversation.
  //
  // <p>The thread is refreshed only once the ANSWER is written. Refreshing after the request would
  // show a request with no answer, which on screen is the state of a turn whose answer never
  // arrived - complete with its "this may still have been charged" warning, on a generation that is
  // running normally.
  const [pendingRequest, setPendingRequest] = React.useState<StudioRequestEnvelope | null>(null);
  // Who pays, and on which key. Held here rather than in the composer because the turn is submitted
  // from here: a choice the composer owned would have to be read back out of it at submit time.
  // 640px = Tailwind's `sm`, the width the chat home changes shape at.
  const isNarrowViewport = useMobileDetection(640);
  const [credentialSource, setCredentialSource] = React.useState<'platform' | 'user'>('platform');
  // The composer's place on the page, so a recipe picked from the history further down can bring
  // the reader back to the box it just filled. Only the empty desktop layout attaches it.
  const composerAnchorRef = React.useRef<HTMLDivElement | null>(null);
  const [credentialId, setCredentialId] = React.useState<number | null>(null);

  /**
   * Selecting a model, which also means forgetting whose key paid for the last one.
   *
   * <p>A credential belongs to an INTEGRATION: an ElevenLabs key is not an account on Seedance. The
   * composer already drops the values and the files on a model change; leaving the payer behind sent
   * model B a credential id minted for model A. That does not fail loudly - the execution path
   * substitutes a key it can use and carries on, in a comment of its own calling the substitution
   * "otherwise invisible" - so the generation runs, on an account the reader did not choose, with
   * the price hidden because the composer believes the reader's own key is paying.
   *
   * <p>Reset only when the integration actually changes: switching between two models of the same
   * provider keeps a choice that is still valid, and re-picking it every time would be its own
   * annoyance.
   */
  const handleSelectModel = React.useCallback((next: GenerationModel) => {
    if (selectedModel && selectedModel.integrationName !== next.integrationName) {
      setCredentialSource('platform');
      setCredentialId(null);
    }
    setSelectedModel(next);
  }, [selectedModel]);

  // The composer's pick is the reader's choice; the other callers of handleSelectModel restore a
  // model (from the thread, a recipe, a reuse) and are not reported.
  const handleUserSelectModel = React.useCallback((next: GenerationModel) => {
    trackStudioModelSelected(next, 'studio');
    handleSelectModel(next);
  }, [handleSelectModel]);

  // The conversation this surface is actually reading: the one in the route, or the one a turn
  // created before the route caught up. Without the second half, a studio that created its
  // conversation and then lost the connection reads nothing at all, so the request it holds - which
  // may have been paid for - is invisible on the very screen that would show it.
  const activeConversationId = conversationId ?? createdConversationId;

  const messagesKey = React.useMemo(
    () => ['studio-messages', activeConversationId] as const,
    [activeConversationId],
  );
  // The conversation itself, read for ONE reason: what it is.
  //
  // Without this the guard is one-directional. The chat surface sends a studio conversation here,
  // but nothing sent a chat conversation away, so opening a chat id at a studio URL rendered an
  // empty studio over it and the first turn appended generation envelopes into a chat - where the
  // chat renderer shows raw JSON and the chat pipeline feeds them to a model as context. The whole
  // immutability apparatus is bypassed at the one surface that writes.
  const { data: conversation, isError: conversationUnreadable } = useQuery({
    queryKey: ['conversation', activeConversationId] as const,
    queryFn: () => conversationApi.getConversation(activeConversationId as string) as Promise<Conversation>,
    enabled: !!activeConversationId,
    staleTime: 60_000,
  });
  const isChatConversation = !!conversation && conversationKind(conversation) !== 'studio';
  // While the answer is outstanding the kind is UNKNOWN, which is not the same as "studio": a submit
  // in that window would write generation envelopes into whatever this turns out to be.
  //
  // "Not known YET" and "could not be read" are deliberately separate. Treating them alike is a
  // silent dead end: a deleted conversation, a 403 or a dropped request leaves the query with no
  // data FOREVER, so every press of Create would do nothing, say nothing, and leave the button
  // enabled. A refusal has to be visible to be a refusal.
  const conversationPending = !!activeConversationId && conversation === undefined && !conversationUnreadable;
  const cannotSubmit = conversationPending || conversationUnreadable || isChatConversation;
  React.useEffect(() => {
    if (!activeConversationId || !isChatConversation) return;
    // `replace`, so Back returns where the reader came from rather than bouncing between routes.
    router.replace(`/app/c/${activeConversationId}`);
  }, [activeConversationId, isChatConversation, router]);

  const { data: messages = [], isLoading: messagesLoading } = useQuery({
    queryKey: messagesKey,
    queryFn: () => conversationApi.getRecentMessagesAsc(activeConversationId as string, STUDIO_PAGE_SIZE),
    enabled: !!activeConversationId,
    // A studio thread only changes when this session writes to it: nothing streams into it, and a
    // generation cannot be started from anywhere else.
    staleTime: 30_000,
  });

  const turns = React.useMemo(() => buildStudioTurns(messages), [messages]);

  // The catalogue's own mark for the model that ran each turn. Resolved here rather than stored in
  // the envelope: an icon is presentation and changes with the catalogue.
  const iconByModel = React.useMemo(() => {
    const map = new Map<string, string | null>();
    for (const model of models) map.set(model.model, model.iconSlug);
    return map;
  }, [models]);

  // The default model, once the catalogue lands. Deliberately not persisted per reader: the model
  // is a per-turn decision and the catalogue changes under it, so a remembered id can name a model
  // that no longer exists. The preference and its fallbacks live in pickDefaultModel.
  React.useEffect(() => {
    if (selectedModel || models.length === 0) return;
    const preferred = pickDefaultModel(models);
    if (preferred) setSelectedModel(preferred);
  }, [models, selectedModel]);

  // Reading an existing thread: adopt the model of its most recent turn, which is what the reader
  // was last working with.
  const adoptedRef = React.useRef<string | null>(null);
  React.useEffect(() => {
    if (!conversationId || models.length === 0) return;
    if (adoptedRef.current === conversationId) return;
    const lastModelId = turns.length > 0 ? turns[turns.length - 1].request.model : null;
    if (!lastModelId) return;
    adoptedRef.current = conversationId;
    const match = models.find((model) => model.model === lastModelId);
    if (match) handleSelectModel(match);
  }, [conversationId, models, turns, handleSelectModel]);

  // A recipe handed over from wherever the asset was shown (Files, a turn card elsewhere). Read
  // once, on mount, and cleared by the read: a recipe left in flight would refill the composer days
  // later with a prompt the reader had forgotten writing.
  //
  // Held aside rather than applied immediately, because a replay has to select the model it names
  // and the catalogue has usually not landed yet on the first render. Applied by the effect below,
  // once there is a catalogue to find the model in.
  const [pendingRecipe, setPendingRecipe] = React.useState<StudioRequestEnvelope | null>(null);
  React.useEffect(() => {
    setPendingRecipe(takeStudioRecipe());
  }, []);

  const handleConversationCreated = React.useCallback((created: Conversation) => {
    setCreatedConversationId(created.id);
    // The sidebar's conversation cache is deliberately sticky (it does not refetch on mount or
    // focus), and its self-healing refresh only fires when LEAVING a conversation surface. Without
    // these invalidations the thread just created is not findable until a full page reload.
    void queryClient.invalidateQueries({ queryKey: ['conversations'] });
  }, [queryClient]);

  const refreshThread = React.useCallback((id: string, phase: 'request' | 'result') => {
    // See `pendingRequest`: refreshing after the request would replace the in-flight card with a
    // turn that reads as abandoned.
    if (phase !== 'result') return;
    void queryClient.invalidateQueries({ queryKey: ['studio-messages', id] });
  }, [queryClient]);

  const { isRunning, error, run, clearError } = useStudioTurn({
    conversationId: activeConversationId,
    onConversationCreated: handleConversationCreated,
    onTurnRecorded: refreshThread,
  });

  // The submission awaiting its analytics outcome. A turn that never reached the thread comes back
  // from `run` as null, and only the hook's `error` says why, so those outcomes are reported from
  // the error below; a recorded or lost turn is reported in handleSubmit, which clears this first.
  const awaitingOutcomeRef = React.useRef<{
    model: Pick<GenerationModel, 'model' | 'kind' | 'provider'>;
    credentialSource: 'platform' | 'user';
  } | null>(null);
  React.useEffect(() => {
    const submitted = awaitingOutcomeRef.current;
    if (!error || !submitted) return;
    awaitingOutcomeRef.current = null;
    const byCode: Record<StudioTurnErrorCode, 'refused' | 'failed' | 'lost'> = {
      refused: 'refused',
      connection_lost: 'lost',
      conversation_create_failed: 'failed',
      unexpected: 'failed',
    };
    trackStudioGenerationSubmitted(submitted.model, submitted.credentialSource, byCode[error.code], 'studio');
  }, [error]);

  const handleSubmit = React.useCallback(async (input: {
    prompt: string;
    params: Record<string, unknown>;
  }): Promise<boolean> => {
    if (!selectedModel) return false;
    // A chat conversation is being redirected away; writing a generation turn into it would be the
    // very thing the guard above exists to stop.
    if (cannotSubmit) return false;
    // Drawn immediately, so the reader sees what they asked for while it runs.
    //
    // Cleared in `finally` only if it is still OURS. A second submit in the same tick is refused
    // inside `run` (the guard is a ref, so it fires before any re-render), and its `finally` would
    // otherwise delete the card drawn for the FIRST turn - the one already submitted and running -
    // leaving an unchanged thread and a primed composer in front of a purchase in flight.
    // Compared by REFERENCE below, which is why it is built once and kept.
    const mine: StudioRequestEnvelope = {
      type: STUDIO_MESSAGE_TYPE,
      role: 'request',
      prompt: input.prompt,
      model: selectedModel.model,
      kind: selectedModel.kind,
      provider: selectedModel.provider,
      ...(Object.keys(input.params).length > 0 ? { params: input.params } : {}),
      credentialSource,
    };
    setPendingRequest(mine);
    // A second submit while one is in flight is refused by `run` without an answer; it must not
    // take over the slot of the turn that is actually running.
    const submitted = { model: selectedModel, credentialSource };
    if (!awaitingOutcomeRef.current) awaitingOutcomeRef.current = submitted;
    try {
      const outcome = await run({
        prompt: input.prompt,
        model: selectedModel.model,
        kind: selectedModel.kind,
        provider: selectedModel.provider,
        params: input.params,
        credentialSource,
        // Only read beside 'user': on the platform key the id is not sent at all.
        credentialId: credentialSource === 'user' ? credentialId : null,
        fallbackTitle: t('untitled'),
      });
      if (!outcome) {
        // Nothing was sent and nothing was charged, so the words and the files are still worth
        // keeping: this is the one case where the composer holds its draft. Its analytics outcome,
        // if the turn was attempted at all, is reported from the hook's error (see above).
        return false;
      }
      // Reported once: a lost turn also sets the hook's error, and whichever of the two paths
      // runs first takes the slot.
      if (awaitingOutcomeRef.current === submitted) {
        awaitingOutcomeRef.current = null;
        trackStudioGenerationSubmitted(
          submitted.model,
          submitted.credentialSource,
          outcome.status === 'lost' ? 'lost' : outcomeOfGenerationResult(outcome.result),
          'studio',
        );
      }

      // The URL arrives once the turn has landed, not when the conversation is created.
      //
      // Routing at creation looked better (a shareable URL during a long generation) and was wrong:
      // the new route is a fresh mount that knows nothing about the turn in flight, so it read the
      // request with no answer and warned that the generation may have been charged and lost, while
      // it was running normally.
      if (outcome.conversationId !== activeConversationId) {
        router.push(`/app/studio/${outcome.conversationId}`);
      }

      if (outcome.status === 'lost') {
        // The submission LEFT. Show the request that was written, so the reader sees a turn with no
        // answer rather than an unchanged thread - and clear the composer, because a primed one
        // holding the identical prompt is an invitation to pay for the same generation twice.
        refreshThread(outcome.conversationId, 'result');
        return true;
      }

      // A RECORDED REFUSAL keeps the draft; a CHARGED FAILURE does not.
      //
      // `success: false` covers two opposite things, and treating them alike is wrong in one
      // direction or the other. A refusal never reached the provider (out of credits, no published
      // price, a parameter the model rejected) and cost nothing, so erasing the words and the
      // uploaded files would make a free refusal more expensive to recover from than a paid
      // success. But a generation that ran upstream and could not be stored WAS billed - billing
      // commits before the asset is fetched - and re-priming the composer with the identical prompt
      // in front of someone who just paid is the invitation to pay twice that this whole outcome
      // type exists to prevent.
      //
      // The two are told apart by `chargedAnyway`, decided in buildStudioResult from whether the
      // failing answer carried any data at all. Anything not known to be free clears, because the
      // costly mistake here is the one that offers a second purchase.
      return outcome.result?.success !== false || generationWasCharged(outcome.result);
    } finally {
      setPendingRequest((current) => (current === mine ? null : current));
    }
  }, [run, selectedModel, t, credentialSource, credentialId, activeConversationId, router,
      cannotSubmit, refreshThread]);

  const handleReuse = React.useCallback((request: StudioRequestEnvelope) => {
    const match = models.find((model) => model.model === request.model);
    if (match) handleSelectModel(match);
    // Repeating a turn repeats WHO PAID for it. Without this, a turn run on the reader's own key
    // comes back on the platform's, which is a second change they did not ask for.
    if (request.credentialSource) setCredentialSource(request.credentialSource);
    setReused(request);
  }, [models, handleSelectModel]);

  // Apply the handed-over recipe once the catalogue can answer which model it names. A recipe whose
  // model has since been retired still loads its words and settings, on whichever model is
  // selected: the alternative is to drop the replay entirely, which loses more than it protects.
  React.useEffect(() => {
    if (!pendingRecipe || models.length === 0) return;
    handleReuse(pendingRecipe);
    setPendingRecipe(null);
  }, [pendingRecipe, models, handleReuse]);

  const handleOpenInFiles = React.useCallback((fileId: string) => {
    router.push(`/app/files?fileId=${encodeURIComponent(fileId)}`);
  }, [router]);

  /**
   * Show a past generation, without leaving the studio.
   *
   * <p>Pressing one of your own results used to navigate to the Files page: the thread, the
   * composer and whatever was half-typed in it were replaced by a file browser, and the way
   * back was the browser's back button. The asset is the thing being looked at, not a reason
   * to change screens, so it opens in the right-hand panel beside the studio instead.
   *
   * <p>Through the canonical {@code openFilesPanel}, so this is the same tab, the same detail
   * view and the same back-chevron-to-the-list as every other file surface in the app - and
   * the metadata travels with it, because a detail view given a bare id draws the placeholder
   * until it has fetched the type back.
   *
   * <p>The navigation stays as the fallback for a host with no panel: an asset that cannot be
   * shown beside the studio must still be reachable.
   */
  const handleOpenAsset = React.useCallback((entry: GenerationHistoryEntry) => {
    if (!sidePanel) {
      handleOpenInFiles(entry.id);
      return;
    }
    openFilesPanel(sidePanel, {
      id: entry.id,
      path: entry.s3Key ?? undefined,
      name: entry.fileName ?? undefined,
      mimeType: entry.mimeType ?? undefined,
      size: entry.sizeBytes ?? undefined,
      createdAt: entry.createdAt,
    });
  }, [sidePanel, handleOpenInFiles]);

  /**
   * Load a past generation back into the composer.
   *
   * <p>Through the SAME mapping the studio already uses for a recipe handed over from another
   * screen, so a card in the history and a file arriving from Files fill the composer identically.
   * A recipe with no model id is dropped rather than half-applied: the model is what a replay RUNS,
   * and filling the words in over whatever happens to be selected would submit them to a different
   * model than the one that made the asset the reader clicked.
   */
  const handleReuseRecipe = React.useCallback((provenance: Parameters<typeof recipeToRequest>[0]) => {
    const request = recipeToRequest(provenance);
    if (!request) return;
    handleReuse(request);
    // Bring the composer back into view.
    //
    // On the desktop empty layout the composer is anchored high and the history sits below the
    // applications row, so pressing Modify at the bottom of the page fills a box the reader cannot
    // see: the press looks like it did nothing, and the obvious next move is to press it again. The
    // narrow layout pins the composer to the bottom and the thread layout keeps it under the
    // thread, so the anchor is only rendered where the problem exists and this is a no-op elsewhere.
    //
    // Guarded on the method, not just on the node: jsdom does not implement scrollIntoView, and an
    // unguarded call takes down every test that renders this surface.
    const anchor = composerAnchorRef.current;
    if (anchor && typeof anchor.scrollIntoView === 'function') {
      anchor.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }, [handleReuse]);

  /**
   * What this workspace has already generated, under the applications row.
   *
   * <p>Shown on an empty studio only, exactly where the applications row is and for the same
   * reason: once a thread exists the reader is working, and their own results are the page. It
   * hides itself entirely when nothing has been generated yet, so a fresh install shows no empty
   * shelf.
   */
  const generationHistory = (
    <GenerationHistoryList
      className="w-full max-w-6xl mx-auto px-6 pb-6"
      heading={t('history.title')}
      hideWhenEmpty
      onOpen={handleOpenAsset}
      onReuse={(entry) => handleReuseRecipe(entry.provenance)}
    />
  );

  const hasThread = turns.length > 0 || !!pendingRequest;

  // A brand-new studio is never "loading a thread": there is no conversation to load one from. The
  // loading half only applies once there IS one, or the empty layout is skipped for every reader
  // opening the studio - which is exactly what left the composer pinned to the bottom of an empty
  // page. (`isLoading` on a disabled query is not a state to reason from; the conversation id is.)
  const hasThreadLayout = hasThread || (!!activeConversationId && messagesLoading);

  // The composer, built once and placed differently by the two layouts below. Rebuilding it per
  // branch would give each its own component identity, so switching between them on the first turn
  // would remount it and drop whatever is being typed.
  const composer = (
    <StudioComposer
      models={models}
      selectedModel={selectedModel}
      onSelectModel={handleUserSelectModel}
      onSubmit={handleSubmit}
      isRunning={isRunning}
      credentialSource={credentialSource}
      onCredentialSourceChange={setCredentialSource}
      credentialId={credentialId}
      onCredentialIdChange={setCredentialId}
      reuse={reused}
      onReuseConsumed={() => setReused(null)}
      autoFocus={!hasThread}
      // Offered only on a studio that has nothing open yet. Once a thread exists its kind is fixed
      // - the server refuses a change - so a switch here would offer something that cannot happen
      // to THIS conversation; leaving means starting a new one.
      modeSwitch={!activeConversationId ? <HomeModeSwitch mode="studio" compact /> : undefined}
      // Every reason handleSubmit would refuse, not just one of them. The submit guard answers
      // `false` silently, so any refusal it can make that the button does not show is a press that
      // does nothing and says nothing - which is what a reader retries. `conversationPending` and
      // `isChatConversation` were both reachable that way, on the second turn of a new studio.
      disabled={cannotSubmit}
      notice={
        conversationUnreadable ? (
          <StudioNotice code="conversation_unreadable" />
        ) : error ? (
          <StudioNotice code={error.code} detail={error.detail} onDismiss={clearError} />
        ) : availability === 'empty' && !modelsLoading ? (
          <StudioNotice code="catalogue_empty" />
        ) : undefined
      }
    />
  );

  // An empty studio and a studio with work in it are two different screens, not one screen with
  // less in it. Pinning the composer to the bottom of an empty page leaves it stranded under a
  // screenful of nothing; the chat home already solved this by anchoring the whole block high, and
  // the studio reads as its sibling, so it anchors the same way.
  // `py-4` on the scroller below matches the chat's own (`... overflow-y-auto py-4 space-y-4 ...`).
  // Measured on the running app: without it the studio's whole column started 16px higher than the
  // chat's, so the composer moved the moment the mode switch was used. The offset was ABOVE the
  // anchor, which is why matching the anchor - and then the composer's own wrapper - still left it.
  if (!hasThreadLayout) {
    /* Narrow screens put the title at the TOP and pin the composer to the BOTTOM, which is what
     * the chat home has always done and what the studio was missing: on a phone its composer
     * floated mid-screen while the chat's sat under the reader's thumb - one page, two behaviours,
     * with the mode switch between them.
     *
     * The chat renders its composer TWICE and lets CSS hide one. This one is placed ONCE by a
     * measured width, because the two are not equally cheap to duplicate: MessageComposer's text
     * belongs to its parent, while StudioComposer owns the prompt, every parameter and every
     * uploaded file. Two of those are two independent drafts, and the invisible one is the one
     * holding the reader's work when the viewport crosses the breakpoint.
     *
     * 640px is Tailwind's `sm`, the width the chat splits on, so both change shape together. */
    if (isNarrowViewport) {
      return (
        <StudioBackdrop>
          <div className="flex min-h-0 flex-1 flex-col">
            <div className="min-h-0 flex-1 overflow-y-auto py-4">
              {/* The chat's mobile welcome, same `pt-8` and `mb-4`. */}
              <div className="pt-8 shrink-0 px-2">
                <div className="text-center max-w-md mx-auto mb-4">
                  <StudioDynamicTitle />
                </div>
              </div>
              <div className="pb-6">
                <StudioApps />
              </div>
              {generationHistory}
            </div>
            {/* OUTSIDE the scroller, which is what pins it to the bottom. */}
            {composer}
          </div>
        </StudioBackdrop>
      );
    }

    return (
      <StudioBackdrop>
        <div className="flex min-h-0 flex-1 flex-col overflow-y-auto py-4">
          {/* The SAME anchor as the chat home (pt-[22vh]), so flipping the mode switch does not move
              the composer under the reader's cursor. The two surfaces are one page with two
              composers; a different offset would make the switch feel like a page load. */}
          {/* The chat home's wrapper, copied structure for structure - the same 22vh anchor, the same
              max-w-4xl/px-2 outer, the same max-w-3xl/p-4 around the composer itself. Matching only
              the anchor was not enough: the chat nests the composer one padding deeper, so the studio
              drew it a row higher and flipping the switch moved the box under the reader's cursor. */}
          <div className="pt-[22vh] shrink-0 mx-auto max-w-4xl px-2 w-full">
            <div className="text-center max-w-md mx-auto mb-8">
              <StudioDynamicTitle />
            </div>
            <div className="w-full relative">
              <div className="relative flex items-end justify-center">
                <div className="max-w-3xl w-full">
                  <div className="p-4" ref={composerAnchorRef}>{composer}</div>
                </div>
              </div>
            </div>
          </div>
          {/* The studio's own applications, under the composer on a studio with nothing open yet.
              Once a thread exists the reader is working, and a marketplace row under their results is
              in the way; the sidebar and the marketplace are how they get back to the apps. */}
          <div className="pb-6">
            <StudioApps />
          </div>
          {/* The reader's own past work, in the applications row's own gutter so the two read as one
              column rather than two panels of different widths. The spacing travels WITH the list
              rather than wrapping it: the list renders nothing at all on an install that has
              generated nothing, and a wrapper would leave its padding behind as a gap under the
              applications row. */}
          {generationHistory}
        </div>
      </StudioBackdrop>
    );
  }

  return (
    <StudioBackdrop>
      <div className="flex min-h-0 flex-1 flex-col">
        <div className="min-h-0 flex-1 overflow-y-auto">
          <div className="mx-auto w-full max-w-3xl px-4 py-6">
            {messagesLoading && !hasThread && (
              <div className="flex items-center justify-center gap-2 py-12 text-sm text-theme-muted">
                <Loader2 className="h-4 w-4 animate-spin" />
                {t('thread.loading')}
              </div>
            )}

            <div className="space-y-4">
              {turns.map((turn) => (
                <StudioTurnCard
                  key={turn.id}
                  request={turn.request}
                  result={turn.result}
                  iconSlug={iconByModel.get(turn.request.model) ?? null}
                  onReuse={handleReuse}
                  onOpenInFiles={handleOpenInFiles}
                />
              ))}
              {/* The turn in flight, drawn from what was submitted rather than read back. It is
                  replaced by the real pair when the answer lands and the thread refreshes. */}
              {pendingRequest && (
                <StudioTurnCard
                  key="studio-turn-pending"
                  request={pendingRequest}
                  isRunning
                  iconSlug={iconByModel.get(pendingRequest.model) ?? null}
                />
              )}
            </div>
          </div>
        </div>

        <div className="mx-auto w-full max-w-3xl flex-shrink-0 px-4 pb-4">
          {composer}
        </div>

      </div>
    </StudioBackdrop>
  );
}

/**
 * A refusal that never reached the thread.
 *
 * <p>Each code is a different fact, and the one that matters is `connection_lost`: it is NOT a
 * failure to retry. The submission left, the server may have finished it and committed the charge,
 * so the sentence sends the reader to look rather than to send it again.
 */
function StudioNotice({
  code, detail, onDismiss,
}: {
  code: 'conversation_create_failed' | 'connection_lost' | 'refused' | 'unexpected'
    | 'catalogue_empty' | 'conversation_unreadable';
  detail?: string;
  onDismiss?: () => void;
}) {
  const t = useTranslations('studio');
  // Only the two that describe a state rather than a fault read as warnings. A refusal is an error:
  // something the reader asked for did not happen.
  const isWarning = code === 'connection_lost' || code === 'catalogue_empty';
  return (
    <div
      // A refusal interrupts what the reader was doing, so it is announced rather than left for
      // them to notice. Only the catalogue-empty note is passive: nothing was attempted.
      role={code === 'catalogue_empty' ? 'status' : 'alert'}
      className={`mb-2 flex items-start gap-2 rounded-xl px-3 py-2 text-sm ${
        isWarning
          ? 'bg-[var(--status-warning)]/10 text-[var(--status-warning)]'
          : 'bg-[var(--status-error)]/10 text-[var(--status-error)]'
      }`}
    >
      <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0" />
      <div className="min-w-0 flex-1">
        <p className="break-words">{t(`notices.${code}`)}</p>
        {/* The underlying message beside the explanation, never instead of it. */}
        {detail && <p className="mt-0.5 break-words text-xs opacity-70">{detail}</p>}
      </div>
      {onDismiss && (
        <button type="button" onClick={onDismiss} className="text-xs underline opacity-80">
          {t('notices.dismiss')}
        </button>
      )}
    </div>
  );
}
