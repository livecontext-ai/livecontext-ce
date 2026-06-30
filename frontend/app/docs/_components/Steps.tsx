import type { ReactNode } from 'react';

export function Steps({ children }: { children: ReactNode }) {
  return <div className="docs-steps">{children}</div>;
}

/** One numbered step in a vertical timeline. Server component. */
export function Step({ n, title, children }: { n: number; title: string; children?: ReactNode }) {
  return (
    <div className="docs-step">
      <span className="docs-step-num">{n}</span>
      <div className="docs-step-title">{title}</div>
      {children ? <div className="docs-step-body">{children}</div> : null}
    </div>
  );
}
