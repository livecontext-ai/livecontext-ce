'use client';

import React, { useState, useCallback } from 'react';
import {
  Copy,
  Check,
  Share2,
  ThumbsUp,
  ThumbsDown,
  RotateCcw,
  Download,
} from 'lucide-react';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { useTranslations } from 'next-intl';
import { conversationApi } from '@/lib/api/conversationApi';

interface MessageActionsProps {
  content: string;
  messageId: string;
  timeLabel?: string;
  initialFeedback?: number | null;
  /** Show the thumbs up/down feedback controls. Defaults to true (agent chat).
   *  DM threads pass false: rating another participant's message is meaningless. */
  showFeedback?: boolean;
  onRetry?: () => void;
}

export function MessageActions({ content, messageId, timeLabel, initialFeedback, showFeedback = true, onRetry }: MessageActionsProps) {
  const t = useTranslations('chat.messageActions');
  const [copied, setCopied] = useState(false);
  const [feedback, setFeedback] = useState<'up' | 'down' | null>(
    initialFeedback === 1 ? 'up' : initialFeedback === -1 ? 'down' : null
  );

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Fallback for older browsers
      const textarea = document.createElement('textarea');
      textarea.value = content;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  }, [content]);

  const handleShare = useCallback(async () => {
    if (navigator.share) {
      try {
        await navigator.share({ text: content });
      } catch {
        // User cancelled or share failed - copy as fallback
        await navigator.clipboard.writeText(content);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
      }
    } else {
      // No Web Share API - copy to clipboard
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  }, [content]);

  const handleDownload = useCallback(() => {
    const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `message-${messageId.slice(0, 8)}.md`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }, [content, messageId]);

  const handleFeedback = useCallback((type: 'up' | 'down') => {
    const newValue = feedback === type ? null : type;
    setFeedback(newValue);
    const numericValue = newValue === 'up' ? 1 : newValue === 'down' ? -1 : null;
    conversationApi.updateMessageFeedback(messageId, numericValue).catch(() => {
      // Revert on failure
      setFeedback(feedback);
    });
  }, [feedback, messageId]);

  return (
    <TooltipProvider delayDuration={300}>
      <div className="flex items-center gap-0.5 mt-1 opacity-0 group-hover:opacity-100 transition-opacity duration-150">
        {/* Copy */}
        <Tooltip>
          <TooltipTrigger asChild>
            <button
              type="button"
              onClick={handleCopy}
              className="p-1 rounded-md text-theme-secondary hover:text-theme-primary hover:bg-theme-secondary/40 transition-colors"
            >
              {copied ? (
                <Check className="h-3.5 w-3.5 text-green-500" />
              ) : (
                <Copy className="h-3.5 w-3.5" />
              )}
            </button>
          </TooltipTrigger>
          <TooltipContent side="bottom">
            <p className="text-xs">{copied ? t('copied') : t('copy')}</p>
          </TooltipContent>
        </Tooltip>

        {/* Share */}
        <Tooltip>
          <TooltipTrigger asChild>
            <button
              type="button"
              onClick={handleShare}
              className="p-1 rounded-md text-theme-secondary hover:text-theme-primary hover:bg-theme-secondary/40 transition-colors"
            >
              <Share2 className="h-3.5 w-3.5" />
            </button>
          </TooltipTrigger>
          <TooltipContent side="bottom">
            <p className="text-xs">{t('share')}</p>
          </TooltipContent>
        </Tooltip>

        {/* Retry */}
        {onRetry && (
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                onClick={onRetry}
                className="p-1 rounded-md text-theme-secondary hover:text-theme-primary hover:bg-theme-secondary/40 transition-colors"
              >
                <RotateCcw className="h-3.5 w-3.5" />
              </button>
            </TooltipTrigger>
            <TooltipContent side="bottom">
              <p className="text-xs">{t('retry')}</p>
            </TooltipContent>
          </Tooltip>
        )}

        {/* Thumbs up/down feedback - agent chat only; hidden in DM threads where
            rating another participant's message is meaningless. */}
        {showFeedback && (
          <>
            {/* Thumbs Up */}
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  type="button"
                  onClick={() => handleFeedback('up')}
                  className={`p-1 rounded-md transition-colors ${
                    feedback === 'up'
                      ? 'text-green-500 bg-green-500/10'
                      : 'text-theme-secondary hover:text-theme-primary hover:bg-theme-secondary/40'
                  }`}
                >
                  <ThumbsUp className="h-3.5 w-3.5" />
                </button>
              </TooltipTrigger>
              <TooltipContent side="bottom">
                <p className="text-xs">{t('helpful')}</p>
              </TooltipContent>
            </Tooltip>

            {/* Thumbs Down */}
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  type="button"
                  onClick={() => handleFeedback('down')}
                  className={`p-1 rounded-md transition-colors ${
                    feedback === 'down'
                      ? 'text-red-500 bg-red-500/10'
                      : 'text-theme-secondary hover:text-theme-primary hover:bg-theme-secondary/40'
                  }`}
                >
                  <ThumbsDown className="h-3.5 w-3.5" />
                </button>
              </TooltipTrigger>
              <TooltipContent side="bottom">
                <p className="text-xs">{t('notHelpful')}</p>
              </TooltipContent>
            </Tooltip>
          </>
        )}

        {/* Download */}
        <Tooltip>
          <TooltipTrigger asChild>
            <button
              type="button"
              onClick={handleDownload}
              className="p-1 rounded-md text-theme-secondary hover:text-theme-primary hover:bg-theme-secondary/40 transition-colors"
            >
              <Download className="h-3.5 w-3.5" />
            </button>
          </TooltipTrigger>
          <TooltipContent side="bottom">
            <p className="text-xs">{t('download')}</p>
          </TooltipContent>
        </Tooltip>

        {timeLabel && (
          <span className="ml-1 text-xs text-theme-secondary tabular-nums">{timeLabel}</span>
        )}
      </div>
    </TooltipProvider>
  );
}
