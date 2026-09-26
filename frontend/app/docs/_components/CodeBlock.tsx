'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import type { HTMLAttributes } from 'react';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark, oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Copy, Check } from 'lucide-react';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';
import { useHorizontalOverflow } from './useHorizontalOverflow';
import { accessibleCodeTheme } from './accessibleCodeTheme';

// Token colours adjusted to 4.5:1 on the code surface (--bg-secondary of each docs theme).
const LIGHT_THEME = accessibleCodeTheme(oneLight, '#f5f6f8');
const DARK_THEME = accessibleCodeTheme(oneDark, '#1f1e1b');

interface CodeBlockProps {
  language?: string;
  /** Optional file name or short label shown in the header instead of the language. */
  title?: string;
  children: string;
}

/** The `<pre>` is what scrolls on a long unbreakable line, so it is the element that
 *  becomes a focusable, labelled region, and only while it actually overflows. */
function makePre(label: string) {
  return function DocsCodePre(props: HTMLAttributes<HTMLPreElement>) {
    const [ref, overflowing] = useHorizontalOverflow<HTMLPreElement>();
    return (
      <pre
        {...props}
        ref={ref}
        role={overflowing ? 'region' : undefined}
        aria-label={overflowing ? `Code example: ${label}` : undefined}
        tabIndex={overflowing ? 0 : undefined}
      />
    );
  };
}

/**
 * Syntax-highlighted code block with a copy button. Keyed off the LANDING theme
 * (`useLandingTheme`), not the app theme, so it matches the docs surface.
 *
 * Accessibility: the copy button keeps one accessible name ("Copy code", which contains its
 * visible text, WCAG 2.5.3);
 * the result is announced once, through a polite live region, and again on every
 * later copy (the message is cleared first, so a repeat is a real change).
 */
export function CodeBlock({ language = 'text', title, children }: CodeBlockProps) {
  const { theme } = useLandingTheme();
  const [copied, setCopied] = useState(false);
  const resetTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => {
    if (resetTimer.current) clearTimeout(resetTimer.current);
  }, []);
  const code = children.replace(/\n$/, '');
  const safeLang = /^[a-zA-Z0-9_+-]+$/.test(language) ? language : 'text';
  const label = title ?? safeLang;
  // Stable per label, so the highlighter does not remount the <pre> on every render.
  const PreTag = useMemo(() => makePre(label), [label]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      if (resetTimer.current) clearTimeout(resetTimer.current);
      // Clear, then set on the next tick: a second copy within 2 s changes the live
      // region again, so screen readers announce it again.
      setCopied(false);
      resetTimer.current = setTimeout(() => {
        setCopied(true);
        resetTimer.current = setTimeout(() => setCopied(false), 2000);
      }, 50);
    } catch {
      /* clipboard unavailable */
    }
  };

  return (
    <div className="docs-code">
      <div className="docs-code-head">
        <span className="docs-code-lang">{label}</span>
        <button type="button" onClick={copy} className="docs-code-copy" aria-label="Copy code">
          {copied ? <Check className="w-3.5 h-3.5" aria-hidden="true" /> : <Copy className="w-3.5 h-3.5" aria-hidden="true" />}
          Copy
        </button>
        <span className="sr-only" role="status" aria-live="polite">
          {copied ? 'Code copied to clipboard' : ''}
        </span>
      </div>
      <SyntaxHighlighter
        language={safeLang}
        style={theme === 'dark' ? DARK_THEME : LIGHT_THEME}
        PreTag={PreTag}
        customStyle={{
          margin: 0,
          padding: '0.875rem 1rem',
          background: 'transparent',
          fontSize: '0.82rem',
          lineHeight: 1.6,
        }}
        wrapLongLines
      >
        {code}
      </SyntaxHighlighter>
    </div>
  );
}
