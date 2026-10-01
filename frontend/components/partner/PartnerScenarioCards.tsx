import Link from 'next/link';
import { Briefcase, Building2, Gem, type LucideIcon } from 'lucide-react';

export interface Scenario {
  key: 'single' | 'agency' | 'large';
  persona: string;
  /** "30 clients on Pro with 250K credits". */
  clients: string;
  /** "$209 a month per client": the real list price of that plan and those credits. */
  bill: string;
  /** Where that price can be checked: the pricing page, with its hidden tiers shown when needed. */
  pricingHref: string;
  /** "$3,135", formatted. */
  perMonth: string;
  /** "$37,620 a year". */
  perYear: string;
}

const ICONS: Record<Scenario['key'], LucideIcon> = { single: Gem, agency: Briefcase, large: Building2 };

/**
 * Three ready-made answers to "how much would I make?", each built on a real plan and credit
 * tier: one large client, an agency, a large agency. The plan it comes from right under the
 * persona, the monthly figure large, the year below. The largest is featured.
 */
export function PartnerScenarioCards({ scenarios, upTo, perMonth }: { scenarios: Scenario[]; upTo: string; perMonth: string }) {
  return (
    <ul className="mt-12 grid grid-cols-1 gap-6 md:grid-cols-3" data-testid="partner-scenarios">
      {scenarios.map((s) => {
        const Icon = ICONS[s.key];
        const featured = s.key === 'large';
        const muted = featured ? 'rgba(255,255,255,0.6)' : 'var(--text-muted)';
        return (
          <li
            key={s.key}
            className={`relative flex flex-col overflow-hidden rounded-3xl p-7 ${featured ? 'md:-translate-y-2' : ''}`}
            style={featured
              ? { background: '#0b0d12', color: '#fff', boxShadow: '0 40px 90px -40px rgba(242,182,64,0.6)' }
              : { background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)', color: 'var(--text-primary)' }}
            data-scenario={s.key}
          >
            {featured && (
              <div aria-hidden className="absolute -right-16 -top-16 h-48 w-48 rounded-full" style={{ background: 'radial-gradient(circle, rgba(242,182,64,0.35), transparent 70%)' }} />
            )}
            <div className="relative flex items-center gap-3">
              <span
                className="grid h-10 w-10 shrink-0 place-items-center rounded-2xl"
                style={featured ? { background: 'rgba(242,182,64,0.15)', color: '#f2b640' } : { background: 'var(--bg-secondary)', color: '#d99a1e' }}
                aria-hidden
              >
                <Icon className="h-5 w-5" />
              </span>
              <div className="text-base font-semibold">{s.persona}</div>
            </div>
            <div className="relative mt-4 text-sm" style={{ color: featured ? 'rgba(255,255,255,0.85)' : 'var(--text-secondary)' }}>{s.clients}</div>
            <Link href={s.pricingHref} className="relative text-sm underline underline-offset-2" style={{ color: muted }} data-testid="partner-scenario-bill">
              {s.bill}
            </Link>
            <div className="relative mt-auto pt-8 text-sm" style={{ color: muted }}>{upTo}</div>
            <div className="relative flex flex-wrap items-baseline gap-x-2">
              <span className="text-4xl md:text-2xl lg:text-4xl xl:text-5xl font-bold tracking-tight tabular-nums" style={featured ? { color: '#f2b640' } : undefined}>{s.perMonth}</span>
              <span className="text-base" style={{ color: muted }}>{perMonth}</span>
            </div>
            <div className="relative mt-2 text-base font-medium">{s.perYear}</div>
          </li>
        );
      })}
    </ul>
  );
}

export default PartnerScenarioCards;
