'use client';

import React from 'react';

interface ThinkingDotsProps {
  message?: string;
  className?: string;
}

export function ThinkingDots({ message, className = '' }: ThinkingDotsProps) {
  return (
    <div className={`flex items-center gap-2 text-sm text-theme-secondary ${className}`}>
      {message && <span className="italic">{message}</span>}
      <span className="flex gap-1">
        <span className="w-1.5 h-1.5 bg-current rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
        <span className="w-1.5 h-1.5 bg-current rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
        <span className="w-1.5 h-1.5 bg-current rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
      </span>
    </div>
  );
}

// Parse thinking markers from content (legacy - now using streaming events)
export function parseThinkingMarker(content: string): { message: string; cleanContent: string } | null {
  if (!content) return null;
  const match = content.match(/\[thinking:([^\]]+)\]/);
  if (match) {
    const message = match[1];
    const cleanContent = content.replace(/\[thinking:[^\]]+\]\n?/g, '');
    return { message, cleanContent };
  }
  return null;
}

// Get the last thinking message from content (legacy - now using streaming events)
export function getLastThinkingMessage(content: string): string | null {
  if (!content) return null;
  const matches = content.match(/\[thinking:([^\]]+)\]/g);
  if (matches && matches.length > 0) {
    const lastMatch = matches[matches.length - 1];
    const message = lastMatch.match(/\[thinking:([^\]]+)\]/);
    return message ? message[1] : null;
  }
  return null;
}

export default ThinkingDots;
