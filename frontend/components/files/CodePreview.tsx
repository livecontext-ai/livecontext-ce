'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark, oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Copy, Check } from 'lucide-react';
import { useThemeSafely } from '@/hooks/useThemeSafely';

interface CodePreviewProps {
  code: string;
  /** Prism language id (e.g. {@code 'json'}, {@code 'typescript'}, {@code 'markup'}). */
  language: string;
  /** Wrap long lines instead of horizontal-scrolling (nicer for JSON/data, off for code). */
  wrap?: boolean;
  /** Show a gutter of line numbers (helpful for code, noisy for one-liners). */
  showLineNumbers?: boolean;
}

/**
 * Syntax-highlighted, scrollable code/data block used by the file detail preview
 * (JSON, source files, XML/HTML, YAML…). Mirrors the highlighting used in chat
 * code blocks ({@code MarkdownRender}) - Prism with the oneDark/oneLight theme -
 * so a stored {@code .json}/{@code .ts}/{@code .py} file reads like real code
 * instead of a flat monospace dump. A hover copy button lifts the whole snippet.
 */
export function CodePreview({ code, language, wrap = false, showLineNumbers = false }: CodePreviewProps) {
  const { theme } = useThemeSafely();
  const tCommon = useTranslations('common');
  const [copied, setCopied] = React.useState(false);
  const copyLabel = copied ? tCommon('copied') : tCommon('copy');

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('Copy failed:', err);
    }
  };

  // Guard against an unknown/empty language slipping through - Prism throws on
  // some undefined grammars; 'text' is always registered.
  const safeLanguage = /^[a-zA-Z0-9_-]+$/.test(language) ? language : 'text';

  return (
    <div className="group relative w-full max-h-[70vh] overflow-auto rounded-lg border border-theme bg-[var(--bg-secondary)] text-left">
      <button
        type="button"
        onClick={handleCopy}
        title={copyLabel}
        aria-label={copyLabel}
        className="absolute top-2 right-2 z-10 p-1.5 rounded-md bg-[var(--bg-primary)]/80 text-[var(--text-secondary)] hover:text-[var(--text-primary)] opacity-0 group-hover:opacity-100 transition-opacity"
      >
        {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
      </button>
      <SyntaxHighlighter
        language={safeLanguage}
        style={theme === 'dark' ? oneDark : oneLight}
        PreTag="div"
        showLineNumbers={showLineNumbers}
        wrapLongLines={wrap}
        customStyle={{
          margin: 0,
          padding: '0.75rem',
          background: 'transparent',
          fontSize: '0.75rem',
          lineHeight: '1.5',
        }}
        codeTagProps={{ style: { fontFamily: 'var(--font-mono, ui-monospace, monospace)' } }}
      >
        {code}
      </SyntaxHighlighter>
    </div>
  );
}
