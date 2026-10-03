import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { locales } from '@/i18n/routing';
import {
  isSafeReturnPath,
  LOCALE_RENDERED_AREA,
  NON_PAGE_PREFIX,
} from '@/lib/security/safeReturnPath';

/**
 * The return-path validator exists twice: here (login/register `returnTo`, the sign-in
 * redirect_uri) and in the backend `OAuth2ReturnPath` (the OAuth connect `return_url`). The
 * backend also needs the frontend's locale list, because the middleware strips a leading locale
 * with a redirect (`/en/api/x` -> `/api/x`). Drift is silent: one side keeps sending a signed-in
 * user to a gateway endpoint while every test on the other side stays green. So this reads the
 * Java source rather than restating either list, and runs the shared fixture table
 * (`shared/contracts/return-path-fixtures.json`, also run by the Java OAuth2ReturnPathTest) so the
 * locale-strip walk and the decoding rounds are pinned on both sides too.
 */
const RETURN_PATH_JAVA = path.resolve(
  __dirname,
  '../../../../backend/auth-service/src/main/java/com/apimarketplace/auth/credential/util/OAuth2ReturnPath.java',
);

const FIXTURES_JSON = path.resolve(__dirname, '../../../../shared/contracts/return-path-fixtures.json');

const fixtures = JSON.parse(fs.readFileSync(FIXTURES_JSON, 'utf8')) as {
  safe: string[];
  unsafe: string[];
};

function javaSource(): string {
  return fs.readFileSync(RETURN_PATH_JAVA, 'utf8');
}

function notFound(name: string): Error {
  return new Error(
    `${name} not found in ${RETURN_PATH_JAVA}. If it was renamed or moved, update this guard `
    + 'rather than deleting it: the two validators still have to agree.',
  );
}

/** A Java regex constant as the JVM sees it: string literals concatenated, double backslashes unescaped. */
function javaPattern(name: string): string {
  const declaration = new RegExp(String.raw`${name}\s*=\s*Pattern\.compile\(([\s\S]*?)\);`);
  const match = javaSource().match(declaration);
  if (!match) throw notFound(name);
  const literals = [...match[1].matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]);
  return literals.join('').replace(/\\\\/g, '\\');
}

function javaLocales(): string[] {
  const match = javaSource().match(/LOCALES\s*=\s*List\.of\(([^)]*)\)/);
  if (!match) throw notFound('LOCALES');
  return [...match[1].matchAll(/"([^"]*)"/g)].map((m) => m[1]);
}

/** A JS regex literal escapes `/`; Java's string does not need to. */
function jsSource(pattern: RegExp): string {
  return pattern.source.replace(/\\\//g, '/');
}

describe('return-path validator parity with the backend OAuth2ReturnPath', () => {
  it('uses the same non-page pattern', () => {
    expect(javaPattern('NON_PAGE_PREFIX')).toBe(jsSource(NON_PAGE_PREFIX));
  });

  it('stops the locale-strip walk at the same locale-rendered area', () => {
    expect(javaPattern('LOCALE_RENDERED_AREA')).toBe(jsSource(LOCALE_RENDERED_AREA));
  });

  it('strips the same locales as the i18n routing config', () => {
    expect(javaLocales()).toEqual([...locales]);
  });

  it.each(fixtures.safe)('keeps the shared fixture page %s', (value) => {
    expect(isSafeReturnPath(value)).toBe(true);
  });

  it.each(fixtures.unsafe)('refuses the shared fixture target %s', (value) => {
    expect(isSafeReturnPath(value)).toBe(false);
  });
});
