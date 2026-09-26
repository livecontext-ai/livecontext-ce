import type { ReactNode } from 'react';
import { Info, Lightbulb, TriangleAlert } from 'lucide-react';

type CalloutVariant = 'info' | 'tip' | 'warn';

const ICONS: Record<CalloutVariant, typeof Info> = {
  info: Info,
  tip: Lightbulb,
  warn: TriangleAlert,
};

// Visible label, so the kind of aside is never conveyed by colour or icon alone
// (WCAG 1.4.1). Screen readers read it too, as the first word of the note.
export const CALLOUT_LABELS: Record<CalloutVariant, string> = {
  info: 'Note',
  tip: 'Tip',
  warn: 'Warning',
};

/** Highlighted aside (note / tip / warning). Server component. */
export function Callout({
  variant = 'info',
  title,
  children,
}: {
  variant?: CalloutVariant;
  /** Optional heading text; defaults to the variant label (Note, Tip, Warning). */
  title?: string;
  children: ReactNode;
}) {
  const Icon = ICONS[variant];
  return (
    <aside className={`docs-callout docs-callout-${variant}`} role="note">
      <Icon className="w-4 h-4" aria-hidden="true" />
      <div>
        <p className="docs-callout-label">{title ?? CALLOUT_LABELS[variant]}</p>
        {children}
      </div>
    </aside>
  );
}
