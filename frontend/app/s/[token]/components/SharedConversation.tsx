'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import MarkdownRender from '@/components/MarkdownRender';
import PublicHeader from '@/components/sharing/PublicHeader';
import { useTranslations } from 'next-intl';
import { SidePanelProvider } from '@/contexts/SidePanelContext';
import { SidePanel } from '@/components/app/SidePanel';
import { ShareProviders } from '@/components/share/ShareProviders';
import { useAnchorScrollToBottom } from '@/lib/hooks/useAnchorScrollToBottom';
import { useIsomorphicLayoutEffect } from '@/lib/hooks/useIsomorphicLayoutEffect';

// ============================================================
// Types
// ============================================================

interface ConversationMeta {
  title?: string;
  shareMode: string;
  memoryEnabled: boolean;
}

interface Message {
  id: string;
  role: string;
  content: string | null;
  createdAt?: string;
}

interface MessagePage {
  items: Message[];
  hasMore: boolean;
}

interface SharedConversationProps {
  token: string;
  title?: string;
}

// ============================================================
// API helpers - raw fetch, no auth (public page)
// Gateway: /c/{token} → conversation-service /api/shared/c/{token}
// ============================================================

const API_BASE = '/c';
const PAGE_SIZE = 20;

async function fetchMeta(token: string): Promise<ConversationMeta> {
  const res = await fetch(`${API_BASE}/${token}`);
  if (res.status === 404) throw new NotFoundError();
  if (!res.ok) throw new Error('Failed to load conversation');
  const data = await res.json();
  return {
    title: data.title ?? undefined,
    shareMode: data.shareMode ?? 'read',
    memoryEnabled: data.memoryEnabled !== false,
  };
}

/**
 * Backend returns DESC server-side then re-sorts ASC for display, in a
 * { items, hasMore } envelope. Older pages prepend at the top of `messages`.
 */
async function fetchMessagesPage(token: string, page: number, size: number): Promise<MessagePage> {
  const res = await fetch(`${API_BASE}/${token}/messages?page=${page}&size=${size}`);
  if (!res.ok) throw new Error('Failed to load messages');
  return res.json();
}

async function postMessage(token: string, content: string): Promise<Message> {
  const res = await fetch(`${API_BASE}/${token}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role: 'user', content }),
  });
  if (res.status === 403) throw new Error('This conversation is read-only');
  if (!res.ok) throw new Error('Failed to send message');
  return res.json();
}

class NotFoundError extends Error {
  constructor() {
    super('not_found');
  }
}

// ============================================================
// Component
// ============================================================

export default function SharedConversation({ token, title: titleOverride }: SharedConversationProps) {
  const t = useTranslations('publicShare');
  const [meta, setMeta] = useState<ConversationMeta | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [input, setInput] = useState('');
  const [isSending, setIsSending] = useState(false);

  // Pagination state. `nextPage` is the index of the page to fetch next (= current
  // count of pages already fetched). 0 means we have not yet loaded page 0.
  const [nextPage, setNextPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [initialLoaded, setInitialLoaded] = useState(false);

  // Refs for scroll-position preservation across prepend.
  const containerRef = useRef<HTMLElement>(null);
  const previousScrollHeightRef = useRef(0);
  const previousScrollTopRef = useRef(0);
  const justPrependedRef = useRef(false);
  const lastFetchAtRef = useRef(0);
  const COOLDOWN_MS = 250;

  // Anchor to bottom on first hydration (with stabilization against async images).
  useAnchorScrollToBottom(containerRef, initialLoaded ? token : null, initialLoaded);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const metaData = await fetchMeta(token);
        if (cancelled) return;
        setMeta(metaData);

        if (metaData.memoryEnabled) {
          const page = await fetchMessagesPage(token, 0, PAGE_SIZE);
          if (cancelled) return;
          setMessages(page.items);
          setNextPage(1);
          setHasMore(page.hasMore);
          setInitialLoaded(true);
        } else {
          setInitialLoaded(true);
        }
      } catch (e) {
        if (cancelled) return;
        if (e instanceof NotFoundError) {
          setError(t('invalidLink'));
        } else {
          setError(t('failedToLoad'));
        }
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }

    load();
    return () => { cancelled = true; };
  }, [token, t]);

  // Lazy-load older messages when the user scrolls within 100px of the top.
  // Cooldown prevents N parallel fetches when the next page is short and the
  // viewport stays in the trigger zone.
  const handleContainerScroll = useCallback(async () => {
    const container = containerRef.current;
    if (!container) return;
    if (loadingOlder || !hasMore) return;
    if (container.scrollTop > 100) return;
    if (performance.now() - lastFetchAtRef.current < COOLDOWN_MS) return;

    previousScrollHeightRef.current = container.scrollHeight;
    previousScrollTopRef.current = container.scrollTop;
    lastFetchAtRef.current = performance.now();

    setLoadingOlder(true);
    try {
      const page = await fetchMessagesPage(token, nextPage, PAGE_SIZE);
      justPrependedRef.current = true;
      setMessages(prev => {
        // Defensive de-dup by id: never produce duplicates if an older page
        // overlaps with the current head (e.g. page boundary races).
        const seen = new Set(prev.map(m => m.id));
        const fresh = page.items.filter(m => !seen.has(m.id));
        return [...fresh, ...prev];
      });
      setNextPage(p => p + 1);
      setHasMore(page.hasMore);
    } catch {
      setError(t('failedToLoadOlder'));
    } finally {
      setLoadingOlder(false);
    }
  }, [token, nextPage, hasMore, loadingOlder, t]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    container.addEventListener('scroll', handleContainerScroll, { passive: true });
    return () => container.removeEventListener('scroll', handleContainerScroll);
  }, [handleContainerScroll]);

  // Restore scroll position synchronously after prepend so the user never sees
  // a jump from "anchored at top of new page" to "anchored at original message".
  // useLayoutEffect runs after DOM mutation but before paint. try/finally
  // guarantees the fence is cleared even if the container has been detached.
  useIsomorphicLayoutEffect(() => {
    if (!justPrependedRef.current) return;
    const container = containerRef.current;
    try {
      if (container) {
        const heightDelta = container.scrollHeight - previousScrollHeightRef.current;
        container.scrollTop = previousScrollTopRef.current + heightDelta;
      }
    } finally {
      justPrependedRef.current = false;
    }
  }, [messages.length]);

  const handleSend = useCallback(async () => {
    const text = input.trim();
    if (!text || isSending) return;

    setInput('');
    setIsSending(true);

    const tempId = `temp-${Date.now()}`;
    const userMsg: Message = { id: tempId, role: 'user', content: text };
    setMessages(prev => [...prev, userMsg]);
    // User-send always scrolls to bottom regardless of position. Run after
    // commit so scrollHeight reflects the new message.
    requestAnimationFrame(() => {
      const c = containerRef.current;
      if (c) c.scrollTop = c.scrollHeight;
    });

    try {
      const saved = await postMessage(token, text);
      setMessages(prev => prev.map(m => m.id === tempId ? saved : m));
    } catch (e) {
      setMessages(prev => prev.filter(m => m.id !== tempId));
      setError(e instanceof Error ? e.message : t('failedToSend'));
    } finally {
      setIsSending(false);
    }
  }, [token, input, isSending, t]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }, [handleSend]);

  const visibleMessages = messages.filter(
    m => (m.role === 'user' || m.role === 'assistant') && m.content != null
  );

  // Prefer the live conversation title from /c/{token} (always current) over the
  // SharedLink's stored title (frozen at share time, often empty for untitled chats).
  // Coerce empty strings to undefined so the ?? chain falls through correctly.
  const liveTitle = meta?.title?.trim() || undefined;
  const overrideTitle = titleOverride?.trim() || undefined;
  const displayTitle = liveTitle ?? overrideTitle ?? t('sharedConversation');
  const isReadWrite = meta?.shareMode === 'readwrite';

  // ── Loading ──
  if (isLoading) {
    return (
      <div className="h-screen flex items-center justify-center bg-theme-primary">
        <div className="animate-spin h-6 w-6 border-2 border-theme border-t-[var(--accent-primary)] rounded-full" />
      </div>
    );
  }

  // ── Error ──
  if (error && !meta) {
    return (
      <div className="h-screen flex items-center justify-center bg-theme-primary px-4">
        <div className="text-center">
          <div className="h-12 w-12 rounded-full bg-theme-secondary flex items-center justify-center mx-auto mb-4">
            <svg className="h-5 w-5 text-theme-muted" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z" />
            </svg>
          </div>
          <p className="text-sm text-theme-secondary">{error}</p>
        </div>
      </div>
    );
  }

  // ── Main view ──
  return (
    <ShareProviders token={token}>
    <SidePanelProvider>
    <div className="h-screen flex flex-row bg-theme-primary overflow-hidden">
    <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
      <PublicHeader title={displayTitle} />

      {/* Messages */}
      <main ref={containerRef} className="flex-1 overflow-y-auto">
        <div className="max-w-4xl mx-auto px-4 py-4 space-y-4">
          {loadingOlder && (
            <div className="flex justify-center py-2 text-xs text-theme-secondary">
              {t('loadingOlder')}
            </div>
          )}
          {!loadingOlder && !hasMore && initialLoaded && messages.length > 0 && (
            <div className="flex justify-center py-2 text-xs text-theme-muted">
              {t('endOfHistory')}
            </div>
          )}
          {!meta?.memoryEnabled ? (
            <div className="flex items-center justify-center py-12">
              <div className="text-center">
                <div className="h-10 w-10 rounded-full bg-theme-secondary flex items-center justify-center mx-auto mb-3">
                  <svg className="h-4 w-4 text-theme-muted" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M3.98 8.223A10.477 10.477 0 0 0 1.934 12c1.292 4.338 5.31 7.5 10.066 7.5.993 0 1.953-.138 2.863-.395M6.228 6.228A10.451 10.451 0 0 1 12 4.5c4.756 0 8.773 3.162 10.065 7.498a10.522 10.522 0 0 1-4.293 5.774M6.228 6.228 3 3m3.228 3.228 3.65 3.65m7.894 7.894L21 21m-3.228-3.228-3.65-3.65m0 0a3 3 0 1 0-4.243-4.243m4.242 4.242L9.88 9.88" />
                  </svg>
                </div>
                <p className="text-sm text-theme-secondary">
                  {t('historyPrivate')}
                </p>
              </div>
            </div>
          ) : visibleMessages.length === 0 ? (
            <div className="flex items-center justify-center py-12">
              <p className="text-sm text-theme-muted">{t('noMessages')}</p>
            </div>
          ) : (
            visibleMessages.map((msg) => (
              <MessageRow key={msg.id} message={msg} />
            ))
          )}
        </div>
      </main>

      {/* Error toast */}
      {error && meta && (
        <div className="fixed bottom-20 left-1/2 -translate-x-1/2 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 rounded-lg px-4 py-2 text-sm text-red-700 dark:text-red-300 shadow-lg">
          {error}
          <button
            onClick={() => setError(null)}
            className="ml-3 text-red-500 hover:text-red-700 dark:text-red-400 dark:hover:text-red-200"
          >
            {t('dismiss')}
          </button>
        </div>
      )}

      {/* Input footer (readwrite only) */}
      {isReadWrite && meta?.memoryEnabled && (
        <footer className="flex-shrink-0 bg-theme-secondary border-t border-theme">
          <div className="max-w-4xl mx-auto px-4 py-3 flex gap-2">
            <textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={t('typeMessage')}
              rows={1}
              className="flex-1 resize-none px-4 py-2.5 text-sm rounded-xl border border-theme bg-theme-primary text-theme-primary placeholder:text-theme-muted focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)] focus:border-transparent"
            />
            <button
              onClick={handleSend}
              disabled={isSending || !input.trim()}
              className="flex-shrink-0 w-10 h-10 rounded-xl bg-[var(--accent-primary)] text-[var(--accent-foreground)] flex items-center justify-center hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8" />
              </svg>
            </button>
          </div>
        </footer>
      )}
    </div>{/* inner flex-col */}
    <SidePanel />
    </div>{/* outer flex-row */}
    </SidePanelProvider>
    </ShareProviders>
  );
}

// ============================================================
// Message row - matches main chat styling
// ============================================================

function MessageRow({ message }: { message: Message }) {
  const isUser = message.role === 'user';

  return (
    <div className={`flex items-start gap-3 ${isUser ? 'justify-end mb-15' : 'justify-start mb-15'}`}>
      <div className={`${isUser ? 'max-w-[70%]' : 'flex-1'} min-w-0`}>
        <div className={isUser ? 'rounded-[18px] p-4 bg-theme-tertiary' : ''}>
          <div className="message-content break-words overflow-wrap-anywhere">
            {isUser ? (
              <p className="text-sm whitespace-pre-wrap text-theme-primary">{message.content}</p>
            ) : (
              <MarkdownRender text={message.content ?? ''} />
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
