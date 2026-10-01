import Link from 'next/link';
import { Check, Crown } from 'lucide-react';

export interface FounderBandCopy {
  eyebrow: string;
  title: string;
  titleRate: string;
  body: string;
  points: string[];
  cta: string;
  daysLeft: string;
  closes: string;
  fine: string;
  /** "Read the Partner Program Terms", and where they are in the reader's language. */
  termsLink: string;
  termsHref: string;
  plate: string;
  plateRate: string;
  /** The Platinum tier name, translated. */
  plateTier: string;
}

/**
 * The founding-partner offer, on its own dark band: straight to Platinum, the top rate from the
 * first client, for life, with the days left. Founders are chosen by the team; the fine print
 * under the offer says so. Server component, every word through `copy`.
 */
export function PartnerFounderBand({ copy, days }: { copy: FounderBandCopy; days: number }) {
  return (
    <div className="grid grid-cols-1 gap-12 lg:grid-cols-5 lg:items-center" data-testid="partner-founder-band">
      <div className="lg:col-span-3">
        <span
          className="inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-wider"
          style={{ background: 'rgba(169,180,214,0.15)', color: '#d9def0', border: '1px solid rgba(169,180,214,0.35)' }}
        >
          <Crown className="h-3.5 w-3.5" aria-hidden />
          {copy.eyebrow}
        </span>
        <h2
          className="mt-5 text-3xl md:text-5xl font-bold tracking-tight text-white"
          style={{ fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em', lineHeight: 1.1 }}
        >
          {copy.title}
          <span
            className="block"
            style={{ background: 'linear-gradient(90deg, #f4f6fb, #c7cfe6 40%, #f2b640 100%)', WebkitBackgroundClip: 'text', backgroundClip: 'text', color: 'transparent' }}
          >
            {copy.titleRate}
          </span>
        </h2>
        <p className="mt-6 text-lg leading-relaxed" style={{ color: 'rgba(255,255,255,0.7)' }}>{copy.body}</p>
        <ul className="mt-6 space-y-3">
          {copy.points.map((p) => (
            <li key={p} className="flex items-start gap-3 text-base text-white">
              <span className="mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full" style={{ background: '#f2b640' }}>
                <Check className="h-3 w-3" style={{ color: '#2a1a00' }} aria-hidden />
              </span>
              {p}
            </li>
          ))}
        </ul>
        <a
          href="#apply"
          className="mt-8 inline-flex h-11 items-center gap-2 rounded-xl px-6 text-sm font-semibold transition-transform active:scale-[0.98]"
          style={{ background: 'linear-gradient(135deg, #fde68a, #f2b640 55%, #d99a1e)', color: '#2a1a00' }}
        >
          <Crown className="h-3.5 w-3.5" aria-hidden />
          {copy.cta}
        </a>
        <p className="mt-4 text-sm" style={{ color: 'rgba(255,255,255,0.5)' }}>{copy.fine}</p>
        <Link href={copy.termsHref} className="mt-2 inline-block text-sm underline underline-offset-2" style={{ color: 'rgba(255,255,255,0.75)' }} data-testid="partner-founder-terms">
          {copy.termsLink}
        </Link>
      </div>

      <div className="lg:col-span-2">
        {/* The founder plate: a membership card in platinum, with the countdown under it. */}
        <div
          className="relative overflow-hidden rounded-3xl p-7"
          style={{
            background: 'linear-gradient(135deg, #f4f6fb 0%, #d9def0 35%, #a9b4d6 70%, #7f89b8 100%)',
            boxShadow: '0 40px 100px -30px rgba(169,180,214,0.6)',
            color: '#1f2340',
          }}
        >
          <div aria-hidden className="absolute -right-10 -top-10 h-40 w-40 rounded-full" style={{ background: 'rgba(255,255,255,0.35)', filter: 'blur(20px)' }} />
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-[0.2em]">{copy.plate}</span>
            <Crown className="h-5 w-5" aria-hidden />
          </div>
          <div className="mt-10 text-6xl font-bold tracking-tight">{copy.plateRate}</div>
          <div className="mt-1 text-sm font-medium opacity-80">{copy.plateTier}</div>
        </div>
        {days > 0 && (
          <div className="mt-5 flex items-center gap-4 rounded-2xl p-4" style={{ background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.1)' }}>
            <div className="text-4xl font-bold tabular-nums" style={{ color: '#f2b640' }} data-testid="partner-founder-days">{days}</div>
            <div>
              <div className="text-sm font-semibold text-white">{copy.daysLeft}</div>
              <div className="text-sm" style={{ color: 'rgba(255,255,255,0.6)' }}>{copy.closes}</div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

export default PartnerFounderBand;
