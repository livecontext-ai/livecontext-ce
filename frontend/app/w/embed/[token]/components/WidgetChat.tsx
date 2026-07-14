'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { WidgetApiService, type WidgetConfig, type WidgetMessage } from '@/lib/api/widget-api';
import { AvatarDisplay } from '@/components/agents/AvatarPicker';
import { useTranslations } from 'next-intl';

interface WidgetChatProps {
  token: string;
}

const SESSION_KEY_PREFIX = 'lc_widget_session_';

export default function WidgetChat({ token }: WidgetChatProps) {
  const t = useTranslations('publicShare');
  const [config, setConfig] = useState<WidgetConfig | null>(null);
  const [messages, setMessages] = useState<WidgetMessage[]>([]);
  const [input, setInput] = useState('');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isSending, setIsSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const apiRef = useRef<WidgetApiService | null>(null);
  const pendingComposerFocusRef = useRef(false);

  // Initialize API service
  useEffect(() => {
    apiRef.current = new WidgetApiService(token);
  }, [token, t]);

  // Scroll to bottom when messages change
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    if (!isSending && pendingComposerFocusRef.current) {
      pendingComposerFocusRef.current = false;
      inputRef.current?.focus();
    }
  }, [isSending]);

  // Load config and restore session
  useEffect(() => {
    async function init() {
      if (!apiRef.current) return;

      try {
        // Fetch config
        const widgetConfig = await apiRef.current.getConfig();
        if (!widgetConfig) {
          setError(t('widget.notAvailable'));
          setIsLoading(false);
          return;
        }
        setConfig(widgetConfig);

        // Try restoring session from localStorage
        const storedSessionId = localStorage.getItem(SESSION_KEY_PREFIX + token);
        if (storedSessionId) {
          setSessionId(storedSessionId);
          // Load history
          const history = await apiRef.current.getHistory(storedSessionId);
          if (history.length > 0) {
            setMessages(history);
          }
        }
      } catch {
        setError(t('widget.failedToLoad'));
      } finally {
        setIsLoading(false);
      }
    }

    init();
  }, [token]);

  // Ensure session exists before sending
  const ensureSession = useCallback(async (): Promise<string | null> => {
    if (sessionId) return sessionId;
    if (!apiRef.current) return null;

    const session = await apiRef.current.createSession();
    if (!session) return null;

    setSessionId(session.sessionId);
    localStorage.setItem(SESSION_KEY_PREFIX + token, session.sessionId);
    return session.sessionId;
  }, [sessionId, token]);

  // Send message
  const handleSend = useCallback(async () => {
    const text = input.trim();
    if (!text || isSending || !apiRef.current) return;

    setInput('');
    setIsSending(true);

    // Add user message
    const userMessage: WidgetMessage = { role: 'user', content: text };
    setMessages(prev => [...prev, userMessage]);

    try {
      // Ensure session
      const sid = await ensureSession();
      if (!sid) {
        setError(t('widget.failedToCreateSession'));
        setIsSending(false);
        return;
      }

      // Send message (sync - blocks until agent completes)
      const result = await apiRef.current.sendMessage(sid, text);
      if (result?.content) {
        setMessages(prev => [...prev, { role: 'assistant', content: result.content }]);
      } else {
        setMessages(prev => [...prev, { role: 'assistant', content: t('widget.couldNotProcess') }]);
      }
    } catch {
      setMessages(prev => [...prev, { role: 'assistant', content: t('widget.errorOccurred') }]);
    } finally {
      pendingComposerFocusRef.current = true;
      setIsSending(false);
    }
  }, [input, isSending, ensureSession, t]);

  // Handle Enter key
  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }, [handleSend]);

  // Close widget (postMessage to parent)
  const handleClose = useCallback(() => {
    window.parent.postMessage({ type: 'lc-widget-close' }, '*');
  }, []);

  // Determine theme
  const isDark = config?.theme === 'dark' ||
    (config?.theme === 'auto' && typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches);

  const primaryColor = config?.primaryColor || '#000000';

  if (isLoading) {
    return (
      <div className={`flex items-center justify-center h-screen ${isDark ? 'bg-gray-900' : 'bg-white'}`}>
        <div className="animate-spin h-6 w-6 border-2 border-current border-t-transparent rounded-full" style={{ color: primaryColor }} />
      </div>
    );
  }

  if (error) {
    return (
      <div className={`flex items-center justify-center h-screen ${isDark ? 'bg-gray-900 text-gray-300' : 'bg-white text-gray-500'}`}>
        <p className="text-sm">{error}</p>
      </div>
    );
  }

  return (
    <div className={`flex flex-col h-screen ${isDark ? 'bg-gray-900 text-gray-100' : 'bg-white text-gray-900'}`}>
      {/* Header */}
      <div
        className="flex items-center gap-3 px-4 py-3 shrink-0"
        style={{ backgroundColor: primaryColor }}
      >
        {config?.showAvatar && config?.agentAvatarUrl && (
          <div className="h-8 w-8 rounded-full bg-white/20 overflow-hidden shrink-0">
            {/* AvatarDisplay resolves preset: values (incl. custom colors) and
                falls back to a preset on a broken URL - a raw <img> renders
                nothing for preset avatars in this anonymous embed. */}
            <AvatarDisplay
              avatarUrl={config.agentAvatarUrl}
              name={config.agentName || t('widget.agent')}
              size="sm"
            />
          </div>
        )}
        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium text-white truncate">{config?.agentName || t('widget.assistant')}</p>
        </div>
        <button
          onClick={handleClose}
          className="text-white/80 hover:text-white transition-colors p-1"
          aria-label={t('widget.close')}
        >
          <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      {/* Messages */}
      <div className={`flex-1 overflow-y-auto px-4 py-3 space-y-3 ${isDark ? 'bg-gray-900' : 'bg-gray-50'}`}>
        {/* Welcome message */}
        {messages.length === 0 && config?.welcomeMessage && (
          <div className="flex justify-start">
            <div
              className={`max-w-[80%] px-3 py-2 rounded-lg text-sm ${
                isDark ? 'bg-gray-800 text-gray-200' : 'bg-white text-gray-800 shadow-sm'
              }`}
            >
              {config.welcomeMessage}
            </div>
          </div>
        )}

        {messages.map((msg, i) => (
          <div key={i} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div
              className={`max-w-[80%] px-3 py-2 rounded-lg text-sm whitespace-pre-wrap break-words ${
                msg.role === 'user'
                  ? 'text-white'
                  : isDark
                    ? 'bg-gray-800 text-gray-200'
                    : 'bg-white text-gray-800 shadow-sm'
              }`}
              style={msg.role === 'user' ? { backgroundColor: primaryColor } : undefined}
            >
              {msg.content}
            </div>
          </div>
        ))}

        {/* Loading indicator */}
        {isSending && (
          <div className="flex justify-start">
            <div
              className={`px-3 py-2 rounded-lg ${
                isDark ? 'bg-gray-800' : 'bg-white shadow-sm'
              }`}
            >
              <div className="flex gap-1">
                <span className="h-2 w-2 rounded-full animate-bounce" style={{ backgroundColor: primaryColor, animationDelay: '0ms' }} />
                <span className="h-2 w-2 rounded-full animate-bounce" style={{ backgroundColor: primaryColor, animationDelay: '150ms' }} />
                <span className="h-2 w-2 rounded-full animate-bounce" style={{ backgroundColor: primaryColor, animationDelay: '300ms' }} />
              </div>
            </div>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* Input */}
      <div className={`shrink-0 px-4 py-3 border-t ${isDark ? 'border-gray-700 bg-gray-900' : 'border-gray-200 bg-white'}`}>
        <div className={`flex items-end gap-2 rounded-lg border px-3 py-2 ${
          isDark ? 'border-gray-700 bg-gray-800' : 'border-gray-300 bg-white'
        }`}>
          <textarea
            ref={inputRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={t('typeMessage')}
            rows={1}
            className={`flex-1 resize-none text-sm outline-none bg-transparent ${
              isDark ? 'text-gray-100 placeholder-gray-500' : 'text-gray-900 placeholder-gray-400'
            }`}
            style={{ maxHeight: '100px' }}
            disabled={isSending}
          />
          <button
            onClick={handleSend}
            disabled={!input.trim() || isSending}
            className="shrink-0 p-1.5 rounded-md text-white disabled:opacity-50 transition-opacity"
            style={{ backgroundColor: primaryColor }}
            aria-label={t('widget.sendMessage')}
          >
            <svg className="h-4 w-4" viewBox="0 0 24 24" fill="currentColor">
              <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  );
}
