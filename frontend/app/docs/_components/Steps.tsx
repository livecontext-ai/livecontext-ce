import type { ReactNode } from 'react';

/** Ordered procedure. A real `<ol>`, so assistive tech announces "list, N items". */
export function Steps({ children }: { children: ReactNode }) {
  return <ol className="docs-steps">{children}</ol>;
}

/** One numbered step in a vertical timeline. Server component. */
export function Step({ n, title, children }: { n: number; title: string; children?: ReactNode }) {
  return (
    <li className="docs-step">
      {/* The list already carries the ordinal for screen readers. */}
      <span className="docs-step-num" aria-hidden="true">
        {n}
      </span>
      <div className="docs-step-title">{title}</div>
      {children ? <div className="docs-step-body">{children}</div> : null}
    </li>
  );
}
