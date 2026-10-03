/**
 * Where the account's display preferences are MOUNTED, and the language the login pages open in.
 *
 * <p>Two wirings that no other test can see, for the same reason as the sibling
 * `inspectorPreferenceWiring` suite: both layouts are async server components that await the
 * request locale and the message catalogue, which a jsdom render cannot provide, so they are read
 * as SOURCE. The gate's own behaviour is exercised in `DisplayPreferencesGate.test.tsx`; what
 * matters here is that it is actually mounted, which is a fact about the files.
 *
 * <p>The gap this closes is specific. Delete the mount from either shell and every other test in
 * the change still passes while the preference silently stops applying on that half of the
 * product - and `/workflows`, the builder, is the route that shows the most timestamps of any in
 * the app. It is also the easy one to forget, because it lives outside the `/app` tree and has
 * already had to re-mount the layout-direction and inspector providers for exactly this reason.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const read = (relative: string) => readFileSync(join(process.cwd(), relative), 'utf8');

const workflowsLayout = read('app/workflows/layout.tsx');
// The /app shell lives in the client half of the layout since the per-request CSP nonce split
// (LC-027): app/[locale]/app/layout.tsx is a thin server wrapper around AppLayoutClient.
const appLayout = read('app/[locale]/app/AppLayoutClient.tsx');
const providers = read('lib/providers/smart-providers.tsx');

/**
 * Lines that are CODE, not prose. Every one of these files explains in a comment WHY the mount is
 * there, and those comments name it, so a plain substring scan would report a deleted mount as
 * present - the explanation of a thing is not the thing.
 */
function codeLines(source: string): string[] {
  return source
    .split('\n')
    .map((line) => line.trim())
    .filter(
      (line) =>
        line !== '' && !line.startsWith('//') && !line.startsWith('*') && !line.startsWith('/*')
    );
}

const hasCodeLine = (source: string, fragment: string): boolean =>
  codeLines(source).some((line) => line.includes(fragment));

describe('the display-preferences gate is mounted on both shells', () => {
  it('wraps the standalone /workflows builder', () => {
    expect(hasCodeLine(workflowsLayout, '<DisplayPreferencesGate>')).toBe(true);
    expect(hasCodeLine(workflowsLayout, '</DisplayPreferencesGate>')).toBe(true);
    expect(hasCodeLine(workflowsLayout, "from '@/components/lifecycle/DisplayPreferencesGate'")).toBe(true);
  });

  it('wraps the /app shell', () => {
    expect(hasCodeLine(appLayout, '<DisplayPreferencesGate>')).toBe(true);
    expect(hasCodeLine(appLayout, '</DisplayPreferencesGate>')).toBe(true);
    expect(hasCodeLine(appLayout, "from '@/components/lifecycle/DisplayPreferencesGate'")).toBe(true);
  });

  it('wraps CHILDREN on the builder route, rather than sitting beside them', () => {
    // A gate that renders nothing under it applies the zone and re-renders an empty subtree: the
    // hook still runs, the tests on the hook still pass, and not one date on the page moves.
    const lines = codeLines(workflowsLayout);
    const open = lines.findIndex((line) => line.includes('<DisplayPreferencesGate>'));
    const close = lines.findIndex((line) => line.includes('</DisplayPreferencesGate>'));
    const children = lines.findIndex((line) => line === '{children}');

    expect(open).toBeGreaterThanOrEqual(0);
    expect(children).toBeGreaterThan(open);
    expect(close).toBeGreaterThan(children);
  });
});

describe('the identity provider is asked for the language the app is being read in', () => {
  it('passes ui_locales on the sign-in redirect', () => {
    // Without it, someone reading the app in French is sent to a login form in English, and the
    // password-reset mail Keycloak sends from that form goes out in English too: before there is
    // an account there is no stored language, so this parameter is the only signal there is.
    expect(hasCodeLine(providers, 'ui_locales: toIdpUiLocale(getClientLocale())')).toBe(true);
  });

  it('lets an explicit caller parameter win over it', () => {
    // The spread comes AFTER, so a deliberate authorizationParams.ui_locales is still honoured.
    const line = codeLines(providers).find((l) => l.includes('ui_locales:'));
    expect(line).toBeDefined();
    expect(line!.indexOf('ui_locales:')).toBeLessThan(line!.indexOf('...opts?.authorizationParams'));
  });
});
