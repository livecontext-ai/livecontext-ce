import { FileText } from 'lucide-react';
import { wholeMoney } from '@/lib/partners/tiers';

export interface ExampleAppScreenCopy {
  appTitle: string;
  /** The status pill of the running app: "Running". */
  running: string;
  /** "Invoices this month". */
  processed: string;
  /** "Booked in QuickBooks". */
  booked: string;
  statusBooked: string;
  statusReview: string;
}

/** The supplier invoices the screen lists: names are fictional brands, amounts in USD. */
const INVOICES = [
  { supplier: 'Northstar Hosting', initial: 'N', tint: '#4f46e5', amount: 1240, booked: true },
  { supplier: 'Atelier Martin', initial: 'A', tint: '#0d9488', amount: 86, booked: true },
  { supplier: 'Cloudway CRM', initial: 'C', tint: '#0284c7', amount: 420, booked: true },
  { supplier: 'Brightline Ads', initial: 'B', tint: '#db2777', amount: 2310, booked: false },
] as const;

const PROCESSED = 128;
const BOOKED_TOTAL = 48920;

/**
 * The screen of the /partners page's fictional app, "Invoice Autopilot", drawn as the cover of
 * its marketplace card: what a real listing shows when its publisher froze a showcase. A picture
 * of an app (two counters, the latest invoices and their state), hook-free so it renders on the
 * provider-less server page; every word arrives through `copy`, amounts in the reader's locale.
 *
 * <p>Sized in `em` on a font tied to the card's width (`cqw`): the card is 340px wide on a
 * desktop and 240px on a phone, and a picture must keep its proportions at both, where fixed
 * text sizes cut the last rows and truncate every name.
 */
export function PartnerExampleAppScreen({ copy, locale }: { copy: ExampleAppScreenCopy; locale: string }) {
  const money = (major: number) => wholeMoney(major, 'usd', locale);
  return (
    <div aria-hidden className="absolute inset-0" style={{ containerType: 'inline-size' }} data-testid="partner-example-app-screen">
      <div
        className="flex h-full flex-col gap-[0.6em] overflow-hidden p-[0.9em] leading-tight"
        style={{ fontSize: '3.9cqw', background: 'var(--bg-primary)', color: 'var(--text-primary)' }}
      >
        <div className="flex items-center gap-[0.5em]">
          <span className="grid h-[1.6em] w-[1.6em] shrink-0 place-items-center rounded-[0.4em]" style={{ background: 'linear-gradient(135deg, #6366f1, #4338ca)', color: '#fff' }}>
            <FileText className="h-[1em] w-[1em]" />
          </span>
          <span className="truncate font-semibold">{copy.appTitle}</span>
          <span className="ml-auto inline-flex shrink-0 items-center gap-[0.35em] rounded-full bg-emerald-500/10 px-[0.6em] py-[0.15em] font-medium text-emerald-700 dark:text-emerald-400">
            <span className="h-[0.45em] w-[0.45em] rounded-full bg-emerald-500" />
            {copy.running}
          </span>
        </div>

        <div className="grid grid-cols-2 gap-[0.5em]">
          {[
            { label: copy.processed, value: PROCESSED.toLocaleString(locale) },
            { label: copy.booked, value: money(BOOKED_TOTAL) },
          ].map((kpi) => (
            <div key={kpi.label} className="min-w-0 rounded-[0.6em] px-[0.7em] py-[0.45em]" style={{ background: 'var(--bg-secondary)' }}>
              <div className="truncate" style={{ color: 'var(--text-muted)' }}>{kpi.label}</div>
              <div className="mt-[0.15em] text-[1.2em] font-semibold tabular-nums">{kpi.value}</div>
            </div>
          ))}
        </div>

        <ul className="flex flex-col gap-[0.35em]">
          {INVOICES.map((inv) => (
            <li key={inv.supplier} className="flex items-center gap-[0.5em]">
              <span className="grid h-[1.35em] w-[1.35em] shrink-0 place-items-center rounded-full text-[0.8em] font-semibold" style={{ background: inv.tint, color: '#fff' }}>
                {inv.initial}
              </span>
              <span className="min-w-0 flex-1 truncate">{inv.supplier}</span>
              <span className="shrink-0 font-medium tabular-nums">{money(inv.amount)}</span>
              <span
                className={inv.booked
                  ? 'shrink-0 rounded-full bg-emerald-500/10 px-[0.5em] font-medium text-emerald-700 dark:text-emerald-400'
                  : 'shrink-0 rounded-full bg-amber-500/10 px-[0.5em] font-medium text-amber-700 dark:text-amber-400'}
              >
                {inv.booked ? copy.statusBooked : copy.statusReview}
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

export default PartnerExampleAppScreen;
