/**
 * Regression tests for the locale path helpers.
 *
 * Bug (2026-06-12): the helpers (and several inline copies) hardcoded
 * (en|fr|es), so de/pt/zh users had the locale prefix left in the pathname -
 * useCurrentView then failed to match any /app/* route and the whole view
 * detection (breadcrumb, sidebar highlight, EmptyCanvasChat gating) broke.
 * The old prefix regex also lacked a boundary, so '/enterprise' was parsed
 * as locale 'en' + '/terprise'.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { removeLocalePrefix, parseLocalePath, buildLocalePath, toIdpUiLocale } from '../locale';
import { locales } from '@/i18n/routing';

describe('removeLocalePrefix', () => {
  it('strips EVERY locale of the routing config (regression: de/pt/zh were not stripped)', () => {
    for (const locale of locales) {
      expect(removeLocalePrefix(`/${locale}/app/workflow`)).toBe('/app/workflow');
    }
  });

  it('regression: does not mistake a path starting with a locale string for a locale prefix', () => {
    expect(removeLocalePrefix('/enterprise')).toBe('/enterprise');
    expect(removeLocalePrefix('/free-trial')).toBe('/free-trial');
  });

  it('leaves unprefixed paths untouched and handles null', () => {
    expect(removeLocalePrefix('/app/workflow')).toBe('/app/workflow');
    expect(removeLocalePrefix(null)).toBe('');
  });
});

describe('parseLocalePath', () => {
  it('extracts EVERY routing locale (regression: de/pt/zh fell through to no-locale)', () => {
    for (const locale of locales) {
      expect(parseLocalePath(`/${locale}/app/workflow`)).toEqual({ locale, normalized: '/app/workflow' });
    }
  });

  it('returns no locale for unprefixed and lookalike paths', () => {
    expect(parseLocalePath('/app/workflow')).toEqual({ locale: '', normalized: '/app/workflow' });
    expect(parseLocalePath('/enterprise')).toEqual({ locale: '', normalized: '/enterprise' });
  });

  it('normalizes a bare locale path to /', () => {
    expect(parseLocalePath('/de')).toEqual({ locale: 'de', normalized: '/' });
  });
});

describe('buildLocalePath', () => {
  it('prefixes non-default locales and leaves en/empty unprefixed', () => {
    expect(buildLocalePath('de', '/app/workflow')).toBe('/de/app/workflow');
    expect(buildLocalePath('en', '/app/workflow')).toBe('/app/workflow');
    expect(buildLocalePath('', '/app/workflow')).toBe('/app/workflow');
  });
});

describe('toIdpUiLocale', () => {
  it('spells the two regional codes the way Keycloak does', () => {
    // The realm lists pt-BR and zh-CN; sending "pt" would leave it on its default locale.
    expect(toIdpUiLocale('pt')).toBe('pt-BR');
    expect(toIdpUiLocale('zh')).toBe('zh-CN');
  });

  it('passes the other four through unchanged', () => {
    for (const locale of ['en', 'fr', 'de', 'es']) {
      expect(toIdpUiLocale(locale)).toBe(locale);
    }
  });

  it('agrees with the server-side mapping for every app locale', () => {
    // KeycloakAdminEmailVerifier.toKeycloakLocale writes the same codes onto the user. The two
    // must not diverge: the login page would then be in one language and its e-mail in another.
    //
    // READ from that file, rather than restating its output as a literal. The literal version
    // pinned nothing about the server (it could have been edited freely) and additionally
    // coupled to the DECLARATION ORDER of `locales`, so reordering an unrelated list failed a
    // test about Keycloak.
    const server = readFileSync(
      join(
        process.cwd(), '..', 'backend', 'auth-service', 'src', 'main', 'java', 'com',
        'apimarketplace', 'auth', 'service', 'KeycloakAdminEmailVerifier.java'
      ),
      'utf8'
    );
    // The DECLARATION, not the first mention: `toKeycloakLocale` also appears in javadoc and at
    // call sites above it, and slicing from one of those reads a region with no switch in it -
    // which is how the first version of this test failed while both sides agreed.
    const declaration = server.indexOf('String toKeycloakLocale(String appLocale)');
    expect(declaration, 'toKeycloakLocale must still be declared there').toBeGreaterThan(-1);
    const body = server.slice(declaration, server.indexOf('\n    }', declaration));

    // Its arms, read as data. A switch EXPRESSION (`case "pt" -> "pt-BR";`), which is what the
    // server actually uses - asserting on `return "..."` matched nothing and said nothing.
    const serverMapping = new Map<string, string>();
    for (const [, from, to] of body.matchAll(/case\s+"([a-z-]+)"\s*->\s*"([a-zA-Z-]+)"/g)) {
      serverMapping.set(from, to);
    }
    expect(serverMapping.size, 'no switch arms found: the server shape changed').toBeGreaterThan(0);

    for (const locale of locales) {
      expect(serverMapping.get(locale), `${locale} must map the same way on both sides`)
        .toBe(toIdpUiLocale(locale));
    }
    // And nothing extra server-side: a code it knows and the app does not would be written onto
    // the user and never produced here, which is the same divergence in the other direction.
    expect([...serverMapping.keys()].sort()).toEqual([...locales].sort());
  });
});
