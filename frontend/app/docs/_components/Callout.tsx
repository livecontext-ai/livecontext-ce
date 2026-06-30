import type { ReactNode } from 'react';
import { Info, Lightbulb, TriangleAlert } from 'lucide-react';

type CalloutVariant = 'info' | 'tip' | 'warn';

const ICONS: Record<CalloutVariant, typeof Info> = {
  info: Info,
  tip: Lightbulb,
  warn: TriangleAlert,
};

/** Highlighted aside (info / tip / warning). Server component. */
export function Callout({ variant = 'info', children }: { variant?: CalloutVariant; children: ReactNode }) {
  const Icon = ICONS[variant];
  return (
    <div className={`docs-callout docs-callout-${variant}`}>
      <Icon className="w-4 h-4" aria-hidden="true" />
      <div>{children}</div>
    </div>
  );
}
