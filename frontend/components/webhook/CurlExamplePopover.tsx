'use client';

import React, { useState, useMemo } from 'react';
import * as ReactDOM from 'react-dom';
import { Terminal, Copy, Check, X } from 'lucide-react';

export interface CurlExampleProps {
  webhookUrl: string;
  httpMethod: string;
  authType: string;
  authConfig?: Record<string, string>;
  label?: string;
  /** Button variant: 'icon' shows a small icon button, 'button' shows a labeled button */
  variant?: 'icon' | 'button';
}

/**
 * Build a curl command string from webhook parameters.
 */
export function buildCurlCommand(
  webhookUrl: string,
  httpMethod: string,
  authType: string,
  authConfig?: Record<string, string>,
): string {
  const lines: string[] = [`curl -X ${httpMethod} "${webhookUrl}"`];

  switch (authType) {
    case 'basic':
      lines.push(`  -u "${authConfig?.basicUsername || 'username'}:${authConfig?.basicPassword ? '****' : 'password'}"`);
      break;
    case 'header':
      lines.push(`  -H "${authConfig?.authHeaderName || 'X-API-Key'}: ${authConfig?.authHeaderValue ? '****' : 'your-value'}"`);
      break;
    case 'jwt':
      lines.push('  -H "Authorization: Bearer <your-jwt-token>"');
      break;
  }

  if (httpMethod !== 'GET') {
    lines.push('  -H "Content-Type: application/json"');
    lines.push("  -d '{\"key\": \"value\"}'");
  } else {
    lines.push('  # Query params are passed as payload');
    lines.push('  # Example: ?key=value');
  }

  return lines.join(' \\\n');
}

/**
 * Reusable popover component that displays a curl example for a webhook.
 * Works both in the Settings webhook cards and in the workflow builder inspector.
 */
export function CurlExamplePopover({
  webhookUrl,
  httpMethod,
  authType,
  authConfig,
  label = 'cURL',
  variant = 'icon',
}: CurlExampleProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const buttonRef = React.useRef<HTMLButtonElement>(null);
  const [position, setPosition] = useState({ top: 0, left: 0 });

  const curlExample = useMemo(
    () => buildCurlCommand(webhookUrl, httpMethod, authType, authConfig),
    [webhookUrl, httpMethod, authType, authConfig],
  );

  const handleOpen = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (buttonRef.current) {
      const rect = buttonRef.current.getBoundingClientRect();
      const popoverWidth = 480;
      let left = rect.left;
      if (left + popoverWidth > window.innerWidth - 16) {
        left = window.innerWidth - popoverWidth - 16;
      }
      if (left < 16) left = 16;
      setPosition({ top: rect.bottom + 8, left });
    }
    setIsOpen(!isOpen);
  };

  const handleCopy = () => {
    const cleanCommand = curlExample.replace(/\\\n\s*/g, ' ').replace(/\s+/g, ' ').trim();
    navigator.clipboard.writeText(cleanCommand);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="relative inline-flex">
      {variant === 'icon' ? (
        <button
          ref={buttonRef}
          onClick={handleOpen}
          className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors flex-shrink-0"
          title={label}
        >
          <Terminal className="h-3.5 w-3.5 text-slate-400 dark:text-slate-500" />
        </button>
      ) : (
        <button
          ref={buttonRef}
          onClick={handleOpen}
          className="inline-flex items-center gap-1.5 px-2 py-1 text-xs font-medium rounded-lg bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
        >
          <Terminal className="h-3 w-3" />
          {label}
        </button>
      )}

      {isOpen && typeof document !== 'undefined' && ReactDOM.createPortal(
        <>
          <div
            className="fixed inset-0 z-[9998]"
            onClick={() => setIsOpen(false)}
          />
          <div
            className="fixed z-[9999] w-[480px] p-4 bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 shadow-xl animate-in fade-in-0 zoom-in-95 duration-150"
            style={{ top: position.top, left: position.left }}
          >
            <div className="flex items-start justify-between gap-2 mb-3">
              <span className="font-semibold text-sm text-slate-700 dark:text-slate-200">
                {label}
              </span>
              <div className="flex items-center gap-1">
                <button
                  onClick={handleCopy}
                  className="p-1.5 rounded hover:bg-slate-100 dark:hover:bg-slate-700"
                  title="Copy"
                >
                  {copied ? (
                    <Check className="h-4 w-4 text-green-500" />
                  ) : (
                    <Copy className="h-4 w-4 text-slate-400" />
                  )}
                </button>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    setIsOpen(false);
                  }}
                  className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-700"
                >
                  <X className="h-4 w-4 text-slate-400" />
                </button>
              </div>
            </div>
            <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-200 dark:border-slate-700">
              <pre className="text-sm text-slate-600 dark:text-slate-300 font-mono overflow-x-auto whitespace-pre-wrap leading-relaxed">
                {curlExample}
              </pre>
            </div>
          </div>
        </>,
        document.body,
      )}
    </div>
  );
}
