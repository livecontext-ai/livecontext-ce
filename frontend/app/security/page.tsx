import type { Metadata } from 'next';
import React from 'react';
import Link from 'next/link';
import { getTranslations } from 'next-intl/server';
import { resolveRequestLocale } from '@/i18n/resolveRequestLocale';
import { LandingShell } from '@/components/landing/LandingShell';
import { IS_CE } from '@/lib/edition';
import { socialCard } from '@/lib/seo/socialCard';

/**
 * Public vulnerability disclosure policy, the `Policy:` target of /.well-known/security.txt
 * (RFC 9116). A server component outside the `[locale]` tree, so the locale comes from the
 * NEXT_LOCALE cookie like /contact, and no client intl provider is needed.
 */
// The address the public CE SECURITY.md (deploy/ce-export/public-assets) already publishes.
const SECURITY_CONTACT_EMAIL = 'security@livecontext.ai';

export async function generateMetadata(): Promise<Metadata> {
  const locale = await resolveRequestLocale();
  const t = await getTranslations({ locale, namespace: 'securityDisclosure.metadata' });
  return {
    title: t('title'),
    description: t('description'),
    alternates: { canonical: '/security' },
    ...socialCard({ title: t('title'), description: t('description'), path: '/security' }),
    // Self-hosted deployments must never index marketing pages.
    // Only on CE: on the cloud an explicit 'robots: undefined' wiped the root layout's directives.
    ...(IS_CE ? { robots: { index: false, follow: false } } : {}),
  };
}

const LINK_STYLE = { color: 'var(--text-secondary)', textDecoration: 'underline' } as const;
const RULE_KEYS = ['ownAccount', 'noData', 'noDisruption', 'noSocial', 'disclosure'] as const;

export default async function SecurityDisclosurePage() {
  const locale = await resolveRequestLocale();
  const t = await getTranslations({ locale, namespace: 'securityDisclosure' });

  const sections: Array<{ title: string; content: React.ReactNode }> = [
    {
      title: t('reportTitle'),
      content: (
        <p>
          {t.rich('reportBody', {
            email: (chunks) => <a href={`mailto:${SECURITY_CONTACT_EMAIL}?subject=%5BSECURITY%5D`} style={LINK_STYLE}>{chunks}</a>,
            contact: (chunks) => <Link href="/contact?category=security" style={LINK_STYLE}>{chunks}</Link>,
          })}
        </p>
      ),
    },
    {
      title: t('commitTitle'),
      content: (
        <ul>
          <li>{t('commitAck')}</li>
          <li>{t('commitUpdates')}</li>
          <li>{t('commitCredit')}</li>
        </ul>
      ),
    },
    {
      title: t('rulesTitle'),
      content: (
        <ul>
          {RULE_KEYS.map((key) => <li key={key}>{t(`rules.${key}`)}</li>)}
        </ul>
      ),
    },
    { title: t('safeHarborTitle'), content: <p>{t('safeHarbor')}</p> },
    {
      title: t('scopeTitle'),
      content: (
        <>
          <p>{t('scopeIn')}</p>
          <p>{t('scopeOut')}</p>
          <p>{t('bountyNote')}</p>
        </>
      ),
    },
  ];

  return (
    <LandingShell>
      <div className="max-w-3xl mx-auto px-6 py-10">
        <article className="space-y-6">
          <header>
            <h1 className="text-2xl font-bold" style={{ color: 'var(--text-primary)' }}>{t('title')}</h1>
            <p className="text-sm mt-2" style={{ color: 'var(--text-secondary)' }}>{t('intro')}</p>
          </header>
          {sections.map((s) => (
            <section
              key={s.title}
              className="rounded-lg p-5"
              style={{ border: '1px solid var(--border-color)', background: 'var(--bg-secondary)' }}
            >
              <h2 className="text-sm font-semibold mb-3" style={{ color: 'var(--text-primary)' }}>{s.title}</h2>
              <div
                className="space-y-2 text-sm leading-relaxed [&_ul]:list-disc [&_ul]:list-inside [&_ul]:space-y-1 [&_ul]:ml-2"
                style={{ color: 'var(--text-secondary)' }}
              >
                {s.content}
              </div>
            </section>
          ))}
          {/* The security.txt file is served by the cloud only (the self-hosted edition does not
              ship it), so a CE install would link to a 404. */}
          {!IS_CE && (
            <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
              {t.rich('machineReadable', {
                file: (chunks) => <a href="/.well-known/security.txt" style={LINK_STYLE}>{chunks}</a>,
              })}
            </p>
          )}
        </article>
      </div>
    </LandingShell>
  );
}
