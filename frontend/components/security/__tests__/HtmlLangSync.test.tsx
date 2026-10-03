/**
 * @vitest-environment jsdom
 *
 * LC-027 CASA E3 (round 2). `HtmlLangSync` is the nonced sibling of the html-lang inline script
 * `app/[locale]/layout.tsx` renders for every route (see documentLanguage.test.ts) - see this
 * component's own comment for why the shared layout cannot nonce its OWN copy. This suite pins
 * the two behaviours that actually matter for CSP correctness: the `nonce` attribute is present
 * only when supplied, and the statement itself is unchanged (still the exact literal
 * `documentLanguage.test.ts` looks for, just re-emitted with a nonce).
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import * as React from 'react';

import HtmlLangSync from '../HtmlLangSync';

describe('HtmlLangSync', () => {
  afterEach(cleanup);

  it('sets the nonce attribute when a nonce is supplied', () => {
    const { container } = render(<HtmlLangSync locale="fr" nonce="abc123" />);
    const script = container.querySelector('script') as HTMLScriptElement;
    expect(script.getAttribute('nonce')).toBe('abc123');
  });

  it('omits the nonce attribute when none is supplied (matches the unnonced [locale]/layout.tsx copy)', () => {
    const { container } = render(<HtmlLangSync locale="fr" />);
    const script = container.querySelector('script') as HTMLScriptElement;
    expect(script.hasAttribute('nonce')).toBe(false);
  });

  it('omits the nonce attribute when explicitly null (headers().get() returns null off the nonce class)', () => {
    const { container } = render(<HtmlLangSync locale="fr" nonce={null} />);
    const script = container.querySelector('script') as HTMLScriptElement;
    expect(script.hasAttribute('nonce')).toBe(false);
  });

  it('emits the exact same html-lang statement documentLanguage.test.ts pins on [locale]/layout.tsx', () => {
    const { container } = render(<HtmlLangSync locale="de" nonce="xyz" />);
    const script = container.querySelector('script') as HTMLScriptElement;
    expect(script.innerHTML).toBe('document.documentElement.lang="de"');
  });
});
