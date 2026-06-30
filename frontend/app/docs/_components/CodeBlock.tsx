'use client';

import { useState } from 'react';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark, oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Copy, Check } from 'lucide-react';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';

interface CodeBlockProps {
  language?: string;
  children: string;
}

/**
 * Syntax-highlighted code block with a copy button. Keyed off the LANDING theme
 * (`useLandingTheme`), not the app theme, so it matches the docs surface.
 */
export function CodeBlock({ language = 'text', children }: CodeBlockProps) {
  const { theme } = useLandingTheme();
  const [copied, setCopied] = useState(false);
  const code = children.replace(/\n$/, '');
  const safeLang = /^[a-zA-Z0-9_+-]+$/.test(language) ? language : 'text';

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard unavailable */
    }
  };

  return (
    <div className="docs-code">
      <div className="docs-code-head">
        <span className="docs-code-lang">{safeLang}</span>
        <button type="button" onClick={copy} className="docs-code-copy" aria-label="Copy code">
          {copied ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <SyntaxHighlighter
        language={safeLang}
        style={theme === 'dark' ? oneDark : oneLight}
        PreTag="pre"
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
