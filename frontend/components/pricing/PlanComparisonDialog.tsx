'use client';

import * as React from 'react';
// The locale-aware Link: the comparison is opened from the /fr landing as
// well as from inside the app, and a bare next/link would send a French
// reader to the English plans page.
import { Link } from '@/i18n/navigation';
import { Check, Minus } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import FeatureLabel from '@/components/pricing/FeatureLabel';
import ReferencePrice from '@/components/pricing/ReferencePrice';
import { usePricingEvent } from '@/hooks/usePricingEvent';
import { calcPrice, creditFactsFor, CREDIT_TIERS, FREE_AI_CREDITS } from '@/lib/billing/pricing-constants';
import {
  buildPlanComparison,
  COMPARISON_PLAN_IDS,
  resolveComparisonPlanId,
  resolveRequiredPlanId,
  type ComparisonPlanId,
  type ComparisonRow,
} from '@/lib/billing/plan-comparison';
import {
  PLAN_COMPARISON_EVENT,
  type PlanComparisonRequest,
} from '@/lib/billing/plan-comparison-open';
import { cn } from '@/lib/utils';

/**
 * The whole plan grid, side by side, opened by {@link openPlanComparison}.
 *
 * <p><b>Where it opens from.</b> The pricing page's "Compare plans" button and
 * the public landing's pricing section, and nowhere else. It used to be wired
 * into every upsell in the app - the credits and storage modals, the workspace
 * and teammate gates, the model pickers, a gated node in the builder, three
 * settings pages, the sidebar's plan name - which put a five-column overlay one
 * stray click away on surfaces a reader passes through all day. Each of those
 * surfaces already links to the pricing page, so the comparison is a hop away
 * from all of them instead of covering the work in progress.
 * `plan-comparison-entry-points.test.ts` keeps that list closed.
 *
 * <p><b>It compares, it does not sell.</b> Every column's action leads to the
 * plans page, which owns checkout, proration, downgrade confirmation, cadence
 * changes and the CE cloud-link path. Reimplementing any of that here would put
 * a second, thinner purchase flow beside the real one.
 *
 * <p><b>The emphasis props.</b> Neither `highlightPlan` (and the "Unlocks this"
 * badge it drives) nor `highlightRow` has a caller today: both existed for the
 * pruned entry points, each of which knew which column or row answered its own
 * restriction. `highlightRow` briefly had one again, a post-onboarding opening
 * on the credits row, and that is exactly the entry point WelcomeGiftModal
 * replaced. They are kept, not deleted, because they are what any entry point
 * added back would need, and `ComparePlansLink` already passes both through.
 *
 * <p><b>It renders above the floating hosts.</b> The composer menu (z-[10000])
 * and its options panel (z-[99999]) paint above an ordinary dialog. Nothing in
 * those hosts opens this any more, but the explicit z stays: it is what makes
 * the surface safe to open from anywhere, which is the property that let it
 * spread in the first place and the one thing worth keeping from that era.
 */

/** The pack every price here is quoted with: the entry tier, same as the landing. */
const TIER_INDEX = 0;

export interface PlanComparisonDialogProps {
  /**
   * The plan that governs this account, marked "current" in the header. Null
   * marks nothing, which is the right answer for a visitor on the landing and
   * for a self-hosted install with no cloud plan governing it.
   */
  currentPlanCode?: string | null;
  /**
   * The Free plan's monthly AI allowance (V494). Defaults to the seeded figure,
   * which is the right answer on the public landing: the live value comes from
   * the plans endpoint, which needs an authenticated query client this surface
   * deliberately does not assume - see the note on {@link AppPlanComparisonDialog}.
   * Inside the app the wrapper passes the configured number, so the pricing card
   * and this table cannot quote two different allowances on the same screen.
   */
  freeAiCredits?: number;
}

export default function PlanComparisonDialog({
  currentPlanCode = null,
  freeAiCredits = FREE_AI_CREDITS,
}: PlanComparisonDialogProps) {
  const [open, setOpen] = React.useState(false);
  const [request, setRequest] = React.useState<PlanComparisonRequest>({});

  React.useEffect(() => {
    const onOpen = (event: Event) => {
      const detail = (event as CustomEvent<PlanComparisonRequest>).detail;
      setRequest(detail ?? {});
      setOpen(true);
    };
    window.addEventListener(PLAN_COMPARISON_EVENT, onOpen);
    return () => window.removeEventListener(PLAN_COMPARISON_EVENT, onOpen);
  }, []);

  // Mounted in a layout, so it exists on every page: nothing is rendered, and no
  // translation or pricing work is done, until someone actually asks for it.
  if (!open) return null;

  return (
    <PlanComparisonBody
      currentPlanCode={currentPlanCode}
      freeAiCredits={freeAiCredits}
      request={request}
      onClose={() => setOpen(false)}
    />
  );
}

function PlanComparisonBody({
  currentPlanCode,
  freeAiCredits,
  request,
  onClose,
}: {
  currentPlanCode: string | null;
  freeAiCredits: number;
  request: PlanComparisonRequest;
  onClose: () => void;
}) {
  const t = useTranslations('pricing.compare');
  const tCards = useTranslations('pricing.planCards');
  const tBilling = useTranslations('pricing.billing');
  const locale = useLocale();
  const { event: pricingEvent } = usePricingEvent();

  // Yearly first, matching the plans page and the landing: a reader comparing
  // two surfaces must not find two different prices for the same plan.
  const [cycle, setCycle] = React.useState<'monthly' | 'yearly'>('yearly');

  const sections = React.useMemo(() => buildPlanComparison(), []);
  const currentPlanId = resolveComparisonPlanId(currentPlanCode);
  const requiredPlanId = resolveRequiredPlanId(request.highlightPlan);
  const highlightRow = request.highlightRow ?? null;

  const entryCredits = CREDIT_TIERS[TIER_INDEX].toLocaleString(locale);
  // The same object the plan cards interpolate their credits tooltip with, so
  // the card and this table cannot describe the same number differently.
  const creditFacts = React.useMemo(() => creditFactsFor(locale), [locale]);

  // The reason the comparison was opened sits at the top of the table, not at
  // the top of the dialog: it belongs to the row it is about.
  const highlightedRowRef = React.useRef<HTMLTableRowElement | null>(null);
  React.useEffect(() => {
    if (!highlightRow) return;
    // After paint, so the row exists and the dialog has settled at its size.
    const raf = requestAnimationFrame(() => {
      highlightedRowRef.current?.scrollIntoView({ block: 'center' });
    });
    return () => cancelAnimationFrame(raf);
  }, [highlightRow]);

  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent
        // Above the composer menu (z-[10000]) and its options panel (z-[99999]),
        // which are the hosts this dialog is opened from.
        className="z-[100000] w-[min(96vw,1180px)] max-w-[1180px] max-h-[88vh] grid-rows-[auto_minmax(0,1fr)] gap-0 overflow-hidden p-0"
        overlayClassName="z-[100000]"
      >
        <header className="flex flex-wrap items-end justify-between gap-4 border-b border-theme px-6 py-5">
          <div className="min-w-0">
            {/* The dialog's accessible name, through Radix's own Title so the
                dialog is announced by it rather than by nothing. */}
            <DialogTitle className="text-base font-semibold text-theme-primary">
              {t('title')}
            </DialogTitle>
            <p className="mt-1 text-sm text-theme-secondary">
              {t('subtitle', { credits: entryCredits })}
            </p>
          </div>
          {/* Same control, same labels as the plans page: the cycle changes the
              header prices only, never which plan includes what. */}
          <div
            role="group"
            aria-label={t('cycleLabel')}
            className="inline-flex shrink-0 items-center gap-1 rounded-xl bg-theme-tertiary p-1 mr-10"
          >
            {(['monthly', 'yearly'] as const).map((option) => (
              <button
                key={option}
                type="button"
                onClick={() => setCycle(option)}
                aria-pressed={cycle === option}
                className={cn(
                  'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors',
                  cycle === option
                    ? 'bg-theme-primary text-theme-primary shadow-sm'
                    : 'text-theme-secondary hover:text-theme-primary'
                )}
              >
                {tBilling(option)}
              </button>
            ))}
          </div>
        </header>

        {/* The table owns the scrolling, in both axes: the dialog itself never
            grows past the viewport and the page behind it never scrolls. */}
        <div className="overflow-auto">
          <table className="w-full min-w-[860px] border-collapse text-sm">
            <caption className="sr-only">{t('title')}</caption>
            {/* Sticky lives on the CELLS, not on <thead>: a sticky thead is not
                honoured by every engine, a sticky th is. */}
            <thead>
              <tr>
                {/* Empty corner: the row labels' own column header. Sticky in
                    both axes, so it holds the crossing point. */}
                <th
                  scope="col"
                  className="sticky left-0 top-0 z-30 w-[24%] min-w-[200px] bg-theme-primary px-6 py-4 text-left align-bottom"
                >
                  <span className="sr-only">{t('featureColumn')}</span>
                </th>
                {COMPARISON_PLAN_IDS.map((planId) => (
                  <PlanHeaderCell
                    key={planId}
                    planId={planId}
                    cycle={cycle}
                    isCurrent={planId === currentPlanId}
                    isRequired={planId === requiredPlanId}
                    pricingEvent={pricingEvent}
                    onNavigate={onClose}
                  />
                ))}
              </tr>
            </thead>

            {sections.map((section) => (
              <tbody key={section.id} className="border-t border-theme">
                <tr>
                  <th
                    scope="colgroup"
                    colSpan={COMPARISON_PLAN_IDS.length + 1}
                    className="bg-theme-secondary px-6 py-2 text-left text-xs font-semibold uppercase tracking-wide text-theme-secondary"
                  >
                    {t(`sections.${section.id}`)}
                  </th>
                </tr>
                {section.rows.map((row) => (
                  <ComparisonRowView
                    key={`${row.kind}:${row.id}`}
                    row={row}
                    currentPlanId={currentPlanId}
                    isHighlighted={row.id === highlightRow}
                    rowRef={row.id === highlightRow ? highlightedRowRef : undefined}
                    entryCredits={entryCredits}
                    freeAiCredits={freeAiCredits}
                    creditFacts={creditFacts}
                  />
                ))}
              </tbody>
            ))}
          </table>

          <p className="px-6 py-4 text-xs text-theme-muted">{t('footnote')}</p>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function PlanHeaderCell({
  planId,
  cycle,
  isCurrent,
  isRequired,
  pricingEvent,
  onNavigate,
}: {
  planId: ComparisonPlanId;
  cycle: 'monthly' | 'yearly';
  isCurrent: boolean;
  isRequired: boolean;
  pricingEvent: React.ComponentProps<typeof ReferencePrice>['event'];
  onNavigate: () => void;
}) {
  const t = useTranslations('pricing.compare');
  const tCards = useTranslations('pricing.planCards');
  const locale = useLocale();

  // Enterprise is quoted, not priced: the grid says so rather than showing a
  // number the sales conversation would contradict.
  const isQuoted = planId === 'enterprise';
  const price = isQuoted ? null : calcPrice(planId, cycle, TIER_INDEX);

  return (
    <th
      scope="col"
      data-testid={`plan-comparison-col-${planId}`}
      className={cn(
        'sticky top-0 z-20 min-w-[132px] px-3 py-4 text-center align-bottom',
        // One background, chosen once: `bg-theme-primary` is a hand-written
        // utility, so twMerge cannot dedupe it against an arbitrary `bg-[...]`
        // and both would ship, leaving CSS layer order to decide the colour.
        isRequired
          ? 'bg-[color-mix(in_srgb,var(--accent-primary)_8%,var(--bg-primary))]'
          : 'bg-theme-primary'
      )}
    >
      <div className="flex flex-col items-center gap-1">
        <span className="text-sm font-semibold text-theme-primary">{tCards(`${planId}.name`)}</span>

        {/* Only the emphasis a caller asked for sits above the price. The
            account's own plan is marked BELOW, in the slot the others use for
            their action: saying "current plan" twice, once as a badge and once
            as a caption, was three words for one fact.
            Both markers are rounded rectangles rather than pills, on the radius
            of their own size: rounded-md up here where the chip is 20px tall,
            rounded-lg below where the marker stands in for a button and takes
            that button's shape and padding exactly. */}
        {isRequired && !isCurrent ? (
          <span className="rounded-md bg-[color-mix(in_srgb,var(--accent-primary)_16%,transparent)] px-2 py-0.5 text-xs font-medium text-[var(--accent-primary)]">
            {t('unlocksBadge')}
          </span>
        ) : null}

        <span className="flex items-baseline gap-1.5">
          <span className="text-base font-semibold text-theme-primary">
            {price === null
              ? tCards('price.contactSales')
              : price === 0
                ? tCards('price.free')
                : `$${price.toLocaleString(locale)}`}
          </span>
          {price !== null && price > 0 ? (
            <span className="text-xs text-theme-muted">{tCards('period')}</span>
          ) : null}
        </span>

        {/* Struck reference price while a founding window is open, exactly as on
            the plan cards: the figure shown large stays the one billed. */}
        <ReferencePrice
          planId={planId}
          cycle={cycle}
          creditTierIndex={TIER_INDEX}
          event={pricingEvent}
          size="sm"
        />

        {isCurrent ? (
          <span className="mt-1 rounded-lg bg-theme-tertiary px-3 py-1.5 text-xs font-medium text-theme-secondary">
            {tCards('badges.current')}
          </span>
        ) : (
          <Link
            href="/app/settings/pricing"
            onClick={onNavigate}
            className="mt-1 rounded-lg border border-theme px-3 py-1.5 text-xs font-medium text-theme-primary transition-colors hover:bg-theme-tertiary"
          >
            {tCards(`${planId}.cta`)}
          </Link>
        )}
      </div>
    </th>
  );
}

function ComparisonRowView({
  row,
  currentPlanId,
  isHighlighted,
  rowRef,
  entryCredits,
  freeAiCredits,
  creditFacts,
}: {
  row: ComparisonRow;
  currentPlanId: ComparisonPlanId | null;
  isHighlighted: boolean;
  rowRef?: React.Ref<HTMLTableRowElement>;
  entryCredits: string;
  freeAiCredits: number;
  creditFacts: Record<string, string | number>;
}) {
  const t = useTranslations('pricing.compare');
  const tCards = useTranslations('pricing.planCards');

  // A scale row is named by its dimension; a flag row is named by the very
  // label the plan cards already use for it, so the two surfaces cannot drift.
  // Either may carry an explanation, through the shared "label||tooltip"
  // convention `FeatureLabel` renders: a cell has room for a value, not for the
  // sentence that qualifies it. A dimension whose cells hide a real subtlety
  // (which nodes a plan excludes, and what it does NOT exclude) says it there.
  const label = React.useMemo(() => {
    // next-intl's own existence check: a key with no explanation costs no
    // thrown-and-caught missing-message error on every render.
    if (row.kind === 'scale') {
      const name = t(`dimensions.${row.id}`);
      const tip = `dimensions.${row.id}Tooltip`;
      // The WHOLE fact set is offered to every dimension tooltip, not only the
      // values used today: a message that ignores one renders unchanged, so a
      // tooltip can start quoting a per-conversation price or the pack size
      // without touching this component. The credits row is the one doing it.
      //
      // `creditFactsFor`, not a hand-listed subset. This used to pass three
      // values by name, and when the credits tooltip was rewritten to quote
      // per-conversation prices instead of dividing the pack, this row started
      // rendering its raw message path and throwing an IntlError on every
      // render, in all six locales - while the plan CARDS, which pass the full
      // set, were fine. One source of facts, or the two surfaces sharing this
      // message diverge silently again.
      return t.has(tip) ? `${name}||${t(tip, creditFacts)}` : name;
    }
    const cardLabel = tCards(`features.${row.id}`);
    const tooltipKey = `features.${row.id}Tooltip`;
    // The credit facts again, for the same reason the scale rows get them: a
    // card tooltip that starts quoting a per-conversation price is edited in the
    // message, and this surface must not be the one that discovers it by
    // throwing a FORMATTING_ERROR and rendering its own key path.
    return tCards.has(tooltipKey) ? `${cardLabel}||${tCards(tooltipKey, creditFacts)}` : cardLabel;
  }, [row.kind, row.id, t, tCards, creditFacts]);

  return (
    <tr
      ref={rowRef}
      data-testid={`plan-comparison-row-${row.id}`}
      className={cn(
        'border-t border-theme',
        isHighlighted && 'bg-[color-mix(in_srgb,var(--accent-primary)_7%,transparent)]'
      )}
    >
      <th
        scope="row"
        className={cn(
          'sticky left-0 z-10 px-6 py-2.5 text-left text-sm font-normal text-theme-secondary',
          // Matches the row's own background so the label stays readable while
          // the plan columns scroll under it.
          isHighlighted
            ? 'bg-[color-mix(in_srgb,var(--accent-primary)_7%,var(--bg-primary))]'
            : 'bg-theme-primary'
        )}
      >
        <FeatureLabel feature={label} />
      </th>

      {COMPARISON_PLAN_IDS.map((planId) => (
        <td
          key={planId}
          className={cn(
            'px-3 py-2.5 text-center text-sm text-theme-primary',
            // `bg-theme-secondary` is a hand-written utility, not a Tailwind
            // colour token, so a `/60` opacity modifier would compile to nothing.
            planId === currentPlanId && 'bg-[color-mix(in_srgb,var(--bg-secondary)_55%,transparent)]'
          )}
        >
          {row.kind === 'scale' ? (
            <ScaleCell
              featureKey={row.cells[planId]}
              entryCredits={entryCredits}
              freeAiCredits={freeAiCredits}
              fallbackValueKey={row.fallbackValueKey}
            />
          ) : (
            <FlagCell included={row.cells[planId]} />
          )}
        </td>
      ))}
    </tr>
  );
}

function ScaleCell({
  featureKey,
  entryCredits,
  freeAiCredits,
  fallbackValueKey,
}: {
  featureKey: string | null;
  entryCredits: string;
  freeAiCredits: number;
  fallbackValueKey?: string;
}) {
  const t = useTranslations('pricing.compare');
  // The APP locale, via next-intl, like every other component here: the figure is
  // rendered server-side on the public landing too, so a /fr reader must get
  // "100" grouped the French way on the first paint and after hydration alike.
  const locale = useLocale();
  if (!featureKey) {
    // A dimension can say what "no key here" MEANS rather than leaving the cross
    // to imply the plan is missing something. The AI allowance is the case: paid
    // plans have no separate pot because their credits already fund agents.
    return fallbackValueKey ? <span>{t(`values.${fallbackValueKey}`)}</span> : <FlagCell included={false} />;
  }
  // The values that are not constants: paid plans quote the pack the reader is
  // currently looking at (chosen on the plans page), and the Free AI allowance
  // quotes whatever the Free plan row carries.
  const value =
    featureKey === 'creditsDynamic'
      ? t('values.creditsDynamic', { credits: entryCredits })
      : featureKey === 'aiCreditsFree'
        // An allowance of zero is how an admin CLOSES the free tier. Printing
        // '0 / month' would advertise a pot nobody has; printing the paid plans'
        // "included in credits" would be worse still, since it says the opposite of
        // what the Free plan's credits do. The honest cell is the same one every other
        // dimension uses for "this plan does not have it".
        ? (freeAiCredits > 0
            ? t('values.aiCreditsFree', { credits: freeAiCredits.toLocaleString(locale) })
            : t('notIncluded'))
        : t(`values.${featureKey}`);

  return <span>{value}</span>;
}

function FlagCell({ included }: { included: boolean }) {
  const t = useTranslations('pricing.compare');

  return included ? (
    <>
      <Check className="mx-auto h-4 w-4 text-emerald-600 dark:text-emerald-400" aria-hidden />
      <span className="sr-only">{t('included')}</span>
    </>
  ) : (
    <>
      <Minus className="mx-auto h-4 w-4 text-theme-muted" aria-hidden />
      <span className="sr-only">{t('notIncluded')}</span>
    </>
  );
}
