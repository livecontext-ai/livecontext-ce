import Link from 'next/link';
import { ArrowRight, Check, Github, Minus } from 'lucide-react';
import { LandingShell } from '@/components/landing/LandingShell';
import SignInButton from '@/app/[locale]/_landing/SignInButton';
import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';
import { COMPARISONS, type CompareCell, type Comparison } from '../_lib/comparisons';

// Shared renderer for the /compare/<slug> SEO pages. Server component, no intl
// context (lives outside the [locale] tree, hardcoded English like /about).

function CellContent({ cell }: { cell: CompareCell }) {
  const icon =
    cell.state === 'yes' ? (
      <Check className="w-4 h-4" aria-label="Yes" style={{ color: 'var(--text-primary)' }} />
    ) : cell.state === 'partial' ? (
      <Minus className="w-4 h-4" aria-label="Partial" style={{ color: 'var(--text-muted)' }} />
    ) : (
      <span aria-label="No" style={{ color: 'var(--text-muted)' }}>-</span>
    );
  const label = cell.state === 'yes' ? 'Yes' : cell.state === 'partial' ? 'Partial' : 'No';
  return (
    <div>
      <div className="flex items-center gap-1.5 font-semibold" style={{ color: cell.state === 'yes' ? 'var(--text-primary)' : 'var(--text-secondary)' }}>
        {icon} {label}
      </div>
      <p className="mt-1 text-xs leading-relaxed" style={{ color: 'var(--text-muted)' }}>{cell.note}</p>
    </div>
  );
}

function Cta() {
  return (
    <div className="flex flex-wrap gap-3">
      <SignInButton
        variant="primary"
        className="inline-flex items-center gap-2 h-12 px-6 rounded-full text-sm font-semibold transition-all hover:brightness-110 active:scale-[0.98] cursor-pointer"
      >
        Start free <ArrowRight className="w-4 h-4" />
      </SignInButton>
      <a
        href={SELF_HOSTED_GITHUB_URL}
        target="_blank"
        rel="noopener noreferrer"
        className="inline-flex items-center gap-2 h-12 px-6 rounded-full text-sm font-semibold transition-all cursor-pointer hover:brightness-110 bg-[var(--bg-tertiary)]"
        style={{ color: 'var(--text-primary)' }}
      >
        <Github className="w-4 h-4" /> Self-host
      </a>
    </div>
  );
}

export default function ComparePageContent({ comparison }: { comparison: Comparison }) {
  const others = COMPARISONS.filter((c) => c.slug !== comparison.slug);

  return (
    <LandingShell>
      <article className="mx-auto w-full max-w-5xl px-6 py-16 md:py-24">
        {/* Hero */}
        <header className="max-w-3xl">
          <nav aria-label="Breadcrumb" className="text-xs" style={{ color: 'var(--text-muted)' }}>
            <Link href="/" className="hover:opacity-80">Home</Link>
            <span className="mx-1.5">/</span>
            <Link href="/compare" className="hover:opacity-80">Compare</Link>
            <span className="mx-1.5">/</span>
            <span>LiveContext vs {comparison.competitor}</span>
          </nav>
          <h1
            className="mt-4 text-4xl md:text-5xl font-bold tracking-tight"
            style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em', lineHeight: 1.1 }}
          >
            {comparison.h1}
          </h1>
          <p className="mt-6 text-lg leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            {comparison.intro}
          </p>
          <div className="mt-8">
            <Cta />
          </div>
          <p className="mt-4 text-xs" style={{ color: 'var(--text-muted)' }}>
            Free tier · No credit card · Last updated {comparison.lastUpdated}
          </p>
        </header>

        {/* Verdict, written to be quotable on its own */}
        <section className="mt-16 grid gap-4 md:grid-cols-2">
          <div className="rounded-2xl p-6" style={{ background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}>
            <h2 className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>When {comparison.competitor} fits</h2>
            <p className="mt-2 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{comparison.verdictCompetitor}</p>
          </div>
          <div className="rounded-2xl p-6" style={{ background: 'var(--landing-highlight-row)', border: '1px solid var(--border-color)' }}>
            <h2 className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>When LiveContext fits</h2>
            <p className="mt-2 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{comparison.verdictLivecontext}</p>
          </div>
        </section>

        {/* Feature table */}
        <section className="mt-20">
          <h2
            className="text-2xl md:text-3xl font-bold tracking-tight"
            style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em' }}
          >
            LiveContext vs {comparison.competitor}, feature by feature
          </h2>
          <div className="mt-8 overflow-x-auto rounded-2xl" style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}>
            <table className="w-full text-sm" style={{ minWidth: 640 }}>
              <thead>
                <tr style={{ background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border-color)' }}>
                  <th className="text-left p-4 text-[11px] uppercase tracking-wider font-semibold w-1/3" style={{ color: 'var(--text-muted)' }}>Capability</th>
                  <th className="text-left p-4 text-[11px] uppercase tracking-wider font-semibold w-1/3" style={{ color: 'var(--text-primary)' }}>LiveContext</th>
                  <th className="text-left p-4 text-[11px] uppercase tracking-wider font-semibold w-1/3" style={{ color: 'var(--text-muted)' }}>{comparison.competitor}</th>
                </tr>
              </thead>
              <tbody>
                {comparison.rows.map((row, idx) => (
                  <tr key={row.feature} style={{ borderBottom: idx < comparison.rows.length - 1 ? '1px solid var(--border-color)' : 'none' }}>
                    <td className="p-4 align-top font-semibold" style={{ color: 'var(--text-primary)' }}>{row.feature}</td>
                    <td className="p-4 align-top" style={{ background: 'var(--landing-highlight-row)' }}>
                      <CellContent cell={row.livecontext} />
                    </td>
                    <td className="p-4 align-top">
                      <CellContent cell={row.competitor} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="mt-3 text-xs" style={{ color: 'var(--text-muted)' }}>
            Comparison reflects publicly available information as of {comparison.lastUpdated}. Products evolve; check both before deciding.
          </p>
        </section>

        {/* Why teams switch */}
        <section className="mt-20">
          <h2
            className="text-2xl md:text-3xl font-bold tracking-tight"
            style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em' }}
          >
            Why teams switch from {comparison.competitor}
          </h2>
          <div className="mt-8 grid gap-4 md:grid-cols-2">
            {comparison.reasons.map((reason) => (
              <div key={reason.title} className="rounded-2xl p-6" style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}>
                <h3 className="text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{reason.title}</h3>
                <p className="mt-2 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{reason.description}</p>
              </div>
            ))}
          </div>
        </section>

        {/* Honest counterpart, deliberately kept: balanced pages earn citations */}
        <section className="mt-16 rounded-2xl p-6" style={{ background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}>
          <h2 className="text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{comparison.honestTitle}</h2>
          <ul className="mt-3 space-y-2 text-sm leading-relaxed list-disc pl-5" style={{ color: 'var(--text-secondary)' }}>
            {comparison.honest.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </section>

        {/* Migration */}
        <section className="mt-20">
          <h2
            className="text-2xl md:text-3xl font-bold tracking-tight"
            style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em' }}
          >
            Migrating from {comparison.competitor} in three steps
          </h2>
          <ol className="mt-8 grid gap-4 md:grid-cols-3">
            {comparison.migration.map((step, idx) => (
              <li key={step.title} className="rounded-2xl p-6" style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}>
                <span className="text-xs font-semibold" style={{ color: 'var(--text-muted)' }}>Step {idx + 1}</span>
                <h3 className="mt-1 text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{step.title}</h3>
                <p className="mt-2 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{step.description}</p>
              </li>
            ))}
          </ol>
        </section>

        {/* FAQ */}
        <section className="mt-20">
          <h2
            className="text-2xl md:text-3xl font-bold tracking-tight"
            style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em' }}
          >
            Frequently asked questions
          </h2>
          <div className="mt-8 space-y-4">
            {comparison.faq.map((item) => (
              <details key={item.question} className="rounded-2xl p-6 group" style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}>
                <summary className="cursor-pointer text-base font-semibold list-none" style={{ color: 'var(--text-primary)' }}>
                  {item.question}
                </summary>
                <p className="mt-3 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{item.answer}</p>
              </details>
            ))}
          </div>
        </section>

        {/* Cross links + CTA */}
        <section className="mt-20 rounded-2xl p-8 text-center" style={{ background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}>
          <h2
            className="text-2xl md:text-3xl font-bold tracking-tight"
            style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em' }}
          >
            Try the {comparison.competitor} alternative
          </h2>
          <p className="mt-3 text-sm" style={{ color: 'var(--text-secondary)' }}>
            One message in. A working automation out. Free tier, no credit card.
          </p>
          <div className="mt-6 flex justify-center">
            <Cta />
          </div>
          <p className="mt-8 text-xs" style={{ color: 'var(--text-muted)' }}>
            Also comparing:{' '}
            {others.map((other, idx) => (
              <span key={other.slug}>
                {idx > 0 && ' · '}
                <Link href={`/compare/${other.slug}`} className="underline underline-offset-2 hover:opacity-80">
                  LiveContext vs {other.competitor}
                </Link>
              </span>
            ))}
            {' · '}
            <a href="https://docs.livecontext.ai" className="underline underline-offset-2 hover:opacity-80">Documentation</a>
          </p>
        </section>
      </article>
    </LandingShell>
  );
}
