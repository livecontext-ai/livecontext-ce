import { Gift, Landmark, LayoutDashboard, MessagesSquare, PanelsTopLeft, Server, type LucideIcon } from 'lucide-react';
import type * as React from 'react';
import { LogoMark } from '@/components/auth/LogoMark';

export type BenefitKey = 'apps' | 'credits' | 'tracking' | 'payout' | 'hosting' | 'team';

export interface BenefitsCopy {
  items: Record<BenefitKey, { title: string; body: string }>;
  visual: {
    /** "+8,000", or null without program terms (the tile then shows its icon instead). */
    creditsAmount: string | null;
    creditsUnit: string;
    signups: string;
    paying: string;
    earned: string;
    /** This month's commission (the tracking tile's "Earned"), formatted; null without program terms. */
    earnedValue: string | null;
    /** Last month's commission: the top payout row, already past its hold; null without terms. */
    previousValue: string | null;
    /** The month before that: the faded payout row under it. */
    olderValue: string | null;
    /** The paying clients behind those figures (the hero's), so the tiles agree with each other. */
    payingCount: string;
    payout: string;
    paid: string;
    /** "Client A", "Client B", "Client C". */
    clients: [string, string, string];
    running: string;
    ask: string;
    reply: string;
    team: string;
  };
}

const ICONS: Record<BenefitKey, LucideIcon> = {
  apps: PanelsTopLeft,
  credits: Gift,
  tracking: LayoutDashboard,
  payout: Landmark,
  hosting: Server,
  team: MessagesSquare,
};

const GOLD = 'linear-gradient(135deg, #fde68a, #f2b640 55%, #d99a1e)';

function TileText({ item, icon: Icon, dark = false }: { item: { title: string; body: string }; icon: LucideIcon; dark?: boolean }) {
  return (
    <div>
      <span
        className="grid h-10 w-10 place-items-center rounded-2xl"
        style={dark ? { background: 'rgba(242,182,64,0.15)', color: '#f2b640' } : { background: 'rgba(242,182,64,0.14)', color: '#d99a1e' }}
        aria-hidden
      >
        <Icon className="h-5 w-5" />
      </span>
      <h3 className="mt-5 text-lg font-semibold" style={{ color: dark ? '#fff' : 'var(--text-primary)' }}>{item.title}</h3>
      <p className="mt-2 text-sm leading-relaxed" style={{ color: dark ? 'rgba(255,255,255,0.7)' : 'var(--text-secondary)' }}>{item.body}</p>
    </div>
  );
}

function Tile({ className = '', style, testId, children }: { className?: string; style?: React.CSSProperties; testId?: string; children: React.ReactNode }) {
  return (
    <li
      data-testid={testId}
      className={`relative overflow-hidden rounded-3xl p-7 ${className}`}
      style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)', ...style }}
    >
      {children}
    </li>
  );
}

/** A small app window: a form and a chart, the kind of finished product a partner hands over. */
function AppMock() {
  return (
    <div aria-hidden className="rounded-2xl p-4" style={{ background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.1)' }}>
      <div className="flex gap-1.5">
        {[0, 1, 2].map((i) => <span key={i} className="h-2 w-2 rounded-full" style={{ background: 'rgba(255,255,255,0.25)' }} />)}
      </div>
      <div className="mt-4 grid grid-cols-5 gap-3">
        <div className="col-span-2 space-y-2">
          <div className="h-2 w-2/3 rounded-full" style={{ background: 'rgba(255,255,255,0.35)' }} />
          <div className="h-6 rounded-lg" style={{ background: 'rgba(255,255,255,0.1)' }} />
          <div className="h-6 rounded-lg" style={{ background: 'rgba(255,255,255,0.1)' }} />
          <div className="h-6 w-2/3 rounded-lg" style={{ background: GOLD }} />
        </div>
        <div className="col-span-3 flex items-end gap-1.5 rounded-xl p-3" style={{ background: 'rgba(255,255,255,0.05)' }}>
          {[35, 55, 45, 70, 60, 85, 100].map((h, i) => (
            <span key={i} className="flex-1 rounded-t" style={{ height: `${h * 0.9}px`, background: i === 6 ? GOLD : 'rgba(242,182,64,0.35)' }} />
          ))}
        </div>
      </div>
    </div>
  );
}

/**
 * "What else you get", as a bento of six tiles, each with its own small picture of the benefit:
 * the app a partner delivers, the credits their clients start with, the live dashboard, the
 * payout, the per-client hosting and the line to the team. Server component; every word and
 * figure arrives through `copy`, and the figures are left out when the program terms are unknown.
 */
export function PartnerBenefitsBento({ copy }: { copy: BenefitsCopy }) {
  const { items, visual } = copy;
  return (
    <ul className="mt-12 grid grid-cols-1 gap-5 md:grid-cols-2 lg:grid-cols-3" data-testid="partner-benefits">
      {/* Apps: the largest tile, dark, with the product itself. */}
      <Tile className="md:col-span-2" style={{ background: '#0b0d12', border: '1px solid #0b0d12' }}>
        <div aria-hidden className="absolute -right-24 -top-24 h-64 w-64 rounded-full" style={{ background: 'radial-gradient(circle, rgba(242,182,64,0.25), transparent 70%)' }} />
        <div className="relative grid grid-cols-1 gap-8 sm:grid-cols-2 sm:items-center">
          <TileText item={items.apps} icon={ICONS.apps} dark />
          <AppMock />
        </div>
      </Tile>

      {/* Credits: gold, the amount as the picture. */}
      <Tile style={{ background: GOLD, border: '1px solid #e0a526' }}>
        <div aria-hidden className="text-5xl font-bold tracking-tight tabular-nums" style={{ color: '#2a1a00' }}>
          {visual.creditsAmount ?? <Gift className="h-12 w-12" />}
        </div>
        {visual.creditsAmount && <div aria-hidden className="text-sm font-semibold" style={{ color: 'rgba(42,26,0,0.7)' }}>{visual.creditsUnit}</div>}
        <h3 className="mt-8 text-lg font-semibold" style={{ color: '#2a1a00' }}>{items.credits.title}</h3>
        <p className="mt-2 text-sm leading-relaxed" style={{ color: 'rgba(42,26,0,0.75)' }}>{items.credits.body}</p>
      </Tile>

      {/* Tracking: a live dashboard. */}
      <Tile testId="partner-benefits-tracking">
        <div aria-hidden className="rounded-2xl p-4" style={{ background: 'var(--bg-secondary)' }}>
          <div className="grid grid-cols-3 gap-2 text-center">
            {[
              { label: visual.signups, value: '48' },
              { label: visual.paying, value: visual.payingCount },
              ...(visual.earnedValue ? [{ label: visual.earned, value: visual.earnedValue }] : []),
            ].map((stat) => (
              <div key={stat.label}>
                <div className="text-base font-bold tabular-nums" style={{ color: 'var(--text-primary)' }}>{stat.value}</div>
                <div className="text-xs" style={{ color: 'var(--text-muted)' }}>{stat.label}</div>
              </div>
            ))}
          </div>
          <svg viewBox="0 0 200 40" className="mt-3 w-full" preserveAspectRatio="none">
            <path d="M0,34 L25,30 L50,31 L75,24 L100,22 L125,16 L150,14 L175,8 L200,4" fill="none" stroke="#f2b640" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </div>
        <div className="mt-6"><TileText item={items.tracking} icon={ICONS.tracking} /></div>
      </Tile>

      {/* Payout: the transfer as the partner sees it. */}
      <Tile testId="partner-benefits-payout">
        <div aria-hidden className="space-y-2">
          {/* Only months already past the holding period are shown as paid: this month's is still held. */}
          {[{ opacity: 1, value: visual.previousValue }, { opacity: 0.45, value: visual.olderValue }].map(({ opacity, value }, i) => (
            <div key={i} className="flex items-center gap-3 rounded-2xl p-3" style={{ background: 'var(--bg-secondary)', opacity }}>
              <span className="grid h-8 w-8 place-items-center rounded-xl" style={{ background: GOLD, color: '#2a1a00' }}>
                <Landmark className="h-3.5 w-3.5" />
              </span>
              <span className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>{visual.payout}</span>
              {value && <span className="ml-auto text-sm font-bold tabular-nums" style={{ color: 'var(--text-primary)' }}>{value}</span>}
              <span className={`${value ? '' : 'ml-auto '}rounded-full px-2 py-0.5 text-xs font-semibold`} style={{ background: 'rgba(34,197,94,0.15)', color: '#16a34a' }}>{visual.paid}</span>
            </div>
          ))}
        </div>
        <div className="mt-6"><TileText item={items.payout} icon={ICONS.payout} /></div>
      </Tile>

      {/* Hosting: one deployment per client. */}
      <Tile>
        <div aria-hidden className="space-y-2">
          {visual.clients.map((client) => (
            <div key={client} className="flex items-center gap-3 rounded-2xl px-3 py-2.5" style={{ background: 'var(--bg-secondary)' }}>
              <Server className="h-3.5 w-3.5" style={{ color: 'var(--text-muted)' }} />
              <span className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>{client}</span>
              <span className="ml-auto inline-flex items-center gap-1.5 text-xs" style={{ color: '#16a34a' }}>
                <span className="h-1.5 w-1.5 rounded-full" style={{ background: '#22c55e', boxShadow: '0 0 0 3px rgba(34,197,94,0.2)' }} />
                {visual.running}
              </span>
            </div>
          ))}
        </div>
        <div className="mt-6"><TileText item={items.hosting} icon={ICONS.hosting} /></div>
      </Tile>

      {/* Team: a short exchange, the partner asking and the team answering. */}
      <Tile className="md:col-span-2 lg:col-span-3">
        <div className="grid grid-cols-1 gap-8 md:grid-cols-2 md:items-center">
          <TileText item={items.team} icon={ICONS.team} />
          <div aria-hidden className="space-y-3">
            <div className="ml-auto max-w-[85%] rounded-2xl rounded-br-md px-4 py-3 text-sm" style={{ background: 'rgba(242,182,64,0.16)', color: 'var(--text-primary)' }}>
              {visual.ask}
            </div>
            <div className="flex max-w-[85%] items-start gap-2">
              <span
                className="relative mt-1 h-8 w-8 shrink-0 overflow-hidden rounded-full"
                style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)', color: 'var(--text-primary)' }}
                data-testid="partner-benefits-team-logo"
              >
                {/* The mark fills about half of its own square, centred in it: the square is centred on
                    the circle rather than laid out in it, so a square larger than the circle stays centred. */}
                <LogoMark className="absolute left-1/2 top-1/2 h-9 w-9 -translate-x-1/2 -translate-y-1/2" />
              </span>
              <div>
                <div className="text-xs font-semibold" style={{ color: 'var(--text-muted)' }}>{visual.team}</div>
                <div className="mt-1 rounded-2xl rounded-tl-md px-4 py-3 text-sm" style={{ background: 'var(--bg-secondary)', color: 'var(--text-primary)' }}>
                  {visual.reply}
                </div>
              </div>
            </div>
          </div>
        </div>
      </Tile>
    </ul>
  );
}

export default PartnerBenefitsBento;
