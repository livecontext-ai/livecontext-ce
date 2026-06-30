'use client';

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { Paperclip, ArrowUp, Square, X, FileIcon, ImageIcon, Loader2, Mic, MicOff, Settings2 } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { AttachmentHandler, type AttachmentView } from './AttachmentHandler';
import { ImageLightbox } from './ImageLightbox';
import { Button } from '../ui/button';
import { useTranslations } from 'next-intl';
import { attachmentApi, type PendingAttachment, type AttachmentRef } from '@/lib/api/attachmentApi';
import { useDefaultSkills } from '@/hooks/useDefaultSkills';
import { useMobileDetection } from '@/hooks/useMobileDetection';
import { orchestratorApi } from '@/lib/api/orchestrator';
import { QueuedMessageBar } from './QueuedMessageBar';
import { MAX_QUEUE_SIZE, type QueuedMessage } from '@/lib/stores/message-queue-store';
import { readDraft, writeDraft, clearDraft } from '@/lib/chat/draftStorage';

export interface AnalyzeBadge {
  id: string;
  type: 'data' | 'workflow';
  label: string;
}

export interface MessageComposerProps {
  inputValue: string;
  onInputChange: (value: string) => void;
  onSendMessage: (content?: string, attachments?: AttachmentRef[], defaultSkillIds?: string[]) => void;
  onKeyPress?: (e: React.KeyboardEvent) => void;
  isChatLoading?: boolean;
  isStreaming?: boolean;
  isStreamStarting?: boolean;
  onStopStream?: () => void;
  showAttachmentMenu: boolean;
  onShowAttachmentMenu: (show: boolean) => void;
  analyzeBadges?: AnalyzeBadge[];
  onRemoveAnalyzeBadge?: (id: string) => void;
  isStreamConnected?: boolean;
  isStreamReconnecting?: boolean;
  streamReconnectAttempts?: number;
  streamStatus?: { hasActiveStream: boolean; streamId?: string } | null;
  onTestReconnection?: () => void;
  showScrollToBottom?: boolean;
  onScrollToBottom?: () => void;
  compact?: boolean;
  fixedBottom?: boolean;
  sidebarWidth?: number;
  fullWidth?: boolean;
  /** When true, disables the textarea and send button */
  disabled?: boolean;
  /** Minimal/DM mode: render the same composer as a plain text+send box, hiding the
   *  AI-only controls (attachments, tools & skills, voice dictation) and skipping the
   *  per-conversation agent lookup. Defaults to false - chat behaviour is unchanged. */
  minimal?: boolean;
  conversationId?: string;
  queuedMessages?: QueuedMessage[];
  shouldEnqueue?: boolean;
  onEnqueueMessage?: (content: string, attachments: PendingAttachment[], defaultSkillIds?: string[]) => void;
  onRemoveQueuedMessage?: (messageId: string) => void;
  onEditQueuedMessage?: (messageId: string, content: string) => void;
  onSendNow?: (messageId: string) => void;
  onReorderQueue?: (fromIndex: number, toIndex: number) => void;
  /** Control rendered in the trailing button group, just left of the mic.
   *  Used to host the model selector (model conversations) or the agent avatar
   *  (agent conversations). DM / public / trigger callers omit it. */
  leadingControl?: React.ReactNode;
}

export function MessageComposer({
  inputValue,
  onInputChange,
  onSendMessage,
  isStreaming = false,
  isStreamStarting = false,
  onStopStream,
  fixedBottom = false,
  sidebarWidth = 256,
  fullWidth = false,
  disabled = false,
  minimal = false,
  conversationId,
  queuedMessages = [],
  shouldEnqueue = false,
  onEnqueueMessage,
  onRemoveQueuedMessage,
  onEditQueuedMessage,
  onSendNow,
  onReorderQueue,
  leadingControl,
}: MessageComposerProps) {
  const t = useTranslations();
  const isMobile = useMobileDetection();
  const {
    activeSkillIds,
    setActiveSkillIds,
    initializeDefaults,
    hasExplicitSkillSelection,
  } = useDefaultSkills(conversationId);
  const activeSkillIdsRef = useRef(activeSkillIds);
  const hasExplicitSkillSelectionRef = useRef(hasExplicitSkillSelection);
  const pendingConfigurationSavesRef = useRef<Set<Promise<unknown>>>(new Set());

  useEffect(() => {
    activeSkillIdsRef.current = activeSkillIds;
    hasExplicitSkillSelectionRef.current = hasExplicitSkillSelection;
  }, [activeSkillIds, hasExplicitSkillSelection]);

  const trackPendingConfigurationSave = useCallback((save: Promise<unknown>) => {
    const tracked = Promise.resolve(save).catch(() => undefined);
    pendingConfigurationSavesRef.current.add(tracked);
    void tracked.finally(() => {
      pendingConfigurationSavesRef.current.delete(tracked);
    });
  }, []);

  const waitForPendingConfigurationSaves = useCallback(async () => {
    while (pendingConfigurationSavesRef.current.size > 0) {
      await Promise.allSettled(Array.from(pendingConfigurationSavesRef.current));
    }
  }, []);

  // Resolve the agent linked to this conversation (if any). When present, the options
  // and skills tabs target the agent entity instead of per-conversation local prefs.
  const linkedAgentQuery = useQuery({
    queryKey: ['linked-agent', conversationId],
    queryFn: () => (conversationId ? orchestratorApi.getAgentByConversationId(conversationId) : Promise.resolve(null)),
    enabled: !minimal && !!conversationId,
    staleTime: 60_000,
  });
  const linkedAgent = linkedAgentQuery.data;
  const agentIdForConversation: string | null = linkedAgent?.id ?? null;
  const [agentScopedSkillIds, setAgentScopedSkillIds] = useState<Set<string> | null>(null);
  const pendingAgentLinkSkillIdsRef = useRef<Set<string> | null>(null);

  useEffect(() => {
    setAgentScopedSkillIds(null);
    pendingAgentLinkSkillIdsRef.current = null;
  }, [conversationId]);

  useEffect(() => {
    if (!agentIdForConversation || !pendingAgentLinkSkillIdsRef.current) return;

    const ids = new Set(pendingAgentLinkSkillIdsRef.current);
    pendingAgentLinkSkillIdsRef.current = null;
    activeSkillIdsRef.current = ids;
    setAgentScopedSkillIds(ids);

    const assignments = Array.from(ids).map(skillId => ({ skillId }));
    const save = orchestratorApi.setAgentSkills(agentIdForConversation, assignments);
    trackPendingConfigurationSave(save);
    void save.catch(err => {
      console.error('[MessageComposer] failed to persist pending linked-agent skills', err);
    });
  }, [agentIdForConversation, trackPendingConfigurationSave]);

  const handleSkillSelectionChange = useCallback((ids: Set<string>) => {
    activeSkillIdsRef.current = ids;

    if (agentIdForConversation) {
      setAgentScopedSkillIds(new Set(ids));
      return;
    }

    if (conversationId && linkedAgentQuery.isPending) {
      pendingAgentLinkSkillIdsRef.current = new Set(ids);
    }

    hasExplicitSkillSelectionRef.current = true;
    setActiveSkillIds(ids);
  }, [agentIdForConversation, conversationId, linkedAgentQuery.isPending, setActiveSkillIds]);

  const [localValue, setLocalValue] = useState(inputValue);
  const [openPanel, setOpenPanel] = useState<AttachmentView | null>(null);
  const [pendingAttachments, setPendingAttachments] = useState<PendingAttachment[]>([]);
  const [isListening, setIsListening] = useState(false);
  // Pre-send image preview shown enlarged in the lightbox (null = closed). Uses the local
  // object URL, so it needs no auth and isn't downloadable (the user already has the file).
  const [lightboxPreview, setLightboxPreview] = useState<{ src: string; fileName: string } | null>(null);
  const menuContainerRef = useRef<HTMLDivElement>(null);
  const panelAnchorRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const recognitionRef = useRef<SpeechRecognition | null>(null);
  // Tracks the confirmed text before the current interim result
  const confirmedTranscriptRef = useRef('');

  // Close panel on outside click
  useEffect(() => {
    if (!openPanel) return;
    const handleClickOutside = (e: MouseEvent) => {
      const target = e.target as Node;
      const targetElement = target instanceof Element ? target : target.parentElement;
      if (panelAnchorRef.current?.contains(target)) return;
      if (menuContainerRef.current?.contains(target)) return;
      const portalMenu = document.querySelector('[data-attachment-menu]');
      if (portalMenu?.contains(target)) return;
      if (targetElement?.closest('[data-radix-select-content], [data-radix-popper-content-wrapper]')) return;
      setOpenPanel(null);
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [openPanel]);

  // ── Draft persistence (sessionStorage, debounced, scoped per conversation) ──
  // Restores what the user was typing if they navigate away and come back within the
  // same tab session. Keyed per-conversation so /app/chat (new), /app/c/<idA> and
  // /app/c/<idB> never bleed into each other. Cleared on message send.
  // Storage + the freshness TTL that prevents a long-orphaned draft from being
  // restored and silently re-sent live in `@/lib/chat/draftStorage` (see its
  // header - the 2026-05-29 duplicate-conversation incident).
  const draftRestoredRef = useRef(false);
  const draftConversationIdRef = useRef<string | null | undefined>(undefined);
  const localValueRef = useRef(localValue);

  useEffect(() => {
    localValueRef.current = localValue;
  }, [localValue]);

  // Restore once on mount/conversation-change. On a conversation switch, the
  // parent input can still hold the previous conversation's text for one render;
  // ignore that stale value and hydrate only the target conversation slot.
  useEffect(() => {
    const previousConversationId = draftConversationIdRef.current;
    const isInitialRestore = previousConversationId === undefined;
    const nextConversationId = conversationId ?? null;

    if (!isInitialRestore && previousConversationId !== nextConversationId) {
      writeDraft(previousConversationId, localValueRef.current);
    }

    draftConversationIdRef.current = nextConversationId;
    draftRestoredRef.current = false;

    if (isInitialRestore && inputValue) {
      setLocalValue(inputValue);
      draftRestoredRef.current = true;
      return;
    }

    const stored = readDraft(conversationId); // null if missing / stale / legacy
    const nextValue = stored ?? '';
    setLocalValue(nextValue);
    // Push to parent so submit/keyboard handlers see the restored text immediately,
    // and so stale parent text from another conversation is cleared.
    if (inputValue !== nextValue) {
      onInputChange(nextValue);
    }
    draftRestoredRef.current = true;
    // Intentional: only re-run when the conversation changes. `inputValue` is read
    // as a one-shot snapshot to decide whether to restore/clear; making it a dep would
    // re-run the restore on every keystroke and toggle `draftRestoredRef` mid-typing,
    // which would race with the save effect and silently drop persistence windows.
    // `onInputChange` is stable from the parent (state setter); excluding it avoids
    // an unnecessary re-run if the parent ever re-binds the callback.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conversationId]);

  // Sync from parent
  useEffect(() => {
    if (inputValue !== localValue) {
      setLocalValue(inputValue);
    }
  }, [inputValue]);

  // Sync to parent (debounced 50ms - keeps the original responsive behavior)
  useEffect(() => {
    const timer = setTimeout(() => {
      if (localValue !== inputValue) {
        onInputChange(localValue);
      }
    }, 50);
    return () => clearTimeout(timer);
  }, [localValue, inputValue, onInputChange]);

  // Persist draft to sessionStorage (debounced 250ms - coarser than parent sync to
  // keep storage writes off the critical typing path). Empty value clears the slot.
  useEffect(() => {
    if (!draftRestoredRef.current) return;
    const timer = setTimeout(() => {
      writeDraft(conversationId, localValue);
    }, 250);
    return () => clearTimeout(timer);
  }, [localValue, conversationId]);

  // Auto-resize textarea
  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;

    // Reset to min height then expand to content
    textarea.style.height = 'auto';
    const scrollHeight = textarea.scrollHeight;
    // Min 2 lines (~52px), max 200px
    const newHeight = Math.max(52, Math.min(scrollHeight, 200));
    textarea.style.height = `${newHeight}px`;
  }, [localValue]);

  // Cleanup preview URLs on unmount
  useEffect(() => {
    return () => {
      pendingAttachments.forEach(a => {
        if (a.preview) {
          attachmentApi.revokePreviewUrl(a.preview);
        }
      });
    };
  }, []);

  // Speech recognition support
  const speechSupported = typeof window !== 'undefined' &&
    ('SpeechRecognition' in window || 'webkitSpeechRecognition' in window);

  const toggleDictation = useCallback(() => {
    if (!speechSupported) return;

    if (isListening && recognitionRef.current) {
      recognitionRef.current.stop();
      return;
    }

    const SpeechRecognitionApi = window.SpeechRecognition || window.webkitSpeechRecognition;
    const recognition = new SpeechRecognitionApi();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = getClientLocale();

    // Save current text as the base before dictation starts
    confirmedTranscriptRef.current = localValue;

    recognition.onresult = (event: SpeechRecognitionEvent) => {
      // event.results is cumulative - rebuild full transcript each time
      let transcript = '';
      for (let i = 0; i < event.results.length; i++) {
        transcript += event.results[i][0].transcript;
      }

      const base = confirmedTranscriptRef.current;
      const separator = base && !base.endsWith(' ') && transcript ? ' ' : '';
      setLocalValue(base + separator + transcript);
    };

    recognition.onend = () => {
      setIsListening(false);
      recognitionRef.current = null;
    };

    recognition.onerror = (event: SpeechRecognitionErrorEvent) => {
      console.error('Speech recognition error:', event.error);
      setIsListening(false);
      recognitionRef.current = null;
    };

    recognitionRef.current = recognition;
    recognition.start();
    setIsListening(true);
  }, [speechSupported, isListening, localValue]);

  // Cleanup recognition on unmount
  useEffect(() => {
    return () => {
      if (recognitionRef.current) {
        recognitionRef.current.stop();
      }
    };
  }, []);

  // Handle file selection (no upload yet - just stage the file)
  const handleFileSelect = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || []);
    if (files.length === 0) return;

    // Process each file - just stage them, don't upload yet
    for (const file of files) {
      // Check if file type is allowed
      if (!attachmentApi.isAllowedType(file.type)) {
        console.warn(`File type not supported: ${file.type}`);
        continue;
      }

      // Create pending attachment with 'pending' status (not uploaded yet)
      const attachment: PendingAttachment = {
        id: crypto.randomUUID(),
        file,
        uploadStatus: 'pending',
        type: attachmentApi.determineType(file.type),
        mimeType: file.type,
        sizeBytes: file.size,
        preview: attachmentApi.createPreviewUrl(file)
      };

      setPendingAttachments(prev => [...prev, attachment]);
    }

    // Reset input
    e.target.value = '';
  }, []);

  // Remove attachment
  const handleRemoveAttachment = useCallback((id: string) => {
    setPendingAttachments(prev => {
      const attachment = prev.find(a => a.id === id);
      if (attachment?.preview) {
        attachmentApi.revokePreviewUrl(attachment.preview);
      }
      return prev.filter(a => a.id !== id);
    });
  }, []);

  const containerClass = fixedBottom
    ? "fixed bottom-0 left-0 right-0 z-30 px-2 sm:px-3 pb-[calc(env(safe-area-inset-bottom,0px)+0.75rem)] sm:pb-4"
    : "px-2 sm:px-3 pb-[calc(env(safe-area-inset-bottom,0px)+0.75rem)] sm:pb-4";

  // On mobile, sidebar is an overlay (absolute/fixed), so no left margin needed
  const inputContainerStyle = fixedBottom && !isMobile
    ? { marginLeft: `${sidebarWidth}px` }
    : {};

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleSend = async () => {
    const content = localValue.trim();
    // Allow sending if there's content OR attachments (pending or already uploaded)
    const hasContent = !!content;
    const hasLocalAttachments = pendingAttachments.length > 0;

    if (!hasContent && !hasLocalAttachments) {
      return;
    }

    await waitForPendingConfigurationSaves();

    if (shouldEnqueue && onEnqueueMessage) {
      const activeSkillArray = Array.from(activeSkillIdsRef.current).sort();
      onEnqueueMessage(
        content,
        [...pendingAttachments],
        !agentIdForConversation && hasExplicitSkillSelectionRef.current ? activeSkillArray : undefined
      );
      setLocalValue('');
      setPendingAttachments([]);
      clearDraft(conversationId);
      return;
    }

    // Track upload results locally (React state may not be synchronously available after await)
    const uploadResults = new Map<string, string>(); // attachment id → storageKey
    const pendingToUpload = pendingAttachments.filter(a => a.uploadStatus === 'pending');

    if (pendingToUpload.length > 0) {
      // Mark all pending as uploading
      setPendingAttachments(prev => prev.map(a =>
        a.uploadStatus === 'pending' ? { ...a, uploadStatus: 'uploading' as const } : a
      ));

      // Upload all pending files via conversation-service (returns UUID storageId)
      for (const attachment of pendingToUpload) {
        try {
          const response = await attachmentApi.uploadAttachment(attachment.file);
          uploadResults.set(attachment.id, response.storageId);
          setPendingAttachments(prev => prev.map(a =>
            a.id === attachment.id
              ? { ...a, storageId: response.storageId, uploadStatus: 'success' as const }
              : a
          ));
        } catch (error) {
          console.error('Upload failed:', error);
          setPendingAttachments(prev => prev.map(a =>
            a.id === attachment.id
              ? { ...a, uploadStatus: 'error' as const, errorMessage: (error as Error).message }
              : a
          ));
          // Don't send the message if any upload fails
          return;
        }
      }
    }

    // Build final attachment list from closure snapshot + local upload results
    const finalAttachments = pendingAttachments.map(a => {
      const uploadedKey = uploadResults.get(a.id);
      if (uploadedKey) {
        return { ...a, storageId: uploadedKey, uploadStatus: 'success' as const };
      }
      return a;
    });

    const successfulAttachments = finalAttachments.filter(a => a.uploadStatus === 'success');
    if (hasContent || successfulAttachments.length > 0) {
      const attachmentRefs = attachmentApi.toAttachmentRefs(finalAttachments);
      const activeSkillArray = Array.from(activeSkillIdsRef.current).sort();

      onInputChange(localValue);
      onSendMessage(
        content || undefined,
        attachmentRefs.length > 0 ? attachmentRefs : undefined,
        !agentIdForConversation && hasExplicitSkillSelectionRef.current ? activeSkillArray : undefined
      );
    }

    // Clear attachments and revoke preview URLs
    setPendingAttachments(prev => {
      prev.forEach(a => {
        if (a.preview) {
          attachmentApi.revokePreviewUrl(a.preview);
        }
      });
      return [];
    });

    // Clear the input
    setLocalValue('');
    // Clear persisted draft synchronously. Relying on the 250ms persist debounce
    // is unsafe here: in the "new conversation" flow, sending creates a
    // conversation and navigates from /app/chat → /app/c/<id>, which changes the
    // draft key and cancels the pending clear via effect cleanup. The old `:new`
    // slot would otherwise be re-restored on the next /app/chat visit.
    clearDraft(conversationId);
  };

  // Check if any uploads are in progress
  const isUploading = pendingAttachments.some(a => a.uploadStatus === 'uploading');
  const hasAttachments = pendingAttachments.length > 0;
  // Count attachments ready to send (pending or success, not error)
  const readyAttachments = pendingAttachments.filter(a => a.uploadStatus === 'pending' || a.uploadStatus === 'success');
  const showStopButton = isStreaming || isStreamStarting;

  return (
    <div className={containerClass} style={inputContainerStyle}>
      <div className={fullWidth ? "w-full" : "mx-auto max-w-4xl"}>
        <div className="relative">
          {/* Composer container */}
          <div
            className="bg-theme-primary overflow-hidden shadow-sm border border-theme"
            style={{ borderRadius: '28px' }}
          >
            {/* Grid layout: attachments preview, textarea, buttons */}
            <div className="grid grid-cols-[auto_1fr_auto] grid-rows-[auto_1fr_auto] p-2.5 gap-y-1">

              {/* Queued messages */}
              {queuedMessages.length > 0 && onRemoveQueuedMessage && onEditQueuedMessage && onSendNow && onReorderQueue && (
                <div className="col-span-3">
                  <QueuedMessageBar
                    messages={queuedMessages}
                    onRemove={onRemoveQueuedMessage}
                    onEditContent={onEditQueuedMessage}
                    onSendNow={onSendNow}
                    onReorder={onReorderQueue}
                  />
                </div>
              )}

              {/* Attachment previews - between queue and textarea */}
              {hasAttachments && (
                <div className="col-span-3 px-3 pt-1 pb-1 flex flex-wrap gap-2">
                  {pendingAttachments.map(attachment => (
                    <div
                      key={attachment.id}
                      className={`relative flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm ${
                        attachment.uploadStatus === 'error'
                          ? 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-300'
                          : 'bg-theme-secondary text-theme-primary'
                      }`}
                    >
                      {attachment.preview ? (
                        <button
                          type="button"
                          onClick={() => setLightboxPreview({ src: attachment.preview!, fileName: attachment.file.name })}
                          aria-label={t('chat.imageViewer.enlarge')}
                          title={t('chat.imageViewer.enlarge')}
                          className="cursor-zoom-in shrink-0"
                        >
                          <img
                            src={attachment.preview}
                            alt={attachment.file.name}
                            className="w-6 h-6 rounded object-cover"
                          />
                        </button>
                      ) : attachment.type === 'IMAGE' ? (
                        <ImageIcon className="w-4 h-4" />
                      ) : (
                        <FileIcon className="w-4 h-4" />
                      )}
                      <span className="max-w-[120px] truncate">
                        {attachment.file.name}
                      </span>
                      {attachment.uploadStatus === 'uploading' && (
                        <Loader2 className="w-4 h-4 animate-spin" />
                      )}
                      <button
                        onClick={() => handleRemoveAttachment(attachment.id)}
                        className="ml-1 p-0.5 hover:bg-theme-primary/20 rounded"
                        title={t('common.remove')}
                      >
                        <X className="w-3 h-3" />
                      </button>
                    </div>
                  ))}
                </div>
              )}

              {/* Textarea area */}
              <div className="col-span-3 px-3 pt-2 pb-1">
                <textarea
                  ref={textareaRef}
                  value={localValue}
                  onChange={(e) => setLocalValue(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder={t('chat.placeholder')}
                  disabled={disabled}
                  className="w-full bg-transparent text-theme-primary placeholder-theme-muted focus:outline-none resize-none text-base leading-6 overflow-y-auto disabled:opacity-50 disabled:cursor-not-allowed"
                  style={{ minHeight: '52px', maxHeight: '200px' }}
                  rows={2}
                />
              </div>

              {/* Bottom row: leading (file + tools + skills) | footer (empty) | trailing (send) */}

              {/* Leading - 3 buttons side by side */}
              <div ref={panelAnchorRef} className="flex items-center gap-0.5">
                {/* File attachment - also available in minimal (DM) mode: DMs carry
                    real attachments; only the AI-only Tools & Skills stay chat-only. */}
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => fileInputRef.current?.click()}
                  className="h-9 w-9"
                  title={t('chat.attachFiles')}
                >
                  <Paperclip className="w-4 h-4" />
                </Button>
                <input
                  ref={fileInputRef}
                  type="file"
                  multiple
                  accept="image/jpeg,image/png,image/gif,image/webp,application/pdf,text/plain,text/markdown,text/csv,text/html,application/json,application/xml,text/javascript,text/css"
                  onChange={handleFileSelect}
                  className="hidden"
                />

                {!minimal && (
                  <>
                {/* Tools & Skills (unified) */}
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => setOpenPanel(openPanel ? null : 'tools')}
                  className={`h-9 w-9 ${openPanel ? 'bg-gray-100 dark:bg-gray-800' : ''}`}
                  title={t('credentials.toolsAndSkills')}
                >
                  <Settings2 className="w-4 h-4" />
                </Button>
                  </>
                )}

                {/* Notification bell relocated to AppHeader (next to the
                    right-side-panel toggle) so the chat-home page surfaces
                    notifications without the composer needing to render. */}
              </div>

              {/* Footer - center area */}
              <div className="flex items-center justify-start gap-2 overflow-x-auto px-2">
              </div>

              {/* Trailing - model selector / agent avatar + Mic + Send/Stop buttons.
                  leadingControl sits left of the mic (model conversations show the model
                  selector here, agent conversations the agent avatar). Dictation also
                  works in minimal (DM) mode - plain speech-to-text, not an AI tool. */}
              <div className="flex items-center gap-1">
                {leadingControl}
                {speechSupported && (
                  isListening ? (
                    <button
                      onClick={toggleDictation}
                      className="flex items-center gap-2 h-9 px-3 rounded-full bg-red-500/10 hover:bg-red-500/20 transition-colors"
                      title={t('chat.stopDictation')}
                    >
                      {/* Animated sound bars */}
                      <div className="flex items-center gap-0.5 h-4">
                        <span className="w-0.5 bg-red-500 rounded-full animate-[soundbar_0.8s_ease-in-out_infinite]" style={{ height: '40%' }} />
                        <span className="w-0.5 bg-red-500 rounded-full animate-[soundbar_0.8s_ease-in-out_0.2s_infinite]" style={{ height: '70%' }} />
                        <span className="w-0.5 bg-red-500 rounded-full animate-[soundbar_0.8s_ease-in-out_0.4s_infinite]" style={{ height: '100%' }} />
                        <span className="w-0.5 bg-red-500 rounded-full animate-[soundbar_0.8s_ease-in-out_0.1s_infinite]" style={{ height: '55%' }} />
                        <span className="w-0.5 bg-red-500 rounded-full animate-[soundbar_0.8s_ease-in-out_0.3s_infinite]" style={{ height: '80%' }} />
                      </div>
                      <Square className="w-3.5 h-3.5 text-red-500" />
                    </button>
                  ) : (
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={toggleDictation}
                      className="h-9 w-9"
                      title={t('chat.startDictation')}
                    >
                      <Mic className="w-5 h-5" />
                    </Button>
                  )
                )}
                {(() => {
                  const hasInput = !!localValue.trim() || readyAttachments.length > 0;
                  const showEnqueue = shouldEnqueue && hasInput;
                  const queueIsFull = queuedMessages.length >= MAX_QUEUE_SIZE;

                  if (showEnqueue) {
                    return (
                      <Button
                        variant="contrast"
                        size="icon"
                        onClick={handleSend}
                        disabled={disabled || isUploading || queueIsFull}
                        className="h-9 w-9 shadow-none hover:shadow-none"
                        title={queueIsFull ? t('chat.queue.full') : t('chat.queue.queued')}
                      >
                        <ArrowUp className="w-5 h-5" />
                      </Button>
                    );
                  }

                  return (
                    <Button
                      variant={showStopButton ? 'default' : 'contrast'}
                      size="icon"
                      onClick={showStopButton ? onStopStream : handleSend}
                      disabled={disabled || isUploading || (!showStopButton && !hasInput)}
                      className="h-9 w-9 shadow-none hover:shadow-none"
                      title={showStopButton ? t('chat.stop') : t('chat.send')}
                    >
                      {showStopButton ? <Square className="w-5 h-5" /> : <ArrowUp className="w-5 h-5" />}
                    </Button>
                  );
                })()}
              </div>
            </div>
          </div>

          {/* Tools/Skills panel */}
          {!minimal && (
            <div ref={menuContainerRef}>
              <AttachmentHandler
                isOpen={!!openPanel}
                initialView={openPanel || 'tools'}
                onClose={() => setOpenPanel(null)}
                onFileSelect={handleFileSelect}
                activeSkillIds={agentIdForConversation ? (agentScopedSkillIds ?? activeSkillIds) : activeSkillIds}
                onSkillSelectionChange={handleSkillSelectionChange}
                onInitializeDefaults={initializeDefaults}
                anchorRef={panelAnchorRef}
                conversationId={conversationId ?? null}
                agentId={agentIdForConversation}
                onPendingConfigurationSave={trackPendingConfigurationSave}
                isScopeResolutionPending={!!conversationId && linkedAgentQuery.isPending}
                hasLocalAgentSkillSelection={agentScopedSkillIds !== null}
              />
            </div>
          )}
        </div>
      </div>

      {/* Enlarge a pre-send image preview. Local object URL → no auth, not downloadable. */}
      <ImageLightbox
        open={lightboxPreview !== null}
        onClose={() => setLightboxPreview(null)}
        src={lightboxPreview?.src ?? ''}
        alt={lightboxPreview?.fileName ?? ''}
        fileName={lightboxPreview?.fileName}
        authenticated={false}
        downloadable={false}
      />
    </div>
  );
}
