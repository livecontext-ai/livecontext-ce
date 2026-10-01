import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { PartnerBadgeIcon } from '@/components/profile/PartnerBadgeIcon';
import { niceCeiling, wholeMoney } from '@/lib/partners/tiers';

/**
 * The hero's partner: their paying clients over twelve months, each on the example plan.
 */
export const HERO_EXAMPLE_CLIENTS = [3, 5, 8, 10, 13, 15, 18, 20, 23, 25, 28, 30] as const;

export interface HeroEarningsCopy {
  name: string;
  role: string;
  thisMonth: string;
  perMonth: string;
  /** "from 30 clients on Pro with 250K credits". */
  fromClients: string;
  yearTotal: string;
  rate: string;
  /** "50% (Platinum)": the rate of the last month, formatted by the page. */
  rateValue: string;
  chartLabel: string;
  /** Month axis labels: first, middle and last. */
  months: [string, string, string];
  badge: string;
}

const W = 420;
const H = 170;
const RIGHT = 8;
/** Roughly the width of one character of an axis label, at the label font size. */
const CHAR_W = 7;
const TOP = 12;
const BOTTOM = 28;

/**
 * The hero's earnings card: what one partner earns per month, month after month, in money, on a
 * chart whose axis is labelled in money. The page computes the months on the real tiers
 * (`estimateMonths`); this only draws them. Server-rendered SVG, no chart library: it is one
 * shape, and it belongs in the first paint.
 */
export function PartnerHeroEarnings({
  copy,
  values,
  avatarSrc,
  currency,
  locale,
}: {
  copy: HeroEarningsCopy;
  /** The commission of each month, major units. */
  values: readonly number[];
  avatarSrc: string;
  currency: string;
  locale: string;
}) {
  if (values.length < 2) return null;
  const last = values[values.length - 1];
  const total = values.reduce((a, b) => a + b, 0);
  const top = niceCeiling(Math.max(...values));
  const ticks = [0, top / 2, top];
  const money = (v: number) => wholeMoney(v, currency, locale);
  // The axis makes room for its longest label: "$1,500" in English, "1 500 $US" in French.
  const axisLeft = Math.max(...ticks.map((t) => money(t).length)) * CHAR_W + 12;
  const x = (i: number) => axisLeft + (i * (W - axisLeft - RIGHT)) / (values.length - 1);
  const y = (v: number) => TOP + (1 - v / top) * (H - TOP - BOTTOM);
  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  const area = `${line} L${x(values.length - 1).toFixed(1)},${y(0)} L${x(0)},${y(0)} Z`;
  const middle = Math.floor((values.length - 1) / 2);

  return (
    <figure
      className="relative w-full max-w-lg rounded-3xl p-6 text-left"
      style={{
        background: 'linear-gradient(180deg, rgba(255,255,255,0.10), rgba(255,255,255,0.03))',
        border: '1px solid rgba(255,255,255,0.14)',
        boxShadow: '0 50px 120px -40px rgba(242,182,64,0.55)',
      }}
      data-testid="partner-hero-earnings"
    >
      <div className="flex items-center gap-3">
        <span className="rounded-full p-0.5" style={{ background: 'linear-gradient(135deg, #fde68a, #f2b640 55%, #c98a12)' }}>
          <PublisherAvatar userId={null} name={copy.name} src={avatarSrc} size={40} variant="neutral" />
        </span>
        <div className="min-w-0">
          <div className="flex items-center gap-1.5 text-sm font-semibold text-white">
            <span className="truncate">{copy.name}</span>
            <PartnerBadgeIcon partner size="sm" label={copy.badge} />
          </div>
          <div className="text-xs" style={{ color: 'rgba(255,255,255,0.6)' }}>{copy.role}</div>
        </div>
      </div>

      <div className="mt-6 text-xs uppercase tracking-wider" style={{ color: 'rgba(255,255,255,0.55)' }}>{copy.thisMonth}</div>
      <div className="mt-1 flex flex-wrap items-baseline gap-x-2">
        <span className="text-4xl sm:text-5xl font-bold tracking-tight text-white tabular-nums" data-testid="partner-hero-month">{money(last)}</span>
        <span className="text-base" style={{ color: 'rgba(255,255,255,0.6)' }}>{copy.perMonth}</span>
      </div>
      <div className="mt-1 text-sm" style={{ color: 'rgba(255,255,255,0.6)' }}>{copy.fromClients}</div>

      <svg viewBox={`0 0 ${W} ${H}`} className="mt-4 w-full" role="img" aria-label={copy.chartLabel}>
        <defs>
          <linearGradient id="hero-earn-fill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#f2b640" stopOpacity="0.45" />
            <stop offset="100%" stopColor="#f2b640" stopOpacity="0" />
          </linearGradient>
          <linearGradient id="hero-earn-line" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#fde68a" />
            <stop offset="100%" stopColor="#f2b640" />
          </linearGradient>
        </defs>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={axisLeft} x2={W - RIGHT} y1={y(t)} y2={y(t)} stroke="rgba(255,255,255,0.08)" />
            <text x={axisLeft - 8} y={y(t) + 4} textAnchor="end" fontSize="12" fill="rgba(255,255,255,0.5)">{money(t)}</text>
          </g>
        ))}
        <path d={area} fill="url(#hero-earn-fill)" />
        <path d={line} fill="none" stroke="url(#hero-earn-line)" strokeWidth="3" strokeLinejoin="round" strokeLinecap="round" />
        <circle cx={x(values.length - 1)} cy={y(last)} r="5" fill="#f2b640" stroke="#07080c" strokeWidth="2" />
        {[0, middle, values.length - 1].map((i, k) => (
          <text key={i} x={x(i)} y={H - 8} textAnchor={k === 0 ? 'start' : k === 2 ? 'end' : 'middle'} fontSize="12" fill="rgba(255,255,255,0.5)">
            {copy.months[k]}
          </text>
        ))}
      </svg>

      <div className="mt-4 grid grid-cols-2 gap-3">
        <div className="rounded-2xl p-3" style={{ background: 'rgba(255,255,255,0.06)' }}>
          <div className="text-xs" style={{ color: 'rgba(255,255,255,0.55)' }}>{copy.yearTotal}</div>
          <div className="mt-0.5 text-lg font-semibold text-white tabular-nums" data-testid="partner-hero-year">{money(total)}</div>
        </div>
        <div className="rounded-2xl p-3" style={{ background: 'rgba(255,255,255,0.06)' }}>
          <div className="text-xs" style={{ color: 'rgba(255,255,255,0.55)' }}>{copy.rate}</div>
          <div className="mt-0.5 text-lg font-semibold" style={{ color: '#f2b640' }}>{copy.rateValue}</div>
        </div>
      </div>
    </figure>
  );
}

export default PartnerHeroEarnings;
