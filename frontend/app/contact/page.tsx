'use client';

import React, { useEffect, useState } from 'react';
import Script from 'next/script';
import { useTranslations } from 'next-intl';

const RECAPTCHA_SITE_KEY = (process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY ?? '').trim();

// A NEXT_PUBLIC_* var is baked at build time, so a blank key is a property of the
// running bundle, not of this visit: the form can NEVER submit. Knowing that at
// mount is what lets the page say so up front instead of accepting a ticket it
// will drop. See `captcha_misconfigured` below for the server-side twin.
//
// This is deliberately also the CE state, and it is a DECISION, not a constraint:
// app/api/runtime-config/route.ts exists precisely so one prebuilt image can be told
// things at run time. That route is scoped to reachability (origins, URLs) and a site
// key is not, so it stays a build arg. The asymmetry is the real reason: a self-hoster
// CAN set RECAPTCHA_SECRET_KEY on their own auth-service, but cannot supply the site
// key without rebuilding, and half a pair still refuses every submission. Announcing
// that and pointing at the address the page already lists beats a form that accepts
// messages and drops them. If self-hosted contact should work, that route is where it
// belongs.
const CAPTCHA_CONFIGURED = RECAPTCHA_SITE_KEY.length > 0;

type Category = 'support' | 'bug' | 'security' | 'privacy' | 'press' | 'abuse' | 'other';

const CATEGORY_VALUES: Category[] = ['support', 'bug', 'security', 'privacy', 'press', 'abuse', 'other'];

function readCategoryFromQuery(raw: string | null): Category | null {
  if (!raw) return null;
  const normalized = raw.trim().toLowerCase() as Category;
  return (CATEGORY_VALUES as string[]).includes(normalized) ? normalized : null;
}

type Status =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'success' }
  | { kind: 'error'; message: string };

declare global {
  interface Window {
    grecaptcha?: {
      ready: (cb: () => void) => void;
      execute: (siteKey: string, opts: { action: string }) => Promise<string>;
    };
  }
}

function friendlyError(
  t: (key: string, values?: Record<string, string | number>) => string,
  code: string,
): string {
  switch (code) {
    case 'invalid_name': return t('errors.invalidName');
    case 'invalid_email': return t('errors.invalidEmail');
    case 'invalid_message': return t('errors.invalidMessage');
    // Permanent: the server has no reCAPTCHA secret, so every retry fails the same
    // way. Grouping it with the transient failures below sent visitors into an
    // endless "try again in a moment" that could never succeed.
    case 'captcha_misconfigured': return t('unavailable');
    case 'captcha_missing':
    case 'captcha_unavailable': return t('errors.captchaUnavailable');
    case 'captcha_rejected':
    case 'captcha_low_score': return t('errors.captchaRejected');
    case 'submission_failed': return t('errors.submissionFailed');
    default: return code.startsWith('http_')
      ? t('errors.http', { code })
      : t('errors.generic');
  }
}

export default function ContactPage() {
  const t = useTranslations('contact');

  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [category, setCategory] = useState<Category>('support');
  const [message, setMessage] = useState('');
  const [status, setStatus] = useState<Status>({ kind: 'idle' });
  const [recaptchaReady, setRecaptchaReady] = useState(false);

  // Pre-fill from URL query params (e.g. `/contact?category=abuse&message=…`)
  // so deep-links from in-app surfaces (publication report, billing CTA, …)
  // land directly on the correct category with context already populated.
  // Applied post-mount from window.location instead of useSearchParams():
  // that hook at page level opts /contact out of server rendering entirely
  // (empty HTML for crawlers), the same CSR-bailout fixed in FirstLoginGuard.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const queryCategory = readCategoryFromQuery(params.get('category'));
    if (queryCategory) setCategory(queryCategory);
    const queryMessage = params.get('message');
    if (queryMessage) setMessage(queryMessage);
  }, []);

  useEffect(() => {
    if (!CAPTCHA_CONFIGURED) {
      console.warn('NEXT_PUBLIC_RECAPTCHA_SITE_KEY is not set - contact form is disabled.');
    }
  }, []);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (status.kind === 'submitting') return;

    if (!CAPTCHA_CONFIGURED) {
      setStatus({ kind: 'error', message: t('unavailable') });
      return;
    }
    if (!recaptchaReady || !window.grecaptcha) {
      setStatus({ kind: 'error', message: t('errors.recaptchaLoading') });
      return;
    }

    setStatus({ kind: 'submitting' });

    // Deliberately OUTSIDE the network try below. grecaptcha.execute() rejects on a
    // captcha problem (invalid or unregistered site key, blocked widget), which the
    // shared catch reported as "network error" - a message that tells the visitor to
    // retry a request that was never sent and can never succeed.
    let captchaToken: string;
    try {
      captchaToken = await window.grecaptcha.execute(RECAPTCHA_SITE_KEY, { action: 'contact' });
    } catch (captchaError) {
      console.error('reCAPTCHA execute failed', captchaError);
      // Reported as retryable even though some causes are not (a key that is invalid,
      // unregistered, or wrong-domain rejects forever). The client cannot tell those from
      // a transient widget failure: execute() rejects the same way for all of them. The
      // permanent cases the page CAN name - no key here, no secret on the server - are
      // named, above and in friendlyError.
      setStatus({ kind: 'error', message: t('errors.captchaUnavailable') });
      return;
    }
    if (!captchaToken) {
      setStatus({ kind: 'error', message: t('errors.captchaUnavailable') });
      return;
    }

    try {
      const response = await fetch('/api/contact', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, email, category, message, captchaToken }),
      });

      if (!response.ok) {
        const payload = await response.json().catch(() => ({}));
        const errorCode: string = payload?.error || `http_${response.status}`;
        setStatus({ kind: 'error', message: friendlyError(t, errorCode) });
        return;
      }

      setStatus({ kind: 'success' });
      setName('');
      setEmail('');
      setMessage('');
      setCategory('support');
    } catch (err) {
      console.error('Contact form submit failed', err);
      setStatus({ kind: 'error', message: t('errors.network') });
    }
  }

  return (
    <article className="space-y-6">
      {/* Not loaded when the key is blank: api.js?render= still answers 200 and defines
          window.grecaptcha, so the widget reported itself READY and only failed later,
          inside execute(). Skipping it keeps the unconfigured state honest from mount. */}
      {CAPTCHA_CONFIGURED && (
        <Script
          src={`https://www.google.com/recaptcha/api.js?render=${RECAPTCHA_SITE_KEY}`}
          strategy="afterInteractive"
          onLoad={() => {
            if (window.grecaptcha) window.grecaptcha.ready(() => setRecaptchaReady(true));
          }}
        />
      )}

      <header>
        <h1 className="text-2xl font-bold" style={{ color: 'var(--text-primary)' }}>{t('title')}</h1>
        <p className="text-sm mt-1" style={{ color: 'var(--text-secondary)' }}>
          {t('subtitle')}
        </p>
      </header>

      <section
        className="rounded-lg p-5"
        style={{ border: '1px solid var(--border-color)', background: 'var(--bg-secondary)' }}
      >
        {!CAPTCHA_CONFIGURED && (
          <p
            role="alert"
            className="mb-4 rounded-md px-3 py-2 text-sm"
            style={{ background: 'var(--bg-primary)', border: '1px solid #dc2626', color: '#dc2626' }}
          >
            {t('unavailable')}
          </p>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="contact-name" className="block text-sm font-semibold mb-1" style={{ color: 'var(--text-primary)' }}>
              {t('fields.name')}
            </label>
            <input
              id="contact-name"
              type="text"
              required
              maxLength={200}
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-md px-3 py-2 text-sm"
              style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)', color: 'var(--text-primary)' }}
              placeholder={t('placeholders.name')}
            />
          </div>

          <div>
            <label htmlFor="contact-email" className="block text-sm font-semibold mb-1" style={{ color: 'var(--text-primary)' }}>
              {t('fields.email')}
            </label>
            <input
              id="contact-email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full rounded-md px-3 py-2 text-sm"
              style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)', color: 'var(--text-primary)' }}
              placeholder={t('placeholders.email')}
            />
          </div>

          <div>
            <label htmlFor="contact-category" className="block text-sm font-semibold mb-1" style={{ color: 'var(--text-primary)' }}>
              {t('fields.category')}
            </label>
            <select
              id="contact-category"
              value={category}
              onChange={(e) => setCategory(e.target.value as Category)}
              className="w-full rounded-md px-3 py-2 text-sm"
              style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)', color: 'var(--text-primary)' }}
            >
              {CATEGORY_VALUES.map((value) => (
                <option key={value} value={value}>{t(`categories.${value}`)}</option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor="contact-message" className="block text-sm font-semibold mb-1" style={{ color: 'var(--text-primary)' }}>
              {t('fields.message')}
            </label>
            <textarea
              id="contact-message"
              required
              rows={6}
              maxLength={5000}
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              className="w-full rounded-md px-3 py-2 text-sm"
              style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)', color: 'var(--text-primary)' }}
              placeholder={t('placeholders.message')}
            />
            <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
              {t('characterCount', { count: message.length, max: 5000 })}
            </p>
          </div>

          <div className="pt-2">
            <button
              type="submit"
              disabled={status.kind === 'submitting' || !CAPTCHA_CONFIGURED}
              className="rounded-md px-4 py-2 text-sm font-semibold disabled:opacity-60"
              style={{ background: 'var(--accent-primary)', color: 'var(--accent-foreground)' }}
            >
              {status.kind === 'submitting' ? t('sending') : t('send')}
            </button>
          </div>

          {status.kind === 'success' && (
            <p className="text-sm" style={{ color: '#16a34a' }}>
              {t('success')}
            </p>
          )}
          {status.kind === 'error' && (
            <p className="text-sm" style={{ color: '#dc2626' }}>
              {status.message}
            </p>
          )}

          <p className="text-xs mt-4" style={{ color: 'var(--text-muted)' }}>
            {t('recaptchaPrefix')}{' '}
            <a href="https://policies.google.com/privacy" target="_blank" rel="noopener noreferrer" className="underline">{t('privacyPolicy')}</a>
            {' '}{t('recaptchaAnd')}{' '}
            <a href="https://policies.google.com/terms" target="_blank" rel="noopener noreferrer" className="underline">{t('termsOfService')}</a>
            {' '}{t('recaptchaSuffix')}
          </p>
        </form>
      </section>

      <section
        className="rounded-lg p-5"
        style={{ border: '1px solid var(--border-color)', background: 'var(--bg-secondary)' }}
      >
        <h2 className="text-sm font-semibold mb-3" style={{ color: 'var(--text-primary)' }}>{t('otherWays')}</h2>
        <div className="space-y-2 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
          <p>
            <strong>{t('emailLabel')}</strong>{' '}
            <a href="mailto:contact@livecontext.ai" style={{ color: 'var(--expression-color)' }}>contact@livecontext.ai</a>
          </p>
          <p><strong>{t('postalLabel')}</strong> {t('postalAddress')}</p>
          <p>{t.rich('securityNote', { code: (chunks) => <code>{chunks}</code> })}</p>
        </div>
      </section>
    </article>
  );
}
