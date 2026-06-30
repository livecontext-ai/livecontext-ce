import type { ReactNode } from 'react';

interface DocsHeroProps {
  /** Small uppercase section label above the title (e.g. the nav section). */
  eyebrow?: string;
  title: string;
  lead?: ReactNode;
}

/** Page header: eyebrow + Outfit H1 + lead paragraph. Server component. */
export function DocsHero({ eyebrow, title, lead }: DocsHeroProps) {
  return (
    <header>
      {eyebrow ? <span className="docs-eyebrow">{eyebrow}</span> : null}
      <h1 className="docs-h1">{title}</h1>
      {lead ? <p className="docs-lead">{lead}</p> : null}
    </header>
  );
}
