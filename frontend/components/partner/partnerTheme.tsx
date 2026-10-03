import type * as React from 'react';
import { cn } from '@/lib/utils';

/**
 * The partner program's visual language, shared by the public /partners page and the partner's
 * own space in Settings, so the two read as one product: dark bands, gold for money and calls
 * to action, the tier metals for standing.
 */

/** The ground of the dark bands: the same in both themes. */
export const PARTNER_DARK = '#07080c';

/** Ink on the dark bands: body copy, then captions. */
export const PARTNER_INK_MUTED = 'rgba(255,255,255,0.72)';
export const PARTNER_INK_FAINT = 'rgba(255,255,255,0.55)';

/** The gold of a figure or an accent on the dark bands. */
export const PARTNER_GOLD = '#f2b640';

/** Gold ink for a headline figure or word. */
export const PARTNER_GOLD_TEXT: React.CSSProperties = {
  background: 'linear-gradient(90deg, #fde68a, #f2b640 55%, #e0a526)',
  WebkitBackgroundClip: 'text',
  backgroundClip: 'text',
  color: 'transparent',
};

/** The gold fill of a primary call to action, with its dark ink. */
export const PARTNER_GOLD_BG: React.CSSProperties = {
  background: 'linear-gradient(135deg, #fde68a, #f2b640 55%, #d99a1e)',
  color: '#2a1a00',
};

export const PARTNER_GOLD_CTA =
  'inline-flex items-center justify-center gap-2 h-11 px-6 rounded-xl text-sm font-semibold transition-transform active:scale-[0.98] cursor-pointer';

/** The display face of the band titles, as on /partners. */
export const PARTNER_DISPLAY: React.CSSProperties = {
  fontFamily: 'var(--font-outfit), Outfit, sans-serif',
  letterSpacing: '-0.02em',
  lineHeight: 1.1,
};

/** A secondary call to action on a dark band. */
export const PARTNER_GHOST_ON_DARK: React.CSSProperties = { border: '1px solid rgba(255,255,255,0.25)', color: '#fff' };

/** A glass card inside a dark band (the hero earnings card of /partners). */
export const PARTNER_GLASS: React.CSSProperties = {
  background: 'linear-gradient(180deg, rgba(255,255,255,0.10), rgba(255,255,255,0.03))',
  border: '1px solid rgba(255,255,255,0.14)',
};

/** An eyebrow for the dark bands, where a muted ink would not read. */
export function PartnerDarkEyebrow({ icon: Icon, children }: {
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
  children: React.ReactNode;
}) {
  return (
    <span
      className="inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-wider"
      style={{ background: 'rgba(242,182,64,0.12)', color: PARTNER_GOLD, border: '1px solid rgba(242,182,64,0.3)' }}
    >
      <Icon className="h-3.5 w-3.5" aria-hidden />
      {children}
    </span>
  );
}

/**
 * A dark band of the partner's space, rounded to sit in the settings column: the same ground and
 * gold glow as the bands of /partners. White ink is set here; children use the PARTNER_INK_* tones.
 */
export function PartnerDarkPanel({ children, className, ...rest }: React.HTMLAttributes<HTMLElement> & {
  'data-testid'?: string;
}) {
  return (
    <section className={cn('relative overflow-hidden rounded-3xl text-white', className)} style={{ background: PARTNER_DARK }} {...rest}>
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0"
        style={{
          background: 'radial-gradient(45% 60% at 85% 10%, rgba(242,182,64,0.20), transparent 70%), radial-gradient(40% 55% at 5% 100%, rgba(169,180,214,0.12), transparent 70%)',
        }}
      />
      <div className="relative">{children}</div>
    </section>
  );
}
