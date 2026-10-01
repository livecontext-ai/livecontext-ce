import { Clock, Crown, Medal, ShieldCheck, TrendingUp, Trophy, type LucideIcon } from 'lucide-react';
import { TIER_STYLE, type PartnerTierKey } from '@/lib/partners/tiers';

const ICONS: Record<PartnerTierKey, LucideIcon> = { silver: Medal, gold: Trophy, platinum: Crown };

export interface TierStop {
  tier: PartnerTierKey;
  name: string;
  /** "50%", formatted. */
  rate: string;
  /** When a partner gets here: "From day one", "Once your clients have paid $5,000". */
  when: string;
  /** What that means in clients: "About 2 clients on Pro with 250K credits for a year". Absent for the first stop. */
  equivalent?: string;
}

export interface TierTrackCopy {
  perInvoice: string;
  founderShortcut: string;
  /** The label over the Platinum card: "Top tier". */
  topTier: string;
  rules: { title: string; body: string }[];
}

const RULE_ICONS: LucideIcon[] = [TrendingUp, ShieldCheck, Clock];

const PLATINUM_FRAME = 'linear-gradient(135deg, #f4f6fb, #a9b4d6 40%, #6f79ad 70%, #d9def0)';
const PLATINUM_TEXT = {
  background: 'linear-gradient(90deg, #ffffff, #d9def0 45%, #a9b4d6)',
  WebkitBackgroundClip: 'text',
  backgroundClip: 'text',
  color: 'transparent',
} as const;

/**
 * How a partner moves up: the three tiers on one track, left to right, the rate large in each
 * metal, what reaches each stop in dollars AND in clients, and the founding-partner shortcut
 * pointing straight at Platinum. Platinum is the destination, so it is drawn as one: a dark card
 * in a platinum frame, raised above the others. Under it, the three rules that govern the climb.
 */
export function PartnerTierTrack({
  stops,
  copy,
  showFounder,
}: {
  stops: TierStop[];
  copy: TierTrackCopy;
  showFounder: boolean;
}) {
  return (
    <div className="mt-14" data-testid="partner-tier-track">
      <div className="relative">
        {/* The track the three stops sit on (desktop). */}
        <div
          aria-hidden
          className="absolute left-[16%] right-[16%] top-[34px] hidden h-1.5 rounded-full lg:block"
          style={{ background: 'linear-gradient(90deg, #c3cad4, #f2b640 50%, #a9b4d6)' }}
        />
        <ol className="relative grid grid-cols-1 gap-6 lg:grid-cols-3 lg:items-start">
          {stops.map((stop) => {
            const Icon = ICONS[stop.tier];
            const style = TIER_STYLE[stop.tier];
            const top = stop.tier === 'platinum';
            return (
              <li key={stop.tier} className={`flex flex-col items-center text-center${top ? ' lg:-translate-y-2' : ''}`} data-tier={stop.tier}>
                <span
                  className={`relative grid place-items-center rounded-full shadow-lg ${top ? 'h-[84px] w-[84px]' : 'h-[72px] w-[72px]'}`}
                  style={{
                    background: style.gradient,
                    color: style.ink,
                    boxShadow: top
                      ? '0 0 0 6px var(--bg-primary), 0 0 60px 6px rgba(169,180,214,0.55)'
                      : `0 0 0 6px var(--bg-primary), 0 16px 40px -12px ${style.ring}`,
                  }}
                  aria-hidden
                >
                  <Icon className={top ? 'h-8 w-8' : 'h-7 w-7'} />
                </span>
                {top ? (
                  // The destination: a dark card in a platinum frame, the rate in platinum.
                  <div
                    className="relative mt-6 w-full rounded-3xl p-[2px]"
                    style={{ background: PLATINUM_FRAME, boxShadow: '0 40px 90px -30px rgba(139,147,194,0.75)' }}
                  >
                    <span
                      className="absolute -top-3 left-1/2 -translate-x-1/2 whitespace-nowrap rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-wider shadow-md"
                      style={{ background: style.gradient, color: style.ink }}
                    >
                      {copy.topTier}
                    </span>
                    <div className="rounded-[22px] p-6 pt-8" style={{ background: '#0b0d12', color: '#fff' }}>
                      <div className="text-sm font-semibold uppercase tracking-wider" style={{ color: '#d9def0' }}>{stop.name}</div>
                      <div className="mt-2 text-7xl font-bold tracking-tight" style={PLATINUM_TEXT}>{stop.rate}</div>
                      <div className="mt-1 text-sm" style={{ color: 'rgba(255,255,255,0.6)' }}>{copy.perInvoice}</div>
                      <div className="mt-5 text-base font-semibold">{stop.when}</div>
                      {stop.equivalent && (
                        <div className="mt-1 text-sm" style={{ color: 'rgba(255,255,255,0.7)' }}>{stop.equivalent}</div>
                      )}
                      {showFounder && (
                        <div
                          className="mt-5 inline-flex items-center gap-2 rounded-full px-3 py-1 text-sm font-semibold"
                          style={{ background: style.gradient, color: style.ink }}
                          data-testid="partner-founder-shortcut"
                        >
                          <Crown className="h-3.5 w-3.5" aria-hidden />
                          {copy.founderShortcut}
                        </div>
                      )}
                    </div>
                  </div>
                ) : (
                  <div
                    className="mt-6 w-full rounded-3xl p-6"
                    style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}
                  >
                    <div className="text-sm font-semibold uppercase tracking-wider" style={{ color: 'var(--text-muted)' }}>{stop.name}</div>
                    <div className="mt-2 text-6xl font-bold tracking-tight" style={{ color: 'var(--text-primary)' }}>{stop.rate}</div>
                    <div className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>{copy.perInvoice}</div>
                    <div className="mt-5 text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{stop.when}</div>
                    {stop.equivalent && (
                      <div className="mt-1 text-sm" style={{ color: 'var(--text-secondary)' }}>{stop.equivalent}</div>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ol>
      </div>

      <ul className="mt-10 grid grid-cols-1 gap-4 md:grid-cols-3">
        {copy.rules.map((rule, i) => {
          const Icon = RULE_ICONS[i] ?? ShieldCheck;
          return (
            <li key={rule.title} className="flex gap-3 rounded-2xl p-5" style={{ background: 'var(--bg-secondary)' }}>
              <Icon className="mt-0.5 h-5 w-5 shrink-0" style={{ color: '#d99a1e' }} aria-hidden />
              <div>
                <div className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>{rule.title}</div>
                <p className="mt-1 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{rule.body}</p>
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export default PartnerTierTrack;
