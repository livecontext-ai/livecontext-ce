import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

// Locks the contract of the SELF-CONTAINED public-site theme. The public site
// (landing + /contact, /legal/*, …) owns its own light/dark palette on
// `.landing-root` / `.landing-root.dark`, DEFAULTS TO LIGHT via
// LandingThemeProvider, and is decoupled from the app-wide ThemeProvider (<body>
// theme) so the logged-in app keeps following the OS. The footer bottom bar
// carries the language selector AND the public theme toggle; the visitor's
// choice persists under 'landing-theme'.
const shellSrc = readFileSync(path.resolve(__dirname, '../LandingShell.tsx'), 'utf8');
const providerSrc = readFileSync(path.resolve(__dirname, '../LandingThemeProvider.tsx'), 'utf8');
const landingPageSrc = readFileSync(
  path.resolve(__dirname, '../../../app/[locale]/page.tsx'),
  'utf8',
) + readFileSync(path.resolve(__dirname, '../landingStyles.ts'), 'utf8');
const globalsCssSrc = readFileSync(
  path.resolve(__dirname, '../../../app/globals.css'),
  'utf8',
);
// The contract is asserted where the markup lives, which is BrandMark: it is the single
// <img> behind every brand mark on the public site, both the landing's integrations strip
// and its "what you can build" cards, and the /integrations directory through
// IntegrationLogo, which only binds a catalogue record onto it. Reading IntegrationLogo
// alone would pass while the mark itself lost its dark-theme file.
const brandMarkSrc = readFileSync(
  path.resolve(__dirname, '../../integrations/BrandMark.tsx'),
  'utf8',
);

describe('public-site self-contained theme contract', () => {
  it('defines a LIGHT palette on .landing-root and a DARK palette on .landing-root.dark', () => {
    expect(shellSrc).toMatch(/\.landing-root\s*\{[\s\S]*?--bg-primary:\s*#ffffff/i); // light default
    expect(shellSrc).toMatch(/\.landing-root\.dark\s*\{[\s\S]*?--bg-primary:\s*#171614/i); // dark override (the app's warm dark palette)
    expect(shellSrc).toMatch(/--text-primary:\s*#111827/i); // light text (cool neutral, matches the app)
    expect(shellSrc).toMatch(/--text-primary:\s*#edecea/i); // dark text
  });

  it('makes a LIGHT .landing-root a "light island" so reused app `dark:` utilities do not leak the OS/<body> .dark', () => {
    // Reused app components inside the landing (marketplace card, node-icon bubbles,
    // preview/skeleton pulses) rely on Tailwind `dark:` variants keyed off <html> .dark.
    // The dark custom-variant must exclude descendants of a light `.landing-root` so
    // those stay LIGHT while the landing shows light, even on an OS-dark visitor.
    expect(globalsCssSrc).toMatch(
      /@custom-variant dark \(&:where\(\.dark, \.dark \*\):not\(:where\(\.landing-root:not\(\.dark\) \*\)\)\);/,
    );
    // The exclusion is wrapped in :where(...) so it adds ZERO specificity: no `dark:`
    // rule may change precedence anywhere else in the app.
    expect(globalsCssSrc).toMatch(/:not\(:where\(/);
  });

  it('drives the dark palette from the .landing-root class, NOT from <body> .dark (decoupled from the app)', () => {
    // The dark block must be scoped to the root's own class, not `.dark .landing-root`.
    expect(shellSrc).toMatch(/\.landing-root\.dark\s*\{/);
    expect(shellSrc).not.toMatch(/\.dark \.landing-root/);
  });

  it('defaults the public site to LIGHT (LandingThemeProvider)', () => {
    // First visit renders light; the visitor's footer-toggle choice is restored
    // afterwards (landing surfaces pass respectStored).
    expect(providerSrc).toMatch(/defaultTheme = 'light'/);
    // Safe fallback (no provider) is light too.
    expect(providerSrc).toMatch(/theme: 'light'/);
    // Both landing surfaces restore the stored choice.
    // Attribute-by-attribute rather than one literal tag: the element also carries the page
    // language now, and a contract about the DEFAULT THEME should not break because an
    // unrelated attribute was added beside it.
    const provider = landingPageSrc.match(/<LandingThemeProvider[^>]*>/)?.[0] ?? '';
    expect(provider).toContain('className="min-h-screen"');
    expect(provider).toContain('respectStored');
    expect(provider).not.toContain('defaultTheme');
    expect(shellSrc).toMatch(/themeRespectStored = true/);
  });

  it('renders the language select AND the public theme toggle in the FOOTER bottom bar', () => {
    // The footer bottom bar carries both controls right after the copyright line.
    expect(shellSrc).toMatch(
      // Both controls now take their copy as props, so match the tag rather than a bare
      // self-close: what this pins is that the two sit together in the bottom bar, right
      // after the copyright line, not how they are configured.
      /\{labels\.rights\}<\/p>\s*<div[^>]*>\s*<LandingLanguageSelect [^/]*\/>\s*<LandingThemeToggle [^/]*\/>/,
    );
    // Header right-cluster must not carry the language control.
    // Matched on the tag, not on a bare self-close: both controls take props now, so the old
    // `<LandingLanguageSelect />` spelling occurs nowhere and this guard could no longer fail.
    expect(shellSrc).not.toMatch(/<LandingLanguageSelect[^>]*\/>\s*<SignInButton variant="link"/);
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

    // Dark brand marks. They are drawn by ServiceLogo, whose `.svc-logo-*` rules read the
    // `.dark` ancestor exactly like the `dark` variant (so `.landing-root.dark` drives them),
    // never by a CSS filter that would recolour the brand.
    expect(brandMarkSrc).toMatch(/<ServiceLogo/);
    for (const src of [landingPageSrc, brandMarkSrc, shellSrc]) {
      expect(src).not.toMatch(/logo-mono|monoDarkInvertClass/);
    }
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
