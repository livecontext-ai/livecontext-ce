'use client';

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { ArrowUp } from 'lucide-react';
import MarkdownRender from '@/components/MarkdownRender';
import PublicHeader from '@/components/sharing/PublicHeader';
import { useTranslations } from 'next-intl';

interface ChatConfig {
  name: string;
  welcomeMessage?: string;
  memoryEnabled?: boolean;
  isActive?: boolean;
}

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
}

interface PublicChatProps {
  resourceToken: string;
  title?: string;
}

const SESSION_KEY_PREFIX = 'lc_share_chat_';

/**
 * Full-page public chat component.
 * Matches the main LiveContext chat styling: MarkdownRender, theme-aware bubbles,
 * pill-shaped MessageComposer input.
 * Uses raw fetch (no auth) through gateway: /chat/{token}/*
 */
export default function PublicChat({ resourceToken, title }: PublicChatProps) {
  const t = useTranslations('publicShare');
  const [config, setConfig] = useState<ChatConfig | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const storageKey = SESSION_KEY_PREFIX + resourceToken;

  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  useEffect(() => { scrollToBottom(); }, [messages, scrollToBottom]);

  // Auto-resize textarea (same as MessageComposer: min 52px, max 200px)
  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = 'auto';
    const scrollHeight = textarea.scrollHeight;
    const newHeight = Math.max(52, Math.min(scrollHeight, 200));
    textarea.style.height = `${newHeight}px`;
  }, [input]);

  // Load config
  useEffect(() => {
    async function loadConfig() {
      try {
        const res = await fetch(`/chat/${resourceToken}/config`, {
          headers: { 'Content-Type': 'application/json' },
        });
        if (!res.ok) {
          setError(t('chat.notFound'));
          return;
        }
        const data: ChatConfig = await res.json();
        if (data.isActive === false) {
          setError(t('chat.noLongerActive'));
          return;
        }
        setConfig(data);
      } catch {
        setError(t('chat.failedToLoadConfig'));
      }
    }
    loadConfig();
  }, [resourceToken, t]);

  const loadHistory = useCallback(async (sid: string) => {
    try {
      const res = await fetch(`/chat/${resourceToken}/history`, {
        headers: {
          'Content-Type': 'application/json',
          'X-Chat-Session': sid,
        },
      });
      if (res.ok) {
        const data = await res.json();
        if (Array.isArray(data)) {
          setMessages(data.map((msg: { id?: string; role?: string; content?: string }, i: number) => ({
            id: msg.id || `hist-${i}`,
            role: msg.role === 'assistant' ? 'assistant' as const : 'user' as const,
            content: msg.content || '',
          })));
        }
      }
    } catch {
      // History load failure is non-fatal
    }
  }, [resourceToken]);

  // Restore or create session
  useEffect(() => {
    if (!config) return;

    async function initSession() {
      const storedSessionId = localStorage.getItem(storageKey);

      try {
        const res = await fetch(`/chat/${resourceToken}/session`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ sessionId: storedSessionId }),
        });

        if (!res.ok) {
          const errBody = await res.json().catch(() => null);
          setError(errBody?.error || errBody?.message || t('chat.failedToCreateSession'));
          return;
        }

        const data = await res.json();
        const sid = data.sessionId;
        setSessionId(sid);
        localStorage.setItem(storageKey, sid);

        // Load history if session was restored
        if (storedSessionId && config.memoryEnabled) {
          loadHistory(sid);
        }
      } catch {
        setError(t('chat.failedToConnect'));
      }
    }
    initSession();
  }, [config, resourceToken, storageKey, loadHistory, t]);

  async function handleSend() {
    if (!input.trim() || !sessionId || sending) return;

    const userMessage: ChatMessage = {
      id: `user-${Date.now()}`,
      role: 'user',
      content: input.trim(),
    };

    setMessages(prev => [...prev, userMessage]);
    setInput('');
    setSending(true);

    try {
      const res = await fetch(`/chat/${resourceToken}/message`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, message: userMessage.content }),
      });

      if (!res.ok) {
        const body = await res.json().catch(() => null);
        setError(body?.error || t('failedToSend'));
        return;
      }

      const data = await res.json();

      if (data.content) {
        const assistantMessage: ChatMessage = {
          id: `assistant-${Date.now()}`,
          role: 'assistant',
          content: data.content,
        };
        setMessages(prev => [...prev, assistantMessage]);
      }
    } catch {
      setError(t('chat.connectionError'));
    } finally {
      setSending(false);
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  // Error state - matching SharedConversation error style
  if (error && !config) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary px-4">
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

  // Loading state
  if (!config) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary">
        <div className="animate-spin h-6 w-6 border-2 border-theme border-t-[var(--accent-primary)] rounded-full" />
      </div>
    );
  }

  const chatTitle = title || config.name || t('chat.defaultTitle');

  return (
    <div className="min-h-screen flex flex-col bg-theme-primary">
      <PublicHeader title={chatTitle} />

      {/* Messages area - matching MessageHistory layout */}
      <main className="flex-1 overflow-y-auto">
        <div className="max-w-4xl mx-auto px-4 py-4 space-y-4">
          {/* Welcome message */}
          {messages.length === 0 && config.welcomeMessage && (
            <div className="flex items-center justify-center py-12">
              <p className="text-sm text-theme-muted">{config.welcomeMessage}</p>
            </div>
          )}

          {messages.map((msg) => (
            <MessageRow key={msg.id} message={msg} />
          ))}

          {/* Sending indicator */}
          {sending && (
            <div className="flex items-start gap-3 justify-start mb-15">
              <div className="flex-1 min-w-0">
                <div className="message-content break-words overflow-wrap-anywhere">
                  <div className="flex items-center gap-1.5 py-2">
                    <span className="w-1.5 h-1.5 bg-theme-muted rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                    <span className="w-1.5 h-1.5 bg-theme-muted rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                    <span className="w-1.5 h-1.5 bg-theme-muted rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
                  </div>
                </div>
              </div>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>
      </main>

      {/* Error toast */}
      {error && config && (
        <div className="fixed bottom-24 left-1/2 -translate-x-1/2 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 rounded-lg px-4 py-2 text-sm text-red-700 dark:text-red-300 shadow-lg z-20">
          {error}
          <button
            onClick={() => setError(null)}
            className="ml-3 text-red-500 hover:text-red-700 dark:text-red-400 dark:hover:text-red-200"
          >
            {t('dismiss')}
          </button>
        </div>
      )}

      {/* Input area - pill-shaped MessageComposer style */}
      <footer className="sticky bottom-0 bg-theme-primary pb-4 px-4">
        <div className="max-w-4xl mx-auto">
          <div
            className="bg-theme-primary overflow-hidden shadow-sm border border-theme"
            style={{ borderRadius: '28px' }}
          >
            <div className="grid grid-cols-[1fr_auto] p-2.5 gap-y-1">
              {/* Textarea */}
              <div className="col-span-2 px-3 pt-2 pb-1">
                <textarea
                  ref={textareaRef}
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder={t('typeMessage')}
                  disabled={sending || !sessionId}
                  rows={2}
                  className="w-full bg-transparent text-theme-primary placeholder-theme-muted focus:outline-none resize-none text-base leading-6 overflow-y-auto disabled:opacity-50 disabled:cursor-not-allowed"
                  style={{ minHeight: '52px', maxHeight: '200px' }}
                />
              </div>

              {/* Empty leading spacer */}
              <div />

              {/* Send button */}
              <div className="flex items-center">
                <button
                  onClick={handleSend}
                  disabled={!input.trim() || sending || !sessionId}
                  className="h-9 w-9 rounded-full bg-theme-inverted text-theme-inverted flex items-center justify-center disabled:opacity-50 disabled:cursor-not-allowed transition-colors hover:opacity-90"
                  aria-label={t('chat.sendMessage')}
                >
                  <ArrowUp className="w-5 h-5" />
                </button>
              </div>
            </div>
          </div>
        </div>
      </footer>
    </div>
  );
}

// ============================================================
// Message row - matches main LiveContext chat styling
// Same as SharedConversation's MessageRow
// ============================================================

function MessageRow({ message }: { message: ChatMessage }) {
  const isUser = message.role === 'user';

  return (
    <div className={`flex items-start gap-3 ${isUser ? 'justify-end mb-15' : 'justify-start mb-15'}`}>
      <div className={`${isUser ? 'max-w-[70%]' : 'flex-1'} min-w-0`}>
        <div className={isUser ? 'rounded-[18px] p-4 bg-theme-tertiary' : ''}>
          <div className="message-content break-words overflow-wrap-anywhere">
            {isUser ? (
              <p className="text-sm whitespace-pre-wrap text-theme-primary">{message.content}</p>
            ) : (
              <MarkdownRender text={message.content} />
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
