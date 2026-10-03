// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { isSafeReturnPath, safeReturnPath } from '@/lib/security/safeReturnPath';

/**
 * Regression (CASA, ASVS 5.1.5 open redirect): the login and register pages navigated to the
 * raw `?returnTo=` value (`router.replace(returnTo)` and `window.location.href = returnTo`),
 * so `/en/login?returnTo=https://evil.example` or `?returnTo=javascript:alert(1)` sent a
 * freshly signed-in user anywhere. Every hostile case below was accepted by the pre-fix code.
 */
const ORIGIN = 'https://livecontext.ai';
const FALLBACK = '/en/app/chat';

describe('safeReturnPath', () => {
  it.each([
    '/en/app/chat',
    '/app/',
    '/fr/invitations/accept?token=tok slash/plus+value',
    '/en/app/project/project-1?panel=files&tab=recent',
    '/en/app/settings?tab=security#2fa',
    '/en/invitations/accept?token=abc%2Fdef',
  ])('keeps the legitimate same-origin path %s', (path) => {
    expect(safeReturnPath(path, FALLBACK, ORIGIN)).toBe(path);
  });

  it.each([
    ['absolute foreign URL', 'https://evil.example/phish'],
    ['protocol-relative', '//evil.example/phish'],
    ['backslash protocol-relative', '/\\evil.example'],
    ['double backslash', '\\\\evil.example'],
    ['javascript scheme', 'javascript:alert(document.cookie)'],
    ['upper-case javascript scheme', 'JaVaScRiPt:alert(1)'],
    ['data scheme', 'data:text/html,<script>alert(1)</script>'],
    ['tab trick that the URL parser collapses into //', '/\t/evil.example'],
    ['newline trick', '/\n/evil.example'],
    ['encoded protocol-relative', '/%2F%2Fevil.example'],
    ['encoded backslash', '/%5Cevil.example'],
    ['double-encoded protocol-relative', '/%252F%252Fevil.example'],
    ['bare host', 'evil.example'],
    ['userinfo smuggling', '@evil.example'],
    ['host extension', '.evil.example/x'],
    ['leading space', ' /en/app/chat'],
    ['empty string', ''],
    ['over-long value', `/${'a'.repeat(3000)}`],
    ['same-host different scheme', 'http://livecontext.ai/en/app/chat'],
    ['look-alike origin', 'https://livecontext.ai.evil.example/en'],
    ['same-origin URL whose path is protocol-relative', 'https://livecontext.ai//evil.example'],
  ])('rejects %s', (_label, hostile) => {
    expect(safeReturnPath(hostile, FALLBACK, ORIGIN)).toBe(FALLBACK);
  });

  // Audit B #8: the shape checks used to run on the WHOLE value (and its decoded forms), so an
  // encoded newline, backslash or colon in the QUERY sent a legitimate deep link to the fallback.
  it.each([
    ['encoded newline in a search query', '/en/app/search?q=a%0Ab'],
    ['encoded Windows path in a query value', '/en/app/files?path=C%3A%5Cusers%5Cme'],
    ['encoded protocol-relative inside a query value', '/en/app/chat?next=%2F%2Fdocs'],
    ['double-encoded newline in a query', '/en/app/search?q=line1%250Aline2'],
    ['encoded tab and colon in the fragment', '/en/app/settings#note%09a%3Ab'],
    ['raw colon and slashes in the query', '/en/app/chat?u=https://docs.example/x'],
    ['raw backslash in the fragment', '/en/app/notes#C:\\temp'],
  ])('keeps the path when only its query or fragment holds %s', (_label, path) => {
    expect(safeReturnPath(path, FALLBACK, ORIGIN)).toBe(path);
    expect(isSafeReturnPath(path)).toBe(true);
  });

  it.each([
    ['encoded newline in the path', '/en/app%0A/chat'],
    ['encoded backslash in the path before a query', '/%5Cevil.example?q=1'],
    ['encoded protocol-relative path before a fragment', '/%2F%2Fevil.example#x'],
    ['protocol-relative path with a query', '//evil.example?q=a%0Ab'],
    ['backslash protocol-relative path with a query', '/\\evil.example?x=1'],
    ['raw newline in the query', '/en/app/search?q=a\nb'],
    ['raw tab in the fragment', '/en/app#a\tb'],
    ['scheme with a harmless-looking query', 'javascript:alert(1)?q=%0A'],
  ])('still rejects %s', (_label, hostile) => {
    expect(safeReturnPath(hostile, FALLBACK, ORIGIN)).toBe(FALLBACK);
  });

  it('falls back on a missing value', () => {
    expect(safeReturnPath(null, FALLBACK, ORIGIN)).toBe(FALLBACK);
    expect(safeReturnPath(undefined, FALLBACK, ORIGIN)).toBe(FALLBACK);
  });

  it('reduces an absolute URL on the current origin to its path (CE embedded sign-in)', () => {
    expect(safeReturnPath(`${ORIGIN}/app/settings?tab=security#x`, FALLBACK, ORIGIN))
      .toBe('/app/settings?tab=security#x');
  });

  it('refuses an absolute URL when no origin is known (server render)', () => {
    expect(safeReturnPath(`${ORIGIN}/app/`, FALLBACK, undefined)).toBe(FALLBACK);
  });

  it('defaults the origin to the current page', () => {
    expect(safeReturnPath(`${window.location.origin}/app/x`, FALLBACK)).toBe('/app/x');
    expect(safeReturnPath('https://evil.example/app/x', FALLBACK)).toBe(FALLBACK);
  });
});

/**
 * Regression (CASA round 3, ASVS 5.1.5 defence in depth): the sanitizer accepted any same-origin
 * path, so `?returnTo=/api/proxy/...`, `/webhook/...` or `/mcp` sent a freshly signed-in user to
 * a backend endpoint or a framework file instead of a page. Every denied case below was returned
 * unchanged by the pre-fix code.
 */
describe('safeReturnPath non-page targets', () => {
  it.each([
    ['bare /api', '/api'],
    ['/api subtree', '/api/users/me'],
    ['the frontend gateway proxy', '/api/proxy/workflows?page=0'],
    ['bare /webhook', '/webhook'],
    ['/webhook subtree', '/webhook/abc-123'],
    ['/webhooks subtree', '/webhooks/stripe'],
    ['any /webhook* prefix', '/webhook-trigger/x'],
    ['Next internals', '/_next/static/chunks/main.js'],
    ['bare /_next', '/_next'],
    ['well-known files', '/.well-known/security.txt'],
    ['bare /mcp', '/mcp'],
    ['/mcp subtree', '/mcp/sse'],
    ['gateway websocket', '/ws/stream'],
    ['chat-channel callback', '/approval-callback/telegram'],
    ['widget script', '/widget.js'],
    ['widget API', '/widget/abc/config'],
    // Audit round 2: the prod ingress matches Prefix rules as STRINGS, so these reach the gateway.
    ['/api followed by a matrix parameter', '/api;x'],
    ['/api followed by an extension', '/api.json'],
    ['a name that merely starts with api', '/apiary'],
    ['/ws string prefix', '/wsx'],
    ['/approval-callback string prefix', '/approval-callbackx'],
    // Audit round 2: next.config rewrites and the /chat and /form ingress rules.
    ['public chat endpoint', '/chat/abc-123'],
    ['/chat with a trailing slash only', '/chat/'],
    ['/chat/c without an id', '/chat/c'],
    ['/chat string prefix', '/chatx'],
    ['bare /form', '/form'],
    ['public form endpoint', '/form/abc-123'],
    ['/form string prefix', '/formx'],
    ['bare /c', '/c'],
    ['shared conversation API', '/c/conv-1'],
    ['bare /share', '/share'],
    ['share resolution API', '/share/tok-1'],
    ['public app endpoint', '/app/public/app-1/render.html'],
    // Re-audit: the cloud ingress takes every /chat*, so the legacy redirects never run there.
    ['bare /chat', '/chat'],
    ['legacy /chat/c link', '/chat/c/conv-1'],
    ['non-page target with a hash', '/api/x#frag'],
  ])('rejects %s', (_label, target) => {
    expect(safeReturnPath(target, FALLBACK, ORIGIN)).toBe(FALLBACK);
    expect(isSafeReturnPath(target)).toBe(false);
  });

  it.each([
    ['percent-encoded letter', '/%61pi/x'],
    ['percent-encoded upper-case letters', '/%41%50%49/x'],
    ['double percent-encoded letter', '/%2561pi/x'],
    ['encoded first letter of webhook', '/%77ebhook/x'],
    ['encoded dot of .well-known', '/%2ewell-known/security.txt'],
    ['upper case', '/API/'],
    ['mixed case webhook', '/WebHooks/stripe'],
    ['mixed case mcp', '/MCP'],
    ['duplicate leading slash', '//api'],
    ['backslash', '/\\api'],
    ['dot segment', '/./api'],
    ['parent segment from a page', '/en/../api/x'],
    ['encoded parent segment', '/en/%2e%2e/api/x'],
    ['decoded-to-parent segment', '/en/%252e%252e/api/x'],
    ['same-origin absolute URL', `${ORIGIN}/api/proxy/x`],
    ['same-origin absolute URL in upper case', `${ORIGIN}/Webhook/x`],
    // Audit round 2: a malformed escape used to stop ALL decoding, so only the raw form was checked.
    ['malformed escape after an encoded letter', '/%61pi/x%zz'],
    ['malformed escape right after the prefix', '/%61pi%zz'],
    ['malformed escape in the second decoding round', '/%2561pi/x%25zz'],
    ['invalid UTF-8 escape after an encoded letter', '/%61pi/%C3x'],
    ['invalid UTF-8 in the same escape run as the prefix', '/%61%70%69%C3/x'],
    // Re-audit: the middleware 308-redirects /<locale><rest> to <rest> when <rest> is not a
    // locale-required area, so a locale prefix in front of a non-page target only adds a hop.
    ['locale before /api', '/en/api/x'],
    ['locale before the gateway proxy', '/en/api/proxy/x'],
    ['locale before /webhook', '/fr/webhook/x'],
    ['locale before bare /mcp', '/en/mcp'],
    ['locale before /share', '/de/share/t'],
    ['locale before /form', '/en/form/x'],
    ['locale before /chat', '/en/chat/abc'],
    ['locale before bare /chat', '/en/chat'],
    ['locale before /widget/', '/en/widget/abc'],
    ['locale before /c', '/es/c/conv-1'],
    ['locale before /_next', '/zh/_next/static/x.js'],
    ['locale before /.well-known', '/pt/.well-known/security.txt'],
    ['two locales (two redirects)', '/en/fr/api'],
    ['upper-case locale and target', '/EN/API/x'],
    ['percent-encoded locale letter', '/%65n/api/x'],
    ['locale before an encoded target', '/en/%61pi'],
    ['locale before a parent segment', '/en/app/../api/x'],
    // Final audit: a `//` anywhere in the path, which a locale-strip redirect brings to the front.
    ['double slash after a locale', '/en//x'],
    ['double slash before a numeric host after a locale', '/en//2130706433'],
    ['encoded double slash after a locale', '/en/%2F%2F2130706433'],
    ['double slash inside a page path', '/en/app/x//y'],
    // Final audit: still decoding after the last round is refused, not judged half-decoded.
    ['4x-encoded /api', '/%25252561pi'],
  ])('rejects the bypass encoding: %s', (_label, target) => {
    expect(safeReturnPath(target, FALLBACK, ORIGIN)).toBe(FALLBACK);
  });

  it.each([
    '/en/app/chat',
    '/app/workflows/abc-123?tab=runs#node-1',
    '/en/app/workflows/abc-123/builder',
    '/s/share-token-1',
    '/f/form-token.v2',
    '/w/embed?agent=a1',
    '/fr/app/settings/overview?tab=security#2fa',
    '/zh/invitations/accept?token=t',
    '/local-mcp',
    '/docs/mcp-server',
    '/docs/rest-api',
    '/en/app/webhooks',
    '/en/app/c/conv-1?q=/api/x',
    '/en/app/chat#/api',
    '/en/app/search?next=%2Fapi%2Fproxy',
    '/widget-demo',
    '/widget',
    '/shared',
    '/compare',
    '/contact',
    '/changelog',
    '/f/form-1',
    '/for/agencies',
    '/en/app/public/x',
    '/en/app/publications',
    '/en/app/settings/public-access',
    '/workflows/builder',
    '/en/app/files/100%25off',
    '/en/app/notes/a%zz',
    // Locale paths the middleware renders under the locale (locale-required areas) or that
    // redirect to a real page.
    '/en',
    '/fr',
    '/en/app/chat',
    '/fr/app/public/app-1/render.html',
    '/en/fr/app/public/x',
    '/de/login',
    '/es/register',
    '/pt/onboarding',
    '/en/s/share-token-1',
    '/en/docs/mcp-server',
    '/en/compare',
    '/en/shared',
    '/fr/for/agencies',
  ])('keeps the page path %s unchanged', (page) => {
    expect(safeReturnPath(page, FALLBACK, ORIGIN)).toBe(page);
    expect(isSafeReturnPath(page)).toBe(true);
  });
});

describe('isSafeReturnPath', () => {
  it('accepts only relative same-origin paths', () => {
    expect(isSafeReturnPath('/app/chat')).toBe(true);
    expect(isSafeReturnPath(`${ORIGIN}/app/chat`)).toBe(false);
    expect(isSafeReturnPath('//evil.example')).toBe(false);
    expect(isSafeReturnPath('/\\evil.example')).toBe(false);
  });

  it('rejects non-string values read back from storage', () => {
    expect(isSafeReturnPath(undefined)).toBe(false);
    expect(isSafeReturnPath(42)).toBe(false);
    expect(isSafeReturnPath({ toString: () => '/app' })).toBe(false);
  });
});
