'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import { useTranslations } from 'next-intl';

export interface PlanGridGroup {
  key: string;
  /** Tinted card background: sets this group off from its neighbour on the rail. */
  tinted?: boolean;
  /** One element per plan card, in display order. */
  cards: React.ReactElement[];
}

/**
 * The plan-card rail of the landing pricing section (home and persona pages). The
 * in-app pricing page keeps its own 3 + 2 grid.
 *
 * <p>Two layouts, one DOM:
 * <ul>
 *   <li><b>Mobile (&lt; md)</b>: one column. Each card collapses to a header row
 *       (name + price) and opens on tap; see {@link PlanCardToggle}.</li>
 *   <li><b>md and up</b>: every plan on ONE row that slides sideways, inside its container.
 *       3 cards and a third of the next fit when the rail has 62rem or more (a container
 *       query on the rail's own width), 2 and a quarter
 *       from 44rem, and 1 card plus a peek in a narrower rail.
 *       Nobody has to scroll sideways: a click on the faded right edge shows the last plans
 *       (the organization ones, tinted), on the left edge it comes back to the first, and
 *       the dots under the rail show where you are.</li>
 * </ul>
 *
 * <p>Cards are as tall as their own content: a short plan ends where its list ends. What
 * lines up across cards is the HEADER (name, price, founding note): every header gets the
 * height of the tallest one ({@code --plan-head-h}, measured here), so all feature lists
 * start on the same line. A card marks its header with {@code data-plan-head} and gives it
 * {@link PLAN_HEAD_CLASS}.
 */
export default function PlanGrid({ groups }: { groups: PlanGridGroup[] }) {
  const t = useTranslations('pricing.planGrid');
  const railRef = useRef<HTMLDivElement>(null);
  const total = groups.reduce((n, g) => n + g.cards.length, 0);
  // First fully visible card and how many fit, read back from the rail itself so the
  // controls follow a swipe, a trackpad or the keyboard as well as the buttons.
  const [position, setPosition] = useState({ first: 0, visible: total });

  const measure = useCallback(() => {
    const rail = railRef.current;
    if (!rail) return;
    alignHeaders(rail);
    const { step, gap } = railStep(rail);
    if (!step) return;
    // Whole cards that fit (the part of the next one is only a peek).
    const style = getComputedStyle(rail);
    const inner = rail.clientWidth - (parseFloat(style.paddingLeft) || 0) - (parseFloat(style.paddingRight) || 0);
    const visible = Math.max(1, Math.floor((inner + gap) / step + 0.01));
    const first = Math.min(Math.max(0, total - visible), Math.round(rail.scrollLeft / step));
    fitHeight(rail, first, visible);
    setPosition(prev => (prev.first === first && prev.visible === visible ? prev : { first, visible }));
  }, [total]);

  useEffect(() => {
    const rail = railRef.current;
    if (!rail) return;
    measure();
    const onScroll = () => measure();
    rail.addEventListener('scroll', onScroll, { passive: true });
    // Watches the headers too: a founding-price note that arrives after the first paint
    // makes one header taller, and every other one must follow.
    const observer = new ResizeObserver(() => measure());
    observer.observe(rail);
    rail.querySelectorAll('[data-plan-head] > *').forEach(el => observer.observe(el));
    return () => {
      rail.removeEventListener('scroll', onScroll);
      observer.disconnect();
    };
  }, [measure]);

  const scrollToCard = (index: number) => {
    const rail = railRef.current;
    if (!rail) return;
    rail.scrollTo({ left: index * railStep(rail).step, behavior: 'smooth' });
  };

  // Index of each group's first card, in rail order.
  const starts: number[] = [];
  groups.reduce((n, g) => {
    starts.push(n);
    return n + g.cards.length;
  }, 0);

  const lastFirst = Math.max(0, total - position.visible);
  const slides = position.visible < total;

  // Fade whichever edge still hides plans (md+ only; mobile is a plain column).
  const fadeLeft = slides && position.first > 0;
  const fadeRight = slides && position.first < lastFirst;
  const fades = FADES;
  const fade = fadeLeft && fadeRight ? fades.both : fadeRight ? fades.right : fadeLeft ? fades.left : '';

  return (
    <div>
      {/* A container: how many cards fit depends on the room the rail actually has, not
          on the viewport. */}
      <div className="@container relative">
        <div
          ref={railRef}
          data-testid="plan-grid"
          className={`flex flex-col gap-3 md:relative md:overflow-y-hidden md:transition-[height] md:duration-300 md:grid md:grid-flow-col md:items-start md:gap-x-5 md:gap-y-0 md:overflow-x-auto md:snap-x md:snap-mandatory md:overscroll-x-contain md:pt-4 md:pb-2 ${COLS} md:[grid-template-rows:auto] ${fade} scrollbar-hide`}
        >
          {groups.flatMap((group, gi) => {
            const start = starts[gi];
            const cells = group.cards.map((card, i) => (
              <div
                key={card.key ?? `${group.key}-${i}`}
                data-plan-cell
                className={`min-w-0 md:snap-start md:[grid-row:1] md:[grid-column:var(--plan-col)] ${group.tinted ? '[--plan-card-bg:rgb(0_0_0/0.035)] dark:[--plan-card-bg:rgb(255_255_255/0.05)]' : ''}`}
                style={{ '--plan-col': `${start + i + 1}` } as React.CSSProperties}
              >
                {card}
              </div>
            ));

            return cells;
          })}
        </div>

        {/* The faded edges go all the way: right shows the last plans (the organization
            ones), left comes back to the first. The dots below step one card at a time. */}
        {fadeLeft && <EdgeButton side="left" label={t('previous')} onClick={() => scrollToCard(0)} />}
        {fadeRight && <EdgeButton side="right" label={t('next')} onClick={() => scrollToCard(lastFirst)} />}
      </div>

      {slides && (
        <div className="mt-6 hidden items-center justify-center gap-2 md:flex">
          {Array.from({ length: lastFirst + 1 }, (_, i) => (
            <button
              key={i}
              type="button"
              aria-label={t('goTo', { n: i + 1, total: lastFirst + 1 })}
              aria-current={i === position.first ? 'true' : undefined}
              onClick={() => scrollToCard(i)}
              className={`h-2 rounded-full transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 ${i === position.first ? 'w-6 bg-[var(--text-primary)]' : 'w-2 bg-black/20 dark:bg-white/25'}`}
            />
          ))}
        </div>
      )}
    </div>
  );
}

/** Card widths per rail width (container queries on the rail's own room). */
const COLS = 'auto-cols-[calc((100%-1.25rem)/1.2)] @min-[44rem]:auto-cols-[calc((100%-2.5rem)/2.25)] @min-[62rem]:auto-cols-[calc((100%-3.75rem)/3.3)]';
/** Fade on whichever edge still hides plans. */
const FADES = {
  both: 'md:[mask-image:linear-gradient(to_right,transparent,black_3rem,black_calc(100%-3rem),transparent)]',
  right: 'md:[mask-image:linear-gradient(to_right,black_calc(100%-4rem),transparent)]',
  left: 'md:[mask-image:linear-gradient(to_right,transparent,black_4rem)]',
};

/** Classes for a card header marked `data-plan-head`: from md it is as tall as the tallest one. */
export const PLAN_HEAD_CLASS = 'md:min-h-[var(--plan-head-h)]';

/**
 * Gives every card header the height of the tallest, so the feature lists start on one
 * line while each card still ends where its own list ends. Reset first, so a header that
 * got shorter (another locale, a caption gone) is measured at its natural height.
 */
function alignHeaders(rail: HTMLDivElement) {
  const heads = rail.querySelectorAll<HTMLElement>('[data-plan-head]');
  if (heads.length === 0) return;
  rail.style.setProperty('--plan-head-h', 'auto');
  let tallest = 0;
  heads.forEach(h => { tallest = Math.max(tallest, h.getBoundingClientRect().height); });
  rail.style.setProperty('--plan-head-h', `${Math.ceil(tallest)}px`);
}

/**
 * Sizes the rail to the cards in view (and the one peeking), not to the tallest plan of
 * all: otherwise a short plan sits above a screen of empty space left by a long one that
 * is not even visible. A taller card coming into view grows the rail as it slides in.
 * Mobile keeps its natural height (a plain column).
 */
function fitHeight(rail: HTMLDivElement, first: number, visible: number) {
  if (!window.matchMedia('(min-width: 48rem)').matches) {
    rail.style.height = '';
    return;
  }
  const cells = rail.querySelectorAll<HTMLElement>('[data-plan-cell]');
  let bottom = 0;
  for (let i = first; i <= Math.min(cells.length - 1, first + visible); i++) {
    bottom = Math.max(bottom, cells[i].offsetTop + cells[i].offsetHeight);
  }
  if (bottom) rail.style.height = `${Math.ceil(bottom + (parseFloat(getComputedStyle(rail).paddingBottom) || 0))}px`;
}

/** Distance between two card starts (card width + column gap), 0 before layout. */
function railStep(rail: HTMLDivElement): { step: number; gap: number } {
  const cell = rail.querySelector<HTMLElement>('[data-plan-cell]');
  const gap = parseFloat(getComputedStyle(rail).columnGap) || 0;
  return { step: cell ? cell.offsetWidth + gap : 0, gap };
}

/**
 * The faded edge of the rail is the control: a click on the strip over the peeking card
 * goes to that end of the rail. At rest it carries a soft shaded band, so it reads as a
 * place to press; on hover (or keyboard focus) the band darkens and the pointer becomes a
 * hand. Nothing moves, and no arrow or button sits on top of the plans.
 */
function EdgeButton({ side, label, onClick }: { side: 'left' | 'right'; label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className={`group absolute bottom-0 top-4 z-10 hidden w-20 cursor-pointer md:block focus-visible:outline-none ${side === 'right' ? 'right-0 rounded-r-2xl' : 'left-0 rounded-l-2xl'}`}
    >
      {/* A soft band at rest, stronger on hover/focus. */}
      <span
        aria-hidden="true"
        className={`absolute inset-0 rounded-[inherit] transition-colors duration-300 ${side === 'right'
          ? 'bg-gradient-to-l from-black/[0.06] to-transparent group-hover:from-black/[0.14] group-focus-visible:from-black/[0.14] dark:from-white/[0.08] dark:group-hover:from-white/[0.18]'
          : 'bg-gradient-to-r from-black/[0.06] to-transparent group-hover:from-black/[0.14] group-focus-visible:from-black/[0.14] dark:from-white/[0.08] dark:group-hover:from-white/[0.18]'}`}
      />
    </button>
  );
}

/**
 * Root classes for a card placed in {@link PlanGrid}: a column whose height is its own
 * content. `hidden` bodies on mobile are the caller's job (collapsed state), see
 * `max-md:hidden` in the two cards.
 */
export const planCardClasses = 'flex flex-col';

/**
 * Mobile-only open/close control for a plan card header. The whole header is the
 * tap target (a transparent button stretched over it), the chevron is the visible
 * cue. The header it sits in must be `relative`. Hidden from md, where every card
 * is always open.
 */
export function PlanCardToggle({
  open,
  onToggle,
  label,
}: {
  open: boolean;
  onToggle: () => void;
  label: string;
}) {
  return (
    <>
      <ChevronDown
        aria-hidden="true"
        className={`h-4 w-4 flex-shrink-0 self-center text-theme-secondary transition-transform duration-200 md:hidden ${open ? 'rotate-180' : ''}`}
      />
      <button
        type="button"
        aria-expanded={open}
        aria-label={label}
        onClick={onToggle}
        className="absolute inset-0 rounded-2xl md:hidden focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60"
      />
    </>
  );
}
