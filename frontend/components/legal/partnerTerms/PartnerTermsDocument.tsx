import Link from 'next/link';
import { PARTNER_TERMS_PATH, PARTNER_TERMS_PATH_FR, PARTNER_TERMS_VERSION } from '@/lib/partners/terms';
import type { PartnerTermsBlock, PartnerTermsDocument as TermsDoc } from './types';

function Block({ block }: { block: PartnerTermsBlock }) {
  if (typeof block === 'string') return <p>{block}</p>;
  return (
    <ul>
      {block.list.map((item) => <li key={item}>{item}</li>)}
    </ul>
  );
}

/**
 * One language version of the Partner Program Terms, laid out like the other legal pages: the
 * version and the date it is in force from at the top (the version is what a partner accepts),
 * which language prevails, a table of contents, every clause, then Schedule 1.
 *
 * <p>Server component, plain text only: the documents are data (./en, ./fr), never markup.
 */
export function PartnerTermsDocument({ doc }: { doc: TermsDoc }) {
  const other = doc.lang === 'fr' ? PARTNER_TERMS_PATH : PARTNER_TERMS_PATH_FR;
  return (
    <article className="space-y-6" lang={doc.lang} data-testid="partner-terms-document">
      <header className="space-y-2">
        <h1 className="text-2xl font-bold" style={{ color: 'var(--text-primary)' }}>{doc.title}</h1>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          {doc.versionLabel} <span data-testid="partner-terms-version">{PARTNER_TERMS_VERSION}</span>
          {' · '}
          {doc.effectiveLabel} {doc.effectiveDate}
        </p>
        <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>
          {doc.prevailingNote}{' '}
          <Link href={other} className="underline" hrefLang={doc.lang === 'fr' ? 'en' : 'fr'}>{doc.otherLanguageLabel}</Link>
        </p>
      </header>

      <section
        className="rounded-lg p-5 space-y-3 text-sm leading-relaxed"
        style={{ border: '1px solid var(--border-color)', background: 'var(--bg-secondary)', color: 'var(--text-secondary)' }}
      >
        {doc.intro.map((p) => <p key={p}>{p}</p>)}
      </section>

      <nav aria-label={doc.contentsLabel} className="text-sm" style={{ color: 'var(--text-secondary)' }}>
        <h2 className="text-sm font-semibold mb-2" style={{ color: 'var(--text-primary)' }}>{doc.contentsLabel}</h2>
        <ol className="grid gap-1 sm:grid-cols-2">
          {doc.clauses.map((c) => (
            <li key={c.number}>
              <a href={`#clause-${c.number}`} className="hover:underline">{c.number}. {c.title}</a>
            </li>
          ))}
          <li><a href="#schedule-1" className="hover:underline">{doc.schedule.title}</a></li>
        </ol>
      </nav>

      {doc.clauses.map((c) => (
        <section
          key={c.number}
          id={`clause-${c.number}`}
          className="rounded-lg p-5 scroll-mt-24"
          style={{ border: '1px solid var(--border-color)', background: 'var(--bg-secondary)' }}
        >
          <h2 className="text-sm font-semibold mb-3" style={{ color: 'var(--text-primary)' }}>{c.number}. {c.title}</h2>
          <div
            className="space-y-2 text-sm leading-relaxed [&_ul]:list-none [&_ul]:space-y-1 [&_ul]:ml-2"
            style={{ color: 'var(--text-secondary)' }}
          >
            {c.blocks.map((b, i) => <Block key={i} block={b} />)}
          </div>
        </section>
      ))}

      <section
        id="schedule-1"
        className="rounded-lg p-5 scroll-mt-24"
        style={{ border: '1px solid var(--border-color)', background: 'var(--bg-secondary)' }}
        data-testid="partner-terms-schedule"
      >
        <h2 className="text-sm font-semibold mb-2" style={{ color: 'var(--text-primary)' }}>{doc.schedule.title}</h2>
        <p className="text-sm mb-3" style={{ color: 'var(--text-secondary)' }}>{doc.schedule.intro}</p>
        <table className="w-full text-sm" style={{ color: 'var(--text-secondary)' }}>
          <tbody>
            {doc.schedule.rows.map(([label, value]) => (
              <tr key={label} className="align-top" style={{ borderTop: '1px solid var(--border-color)' }}>
                <th scope="row" className="py-2 pr-4 text-left font-medium" style={{ color: 'var(--text-primary)' }}>{label}</th>
                <td className="py-2">{value}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </article>
  );
}

export default PartnerTermsDocument;
