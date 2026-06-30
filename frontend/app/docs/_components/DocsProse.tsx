import type { ReactNode } from 'react';

/**
 * Long-form article wrapper. Typography (h2-h4, p, ul/ol, a, code, blockquote)
 * is styled by `.docs-prose` selectors in `docsStyles.ts`. The in-page TOC
 * (`DocsToc`) scans `.docs-prose h2, .docs-prose h3` after mount.
 */
export function DocsProse({ children }: { children: ReactNode }) {
  return <div className="docs-prose">{children}</div>;
}
