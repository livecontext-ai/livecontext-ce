import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

// Locks the contract of the SELF-CONTAINED public-site theme. The public site
// (landing + /contact, /legal/*, …) owns its own light/dark palette on
// `.landing-root` / `.landing-root.dark`, DEFAULTS TO DARK via LandingThemeProvider,
// and is decoupled from the app-wide ThemeProvider (<body> theme) so the logged-in
// app keeps following the OS. The footer only exposes the language selector; the
// public theme toggle has been removed, so stale light preferences are ignored.
const shellSrc = readFileSync(path.resolve(__dirname, '../LandingShell.tsx'), 'utf8');
const providerSrc = readFileSync(path.resolve(__dirname, '../LandingThemeProvider.tsx'), 'utf8');
const landingPageSrc = readFileSync(
  path.resolve(__dirname, '../../../app/[locale]/page.tsx'),
  'utf8',
);

describe('public-site self-contained theme contract', () => {
  it('defines a LIGHT palette on .landing-root and a DARK palette on .landing-root.dark', () => {
    expect(shellSrc).toMatch(/\.landing-root\s*\{[\s\S]*?--bg-primary:\s*#ffffff/i); // light default
    expect(shellSrc).toMatch(/\.landing-root\.dark\s*\{[\s\S]*?--bg-primary:\s*#171614/i); // dark override
    expect(shellSrc).toMatch(/--text-primary:\s*#111827/i); // light text
    expect(shellSrc).toMatch(/--text-primary:\s*#edecea/i); // dark text
  });

  it('drives the dark palette from the .landing-root class, NOT from <body> .dark (decoupled from the app)', () => {
    // The dark block must be scoped to the root's own class, not `.dark .landing-root`.
    expect(shellSrc).toMatch(/\.landing-root\.dark\s*\{/);
    expect(shellSrc).not.toMatch(/\.dark \.landing-root/);
  });

  it('defaults the public site to DARK (LandingThemeProvider)', () => {
    // The public site is dark-only for now; stored light preferences are ignored
    // (landing keeps respectStored=false, so it always renders the default theme).
    expect(providerSrc).toMatch(/defaultTheme = 'dark'/);
    // Safe fallback (no provider) is dark too.
    expect(providerSrc).toMatch(/theme: 'dark'/);
  });

  it('renders the language select in the FOOTER, with no public theme toggle in the chrome', () => {
    // The footer bottom bar carries the language select right after the copyright line.
    expect(shellSrc).toMatch(
      /All rights reserved\.<\/p>\s*<div[^>]*>\s*<LandingLanguageSelect \/>/,
    );
    expect(shellSrc).not.toMatch(/LandingThemeToggle/);
    // Header right-cluster must not carry the language control.
    expect(shellSrc).not.toMatch(/<LandingLanguageSelect \/>\s*<SignInButton variant="link"/);
  });

  it('wraps both landing surfaces in LandingThemeProvider (no forced `dark` class on a wrapper)', () => {
    // `className` is the first prop, whether the tag is on one line or wrapped across several.
    expect(shellSrc).toMatch(/<LandingThemeProvider\s+className=/);
    expect(landingPageSrc).toMatch(/<LandingThemeProvider\s+className=/);
    expect(shellSrc).not.toMatch(/className="dark /);
    expect(landingPageSrc).not.toMatch(/className="dark /);
  });

  it('drives landing decoration through theme-aware vars, not hardcoded dark rgba in the page', () => {
    expect(landingPageSrc).toMatch(/background: var\(--landing-hero-glow\)/);
    expect(landingPageSrc).toMatch(/box-shadow: var\(--landing-frame-shadow\)/);
    expect(landingPageSrc).not.toMatch(/rgba\(58, 46, 31/); // warm dark glow literal lives only in the shell .dark block
  });

  it('keeps the body-`dark:` utilities out of the page (converted to landing-scoped classes)', () => {
    // The two former body-theme dependencies are now landing-scoped:
    expect(landingPageSrc).not.toMatch(/dark:text-slate-100/); // feature-node icon
    expect(landingPageSrc).toMatch(/feature-node-icon/);
    expect(landingPageSrc).not.toMatch(/monoDarkInvertClass/); // mono logos
    expect(landingPageSrc).toMatch(/logo-mono/);
  });
});

// The public pages sharing the landing chrome (contact, legal/*) must reference the
// theme vars (resolved from .landing-root / .landing-root.dark), never hardcoded hex.
describe('public landing pages follow the theme (no hardcoded dark palette hex)', () => {
  const DARK_PALETTE_HEX = /#(?:171614|1f1e1b|2a2925|5e5a54|edecea|a39f97|736f67)\b/i;
  const sharedChromePages = [
    'app/contact/page.tsx',
    'app/legal/mentions/page.tsx',
    'app/legal/privacy/page.tsx',
    'app/legal/terms/page.tsx',
    'app/about/page.tsx',
    'app/changelog/page.tsx',
    'app/docs/page.tsx',
  ];

  for (const rel of sharedChromePages) {
    it(`${rel} has no hardcoded dark-palette hex (uses var(--…) instead)`, () => {
      const src = readFileSync(path.resolve(__dirname, '../../../', rel), 'utf8');
      expect(src).not.toMatch(DARK_PALETTE_HEX);
    });
  }
});
