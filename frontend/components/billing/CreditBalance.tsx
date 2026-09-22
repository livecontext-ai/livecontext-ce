'use client';

/**
 * Credit-balance display kit: a progress RING (drawn around the sidebar avatar,
 * where it sweeps to its position on arrival) and the BALANCE PANEL at the top
 * of the user menu that avatar opens (Monthly grant / Remaining).
 *
 * Two states, one geometry:
 *
 *  - Under allowance - neutral ink arc showing the consumed share of the cycle
 *    grant. "2%" on a 10,000-credit plan with 9,779 left.
 *  - Over allowance - carry-over or a PAYG top-up has pushed the wallet above
 *    the cycle grant. The arc turns gold and measures the SURPLUS (capped at
 *    100%, since a dial cannot draw past full).
 *
 * The two states must be distinguishable WITHOUT colour, because they are near
 * opposites: an exhausted wallet and a wallet at double its grant both fill the
 * dial completely. So the ring's glyph differs too - a percentage when credits
 * are being consumed, a bare "+" when the wallet is above its grant. Colour is
 * the reinforcement, never the only signal.
 *
 * The gold itself comes from `--credit-gold-*` in globals.css rather than a
 * literal, because it needs DIFFERENT values per theme: the bright metal that
 * reads on the dark ground sits at 1.8:1 on white and would fail WCAG AA for
 * the "+X% over your plan" sentence the wallet card prints in it.
 *
 * WHERE THE PERCENTAGE IS SPELT OUT. The panel used to carry a horizontal bar
 * repeating the ring beneath the figures, with "X% of your plan used" under it.
 * The menu is a place people pass through, and it was saying the same thing
 * three ways in 200px; the sentence now lives on the wallet card
 * (`BalanceBreakdown`'s `PlanShare`), which is the surface people open to READ
 * their plan - stated there as a REMAINING share, on the same numerator this
 * ring uses, and it is also the only place the gold "+" is spelt out in words.
 * What stays here is what a menu is good at: the two figures, and a dial.
 */

import React, { useEffect, useId, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { usePrefersReducedMotion } from '@/hooks/usePrefersReducedMotion';
// The app's locale-aware Link: it prefixes the URL for the reader's locale and
// prefetches, both of which a hand-rolled anchor would have to redo by hand.
import { Link } from '@/i18n/navigation';
import { formatCreditsCompact } from '@/lib/format-cost';
import type { CreditGauge } from '@/lib/billing/credit-allowance';

/**
 * The one accessible label for every credit trigger.
 *
 * It lived twice, copied verbatim between two credit triggers down to its
 * comments, which is how they drifted apart before (one localised its
 * percentage, the other did not) and how a third surface would drift again.
 *
 * `amountVisible` is the WCAG 2.5.3 "Label in Name" half. An aria-label REPLACES
 * a control's contents for the accessible name, so where the visible content is
 * the compact amount ("9.8K") the name has to contain that exact string, or a
 * speech-input user saying "click 9.8K" cannot activate it. Where the visible
 * content is the ring's own "2%" or gold "+", the sentence already contains it
 * and prefixing the amount would name something nobody can see.
 */
export function useCreditTriggerLabel({
  balance,
  allowance,
  gauge,
  amountVisible,
}: {
  balance: number | null;
  allowance: number | null;
  gauge: CreditGauge;
  amountVisible: boolean;
}): string {
  const t = useTranslations('billing.balance');
  const locale = useLocale();

  // A surplus that rounds to zero must not announce "+0% over your plan", which
  // contradicts the gold state it appears in.
  //
  // Every percentage is formatted for the APP locale before interpolation: a
  // bare ICU `{percent}` given a number is NOT locale-formatted, so a 3,400%
  // surplus reached this string as "3400" while the panel one click away, which
  // does format it, said "3.400" to the same German reader.
  const compact = compactCredits(balance, locale);

  const detail =
    allowance === null
      // The COMPACT amount here, not the grouped one: on this branch the amount
      // is itself the trigger's visible text, so spelling it a second way would
      // put the same number in the name twice, in two different notations.
      ? t('remainingAmount', { amount: compact })
      : gauge.isOver
        ? gauge.overPct === 0
          ? t('overAllowanceTiny')
          : t('overAllowance', { percent: gauge.overPct.toLocaleString(locale) })
        : t('usedPercent', { percent: gauge.fillPct.toLocaleString(locale) });

  // The no-allowance sentence already opens with the visible string, so
  // prefixing it again would just repeat it.
  if (!amountVisible || allowance === null) return detail;
  return t('triggerLabel', { amount: compact, detail });
}

/**
 * The badge's amount. Two regimes on purpose:
 *
 * At or above 1,000 the shared {@link formatCreditsCompact} abbreviation is what
 * fits ("9.8K" / "9,8K"), and the K is a unit rather than part of the number.
 *
 * Below it the value is a plain count, and the shared formatter is wrong twice:
 * it pads a whole balance to "980.0" (noise, right next to a ring) and it renders
 * with `toFixed`, always a DOT, while the panel this badge opens formats for the
 * locale - so a French reader would see "980.4" here and "980,4" one hover away,
 * for the same wallet. A real fraction is never rounded off: it is spendable.
 */
export function compactCredits(value: number | null | undefined, locale: string): string {
  if (value === null || value === undefined) return formatCreditsCompact(value, locale);
  // Sign handled here, magnitude delegated: formatCreditsCompact's K/M thresholds
  // are unsigned, so a delinquent -5,000 balance came back "-5000.0" - unabbreviated
  // and wider than the badge it has to fit in.
  //
  // Both branches spell the number for the app locale. The abbreviated one used
  // not to: `toFixed` always emits a dot, so a German reader got "9.8K" - which
  // in German reads as nine thousand eight hundred - beside a panel row saying
  // "9.779" for the same wallet. That was fixed in the shared formatter rather
  // than here, because all three of its call sites are billing surfaces and two
  // of them sit on the same page as this one.
  const sign = value < 0 ? '-' : '';
  const magnitude = Math.abs(value);
  if (magnitude >= 1_000) return sign + formatCreditsCompact(magnitude, locale);
  return exactCredits(value, locale);
}

/**
 * A credit count for the panel's own rows: grouped for the app locale, and
 * carrying at most one decimal so it agrees with the compact badge beside it
 * (980.4 must not read "980" here and "980.4" there). Never rounds a fraction
 * away to a whole number, which would overstate a spendable balance.
 */
function exactCredits(value: number, locale: string): string {
  return value.toLocaleString(locale, { maximumFractionDigits: 1 });
}

/**
 * Circular progress dial. Renders its state inside when there is room (>= 26px),
 * which is what makes it self-explanatory in a crowded bar.
 *
 * `percent` is already clamped by the caller's gauge; clamped again here so a
 * bad prop can never draw an arc longer than the circle.
 */
export function CreditRing({
  percent,
  size = 28,
  strokeWidth = 2.5,
  gold = false,
  showLabel = true,
}: {
  percent: number;
  size?: number;
  strokeWidth?: number;
  gold?: boolean;
  showLabel?: boolean;
}) {
  const safePercent = Math.max(0, Math.min(100, Number.isFinite(percent) ? percent : 0));
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const dash = (safePercent / 100) * circumference;

  return (
    <span
      className="relative inline-flex items-center justify-center"
      style={{ width: size, height: size }}
    >
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="-rotate-90" aria-hidden="true">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth={strokeWidth}
          className={gold ? undefined : 'stroke-black/15 dark:stroke-white/20'}
          stroke={gold ? 'var(--credit-gold-arc-track)' : undefined}
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth={strokeWidth}
          strokeLinecap="round"
          strokeDasharray={`${dash} ${circumference}`}
          className={gold ? undefined : 'stroke-black dark:stroke-white'}
          stroke={gold ? 'var(--credit-gold-arc)' : undefined}
        />
      </svg>
      {/* The percentage needs ~26px to be legible, but the gold state's single
          "+" does not - and dropping it at badge size is what would make gold
          the ONLY signal on the sidebar, for the one reader who cannot use it.
          This is why the menu panel's 22px dial leaves `showLabel` at its
          default: below 26px the ink percentage is dropped and only the gold
          "+" survives, which is exactly the asymmetry wanted. */}
      {showLabel && (gold || size >= 26) && (
        <span
          data-testid="credit-ring-label"
          className="absolute inset-0 flex items-center justify-center font-medium tabular-nums leading-none"
          style={{
            // Three regimes, set by how many glyphs have to fit the ring's hole.
            // The gold "+" is one glyph and reads largest; an ordinary "42%" is
            // three; only "100%" is four and has to step down, and it does so as
            // little as it can - 0.25 put it at 7px, which is the least legible
            // label on the dial in the state that matters most (an empty wallet).
            // It never has to carry the meaning alone: the trigger's aria-label
            // and title state the whole sentence.
            // Floored at 8px. At the rail's 14px ring the gold ratio resolved
            // to 7px - exactly the size the note above rejects for "100%" as
            // the least legible label on the dial. A "+" is one glyph and has
            // room to be bigger, so the floor costs it nothing.
            fontSize: Math.max(8, Math.round(size * (gold ? 0.5 : Math.round(safePercent) >= 100 ? 0.29 : 0.32))),
            color: gold ? 'var(--credit-gold-ink)' : undefined,
          }}
        >
          {/* Deliberately NOT the percentage in the gold state: an ink "100%"
              means the wallet is empty and a gold "100%" would mean it holds
              double its grant. Same glyphs, opposite meanings, separated only by
              a hue - unreadable for anyone who cannot resolve it. The exact
              surplus is on the wallet card. */}
          {gold ? '+' : `${Math.round(safePercent)}%`}
        </span>
      )}
    </span>
  );
}

/**
 * The wallet state drawn AROUND the user's avatar.
 *
 * This is the sidebar's whole indicator: there is no number beside it and
 * nothing in the top bar, so the ring carries the state on its own and is
 * always present - discreet while inside the grant, gold above it. The exact
 * figures live one click away, in the menu the avatar opens.
 *
 * It fills its positioned parent, which is sized to the avatar PLUS the gap,
 * so the ring lives inside normal layout instead of spilling out of it. The
 * earlier version pulled itself out with negative offsets, and the consequence
 * was that no padding on the surrounding button could ever contain it.
 *
 * `pointer-events-none` so it never intercepts the click that opens the user
 * menu underneath.
 *
 * `gap` is the breathing room between the avatar's edge and the ring; the SVG
 * is grown by twice that and pulled back by it on every side, which keeps the
 * ring concentric whatever size the avatar is. At 5px the ring reads as a
 * separate object orbiting the avatar rather than a border drawn on it, which
 * is the whole difference between "the user has a ring" and "the user's photo
 * has a coloured edge".
 *
 * IT DRAWS ITSELF ON ARRIVAL, in two stages, and the order is the point.
 *
 * First the TRACK draws itself all the way round the avatar; then the arc
 * sweeps out to the wallet's position. The reveal has to be the track's,
 * because the arc measures credits CONSUMED and a healthy account has consumed
 * almost nothing: animating a 2% arc from zero travels two percent of a circle,
 * which nobody sees. The full circle is the part that says "there is a gauge
 * here", and it is the same length for everybody.
 *
 * The arc's own travel is not decoration either: an arc caught moving reads as
 * a quantity being measured, where the same arc already at rest reads as a
 * border drawn on a photo. It follows the track rather than racing it, so the
 * eye reads one gesture (a dial appearing, then filling) instead of two things
 * happening at once.
 *
 * It plays once per visit, and `animate={false}` is how a caller says so. It
 * cannot be decided in here: `AppSidebar` mounts this from both arms of its
 * collapsed/expanded ternary, so a sidebar toggle destroys any state the ring
 * held and the reveal would replay on every toggle. `SidebarCreditRing` keeps
 * that memory for the page load (see `lib/billing/credit-ring-reveal`).
 *
 * AFTER the reveal, the same mechanism carries a LATER change in the balance -
 * a refetch on window focus, credits spent while the app is open - but on its
 * own timing: a short glide with no delay, because the staggered opening delay
 * would leave the arc sitting still for 620ms before acknowledging a number
 * that already changed.
 */
/*
 * The reveal's timings, named because they are relative to each other rather
 * than independent: the arc's delay is DERIVED, so that it starts just before
 * the track lands (the overlap is what makes the two stages read as one
 * gesture), and the whole thing has to stay short enough to be over before a
 * reader has finished arriving. 1,300ms end to end.
 */
const START_DELAY_MS = 120; // let the sidebar paint first, so the ring is what moves
const TRACK_MS = 620;
const ARC_OVERLAP_MS = 120;
const ARC_DELAY_MS = START_DELAY_MS + TRACK_MS - ARC_OVERLAP_MS;
const ARC_MS = 680;
const REVEAL_MS = ARC_DELAY_MS + ARC_MS;
/** What carries a later change in the balance, once the reveal is behind us. */
const UPDATE_MS = 420;
const EASE = 'cubic-bezier(0.16, 1, 0.3, 1)';

export function CreditAvatarRing({
  percent,
  gold = false,
  avatarSize,
  gap = 5,
  strokeWidth = 2,
  animate = true,
}: {
  percent: number;
  gold?: boolean;
  avatarSize: number;
  gap?: number;
  strokeWidth?: number;
  /**
   * False once the reveal has been seen this visit: draw the answer, no travel.
   *
   * Read at MOUNT only, and deliberately so - it seeds the phase and nothing
   * watches it afterwards. Flipping it on a live ring would either restart a
   * reveal halfway through the reader's attention or abort one mid-travel, and
   * neither is a thing any caller wants. Its only caller fixes it for the
   * lifetime of the mount too.
   */
  animate?: boolean;
}) {
  const safePercent = Math.max(0, Math.min(100, Number.isFinite(percent) ? percent : 0));
  const size = avatarSize + gap * 2;
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const dash = (safePercent / 100) * circumference;

  const reduceMotion = usePrefersReducedMotion();

  /*
   * Three phases, because two of them need different timings and the third has
   * to exist before either can be painted.
   *
   *   'hidden'  - the arc is retracted and the track undrawn. One frame only.
   *   'opening' - the staggered reveal is running.
   *   'settled' - the reveal is behind us; a later change glides on UPDATE_MS.
   *
   * A ring told not to animate starts SETTLED, which is also the shape that
   * makes `animate={false}` free of any flicker: nothing is ever retracted.
   */
  const [phase, setPhase] = useState<'hidden' | 'opening' | 'settled'>(
    animate ? 'hidden' : 'settled',
  );
  const drawn = phase !== 'hidden';

  /*
   * TWO frames, not one, and never a first-render value.
   *
   * The browser only animates a property it has already painted at its starting
   * value. A single `requestAnimationFrame` runs BEFORE that paint in some
   * engines, so the offset would change in the same frame it was first set and
   * the transition would have nothing to travel from - the ring would simply
   * appear at its final position, which is the bug this whole block exists to
   * avoid. The second frame is the reliable "the empty ring is on screen" signal.
   *
   * Reduced motion is handled by dropping the TRANSITION, not by starting at the
   * final value: `usePrefersReducedMotion` answers `false` on the server, so a
   * reader with the setting on would get one tree from the server and another on
   * hydration. Starting empty for both and letting one of them skip the travel
   * keeps the two renders identical. (Its only caller cannot server-render the
   * ring today - the wallet is still loading at that point - so this is the
   * shape being kept rather than a bug being fixed, and it costs one frame.)
   */
  useEffect(() => {
    if (phase !== 'hidden') return;
    let second = 0;
    const first = requestAnimationFrame(() => {
      second = requestAnimationFrame(() => setPhase('opening'));
    });
    return () => {
      cancelAnimationFrame(first);
      cancelAnimationFrame(second);
    };
    // Mount only: `phase` is what this drives, and re-running on it would
    // schedule a second reveal the moment the first one starts.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /*
   * Hand over to the settled timings once the reveal has run its length. Timed
   * rather than driven by `transitionend`, which fires per property and does not
   * fire at all for a ring that was already at its position (0%, or a reader on
   * reduced motion), leaving those two stuck on the opening timings forever.
   */
  useEffect(() => {
    if (phase !== 'opening') return;
    const done = setTimeout(() => setPhase('settled'), reduceMotion ? 0 : REVEAL_MS);
    return () => clearTimeout(done);
  }, [phase, reduceMotion]);

  // Colons are stripped: React's useId emits ":r0:", and a fragment reference
  // carrying them (`url(#:r0:)`) is not reliably resolved.
  const gradientId = `credit-gold-${useId().replace(/:/g, '')}`;

  return (
    <svg
      width={size}
      height={size}
      viewBox={`0 0 ${size} ${size}`}
      aria-hidden="true"
      className="pointer-events-none absolute inset-0 m-auto -rotate-90"
    >
      {/* The gold arc is stroked with a metallic sweep rather than one colour.
          A flat gold cannot work on a white card: every hue bright enough to
          look like metal falls under the 3:1 a graphical object needs, so a
          single value gets forced down into bronze. Splitting it into deep,
          bright and mid stops keeps the legibility in the deep end and puts the
          shine where light would actually land on a curved surface. */}
      {gold && (
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="var(--credit-gold-metal-1)" />
            <stop offset="55%" stopColor="var(--credit-gold-metal-2)" />
            <stop offset="100%" stopColor="var(--credit-gold-metal-3)" />
          </linearGradient>
        </defs>
      )}
      <circle
        data-testid="credit-avatar-ring-track"
        cx={size / 2}
        cy={size / 2}
        r={radius}
        fill="none"
        strokeWidth={strokeWidth}
        /* Stage one: the circle draws itself round the avatar. Same dash
           technique as the arc below, so the two stages are one mechanism with
           two timings rather than two ways of doing the same thing. */
        strokeDasharray={circumference}
        strokeDashoffset={drawn ? 0 : circumference}
        style={{
          transition:
            reduceMotion || phase !== 'opening'
              ? undefined
              : `stroke-dashoffset ${TRACK_MS}ms ${EASE} ${START_DELAY_MS}ms`,
        }}
        className={gold ? undefined : 'stroke-black/12 dark:stroke-white/15'}
        stroke={gold ? 'var(--credit-gold-arc-track)' : undefined}
      />
      <circle
        data-testid="credit-avatar-ring-arc"
        cx={size / 2}
        cy={size / 2}
        r={radius}
        fill="none"
        strokeWidth={strokeWidth}
        strokeLinecap="round"
        /* The dash pattern is the WHOLE circle and the offset is what moves.
           Animating `stroke-dasharray` instead would have to interpolate a
           two-value list, which engines do inconsistently; one length is a
           number, and a number is what a transition is good at. */
        strokeDasharray={circumference}
        strokeDashoffset={drawn ? circumference - dash : circumference}
        style={{
          transition:
            // No transition at all on the hidden frame. It has nothing to
            // travel from yet, and advertising the settled timing there made
            // the retracted frame indistinguishable from the finished one to
            // anything reading the style.
            reduceMotion || phase === 'hidden'
              ? undefined
              : phase === 'opening'
              ? // Stage two, starting just BEFORE the track lands: a hard handover
                // reads as two separate animations, a small overlap as one gesture.
                // The ease decelerates hardest at the end, so the arc settles onto
                // its figure rather than stopping dead on it.
                `stroke-dashoffset ${ARC_MS}ms ${EASE} ${ARC_DELAY_MS}ms`
              : // Settled: a later balance still glides rather than jumping, but
                // with no delay - the opening's 620ms stagger would leave the arc
                // motionless for two thirds of a second after a number changed.
                `stroke-dashoffset ${UPDATE_MS}ms ${EASE}`,
        }}
        className={gold ? undefined : 'stroke-black/55 dark:stroke-white/70'}
        stroke={gold ? `url(#${gradientId})` : undefined}
      />
    </svg>
  );
}

/**
 * The balance card body, written once so that any surface showing this wallet
 * describes it identically.
 *
 * Both actions are optional, and a caller that omits one gets no control at all
 * rather than an inert one. The sidebar menu, the only production caller, wires
 * both: it is the sole Upgrade CTA in the cloud chrome, and the sole route to
 * the usage page from that menu.
 *
 * `viewUsage` makes the READOUT itself - the figures - that route, rather than
 * adding a "View usage" link underneath it. The figures are what a reader points
 * at when they want to know where the credits went, so they are the thing that
 * should answer; a separate text link below them said the same thing a second
 * time, in a place nobody aimed at.
 *
 * A null `allowance` is a real state, not an error: the payer is someone else,
 * or the billing payload did not load. Monthly grant and the header dial simply
 * do not render; Remaining does, because it is still true.
 */
export function CreditBalancePanel({
  balance,
  allowance,
  gauge,
  subBalance = null,
  paygBalance = null,
  onUpgrade,
  viewUsage,
}: {
  balance: number | null;
  allowance: number | null;
  gauge: CreditGauge;
  /** Renewal-grant bucket - shown only alongside a non-zero top-up bucket. */
  subBalance?: number | null;
  /** Top-up bucket. A non-zero one is usually WHY the ring went gold. */
  paygBalance?: number | null;
  onUpgrade?: () => void;
  /**
   * Where the readout leads, as BOTH halves of a link: `href` is what the
   * browser needs (new tab, copy link address, the status bar), `onNavigate`
   * is what the app does on an ordinary click (client-side route + close the
   * menu). One object rather than two props so a caller cannot wire half a
   * link and get a control that looks navigable and is not.
   */
  viewUsage?: { href: string; onNavigate: () => void };
}) {
  const t = useTranslations('billing.balance');
  const tPayg = useTranslations('billing.payg');
  const locale = useLocale();
  // The split is only worth the two extra rows when a top-up actually exists:
  // otherwise "Subscription" would simply restate Remaining. This is also the
  // answer to the gold ring - a wallet above its grant is normally a top-up.
  const showBuckets = subBalance !== null && paygBalance !== null && paygBalance > 0;

  return (
    <div className="w-full">
      <div className="flex items-center justify-between gap-3 mb-4">
        <div className="flex items-center gap-2.5 min-w-0">
          {/* `showLabel` is left at its default here. At 22px it draws NOTHING in
              the ink state (a percentage needs 26px to be legible) and the gold
              "+" in the over state. That asymmetry is the point: with the
              labelled bar gone, this glyph is the only thing on any rendered
              surface separating "over your plan" from "plan exhausted" without
              asking the reader to resolve a hue. The exact figure is on the
              wallet card and in the ring's accessible name. */}
          {allowance !== null && (
            <CreditRing percent={gauge.fillPct} size={22} strokeWidth={2} gold={gauge.isOver} />
          )}
          <span className="text-base font-semibold text-theme-primary truncate">
            {t('title')}
          </span>
        </div>
        {onUpgrade && (
          <button
            type="button"
            onClick={onUpgrade}
            data-testid="balance-upgrade"
            className="flex-shrink-0 text-sm px-3 py-1.5 rounded-lg bg-[var(--accent-primary)] text-[var(--accent-foreground)] hover:bg-[var(--accent-hover)] font-medium transition-colors cursor-pointer"
          >
            {t('upgrade')}
          </button>
        )}
      </div>

      <ReadoutFrame viewUsage={viewUsage} actionLabel={t('viewUsage')}>
        <div className="space-y-1.5">
          {allowance !== null && (
            <div className="flex items-start justify-between gap-4 text-sm">
              {/* The number never breaks; the LABEL is what gives way, and it
                  wraps rather than truncating. Truncation was wrong here: the
                  sidebar card is only 228px of content, so German "Monatliches
                  Guthaben" beside "10.000 Credits" already overflows at the
                  ORDINARY tier - it would have ellipsised to "Monatliches ..."
                  for every German user, with no title to recover the text from.
                  Wrapping costs a line and stays readable. `items-start` so the
                  two sides still align on the first line when it does wrap. */}
              <span className="text-theme-secondary min-w-0" data-testid="balance-total-label">
                {t('total')}
              </span>
              <span className="font-semibold text-theme-primary tabular-nums whitespace-nowrap">
                {t('creditsAmount', { amount: allowance.toLocaleString(locale) })}
              </span>
            </div>
          )}
          {/* Same shape as the row above: the number holds, the label wraps.
              Leaving the two adjacent rows to behave differently under identical
              pressure is how one of them ends up looking broken. */}
          <div className="flex items-start justify-between gap-4 text-sm">
            <span className="text-theme-secondary min-w-0" data-testid="balance-remaining-label">{t('remaining')}</span>
            <span className="font-semibold text-theme-primary tabular-nums whitespace-nowrap" data-testid="balance-remaining">
              {balance === null ? '-' : exactCredits(balance, locale)}
            </span>
          </div>
        </div>
      </ReadoutFrame>

      {/* Outside the link on purpose: its own section below a rule, and the
          link's hover surface stopping at the rule is what tells a reader where
          the clickable readout ends. */}
      {showBuckets && (
        <div className="mt-3 pt-3 border-t border-theme space-y-1.5">
          <div className="flex items-baseline justify-between gap-4 text-xs">
            <span className="text-theme-muted">{tPayg('breakdown.sub')}</span>
            <span className="text-theme-secondary tabular-nums" data-testid="bucket-sub">
              {compactCredits(subBalance, locale)}
            </span>
          </div>
          <div className="flex items-baseline justify-between gap-4 text-xs">
            <span className="text-theme-muted">{tPayg('breakdown.payg')}</span>
            <span className="text-theme-secondary tabular-nums" data-testid="bucket-payg">
              {compactCredits(paygBalance, locale)}
            </span>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * The figures, made the route to the usage page when one is wired.
 *
 * A real link, not a div with `role="link"`: an anchor's content model is
 * transparent, so it may legally wrap these block rows, and it brings what a
 * hand-rolled control cannot - Enter activation, middle-click and cmd-click
 * into a new tab, "copy link address", and a place in a screen reader's link
 * list. That matters here more than usual: this readout is the ONLY route to
 * the usage page from the cloud user menu.
 *
 * It is the app's own `Link`, so the href carries the reader's locale (an
 * unprefixed /app URL is redirected to a hardcoded /en, which would open the
 * English page in the new tab of a French reader). The plain click is still
 * handled here rather than left to the router: that is the path carrying the
 * app's navigation guard, the progress bar, and closing the menu behind it.
 *
 * A plain wrapper when nothing is wired, so a panel with no `viewUsage` keeps
 * exactly the markup it had - no link, no focus stop, no hover surface.
 *
 * The action's name is visually-hidden text INSIDE the link rather than an
 * `aria-label`, because a label REPLACES the contents for the accessible name:
 * "View usage" alone would drop the figures, which are the only thing a
 * sighted user can point at or say. This way the name carries both the action
 * and what it is about. No `title` either - it would be announced on top of a
 * name that already says it, and pop a tooltip over the numbers on any hover.
 */
function ReadoutFrame({
  viewUsage,
  actionLabel,
  children,
}: {
  viewUsage?: { href: string; onNavigate: () => void };
  actionLabel: string;
  children: React.ReactNode;
}) {
  if (!viewUsage) return <>{children}</>;

  return (
    <Link
      href={viewUsage.href}
      data-testid="balance-view-usage"
      // Dragging across the figures must SELECT them, not pick the link up:
      // these are numbers people copy, and a link is draggable by default.
      draggable={false}
      // The panel's home is a portalled menu, where events bubble along the
      // REACT tree rather than the DOM one. Every control in that menu stops
      // propagation, and this one keeps the habit: whatever the panel is
      // dropped into later must not receive this click as its own.
      onClick={(e) => {
        e.stopPropagation();
        // Let the browser have the gestures that are its: a new tab, a new
        // window, a download. Only the plain click is ours to intercept.
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
        e.preventDefault();
        // These are figures people copy. A drag that ends inside the link still
        // fires a click, so selecting the balance would otherwise navigate away
        // with the number still highlighted.
        //
        // Scoped three ways, because a link that silently does nothing is worse
        // than one that navigates while text is selected. It bails only when the
        // selection STARTED inside this link (a select-all covers it too, and
        // that reader is not trying to copy the balance), and only for pointer
        // clicks (`detail` is 0 for a keyboard activation, where no drag can
        // have happened).
        const selection = window.getSelection();
        const draggedHere =
          e.detail !== 0 &&
          selection !== null &&
          !selection.isCollapsed &&
          e.currentTarget.contains(selection.anchorNode);
        if (draggedHere) return;
        viewUsage.onNavigate();
      }}
      // Negative margins so the hover surface can breathe without moving the
      // figures a pixel from where they sit in the non-linked version. The
      // horizontal pair cancels the section's own `px-1.5`, which is what puts
      // the rectangle's edges on the menu rows' edges instead of past them.
      className="block -mx-1.5 px-1.5 -my-1 py-1 rounded-xl transition-colors hover:bg-gray-100 dark:hover:bg-gray-800 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-1 focus-visible:ring-offset-[var(--bg-primary)]"
    >
      <span className="sr-only">{actionLabel}</span>
      {children}
    </Link>
  );
}

/**
 * Compact "ring + amount" trigger used inside the sidebar user block, and the
 * fallback wherever no gauge is possible.
 *
 * With `allowance === null` the ring is DROPPED rather than drawn empty: a 0%
 * dial is a statement about the account, and the whole point of a null allowance
 * is that we cannot make one.
 */
export function CreditRingBadge({
  balance,
  gauge,
  hasAllowance,
  compact = false,
}: {
  balance: number | null;
  gauge: CreditGauge;
  hasAllowance: boolean;
  compact?: boolean;
}) {
  const locale = useLocale();
  return (
    <span className="flex items-center gap-1">
      {/* The glyph is suppressed at this size EXCEPT in the gold state, where it
          is a single "+" and is the only non-colour signal separating "40%
          consumed" from "+40% over" on this surface.
          KNOWN TRADE-OFF: this badge pairs a ring filled by credits CONSUMED
          with a number that is credits REMAINING, so a healthy wallet shows a
          nearly-empty ring beside "9.8K". The panel's doc block argues each bar
          should agree with its adjacent number, and this is the one surface
          where it cannot: a 64px rail has no room for a sentence, and
          flipping the ring would put it in contradiction with the dial in the
          menu panel, which is the same widget. That panel resolves it in one
          click and the trigger's aria-label states it outright. */}
      {hasAllowance && (
        <CreditRing
          percent={gauge.fillPct}
          size={compact ? 14 : 16}
          strokeWidth={2}
          gold={gauge.isOver}
          showLabel={gauge.isOver}
        />
      )}
      {/* 10px in the rail: the same size the CE badge this replaces uses in that
          slot (AppSidebar's collapsed user block), because a 12px amount beside a
          14px ring plus a 12px amount is a tight fit in the 64px rail
          (`md:w-16`, less its padding). text-xs everywhere else. */}
      <span className={compact ? 'text-[10px] tabular-nums' : 'text-xs tabular-nums'}>
        {compactCredits(balance, locale)}
      </span>
    </span>
  );
}
