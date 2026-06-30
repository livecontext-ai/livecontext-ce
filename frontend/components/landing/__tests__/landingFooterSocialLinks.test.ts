import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

// The public landing footer (LandingFooter in LandingShell) carries the company
// social links. It renders OUTSIDE the [locale] tree on several public sub-pages,
// so it must stay intl-context-free; we lock its link set at the source level
// (same approach as landingChromeTheme.test.ts) rather than rendering it.
const shellSrc = readFileSync(path.resolve(__dirname, '../LandingShell.tsx'), 'utf8');

describe('public landing footer social links', () => {
  const expectedHrefs = [
    'https://www.linkedin.com/company/livecontext/',
    'https://x.com/livecontextai',
    'https://www.instagram.com/livecontext.ai/',
    'https://github.com/livecontext-ai',
    'https://www.tiktok.com/@livecontextai',
    'https://discord.gg/5gTuUwhkJ',
  ];

  for (const href of expectedHrefs) {
    it(`links to ${href}`, () => {
      expect(shellSrc).toContain(`href="${href}"`);
    });
  }

  it('labels the Discord community invite for accessibility', () => {
    // The Discord entry must ship an aria-label so it is reachable by name,
    // mirroring the other footer social icons.
    expect(shellSrc).toMatch(
      /href="https:\/\/discord\.gg\/5gTuUwhkJ"[\s\S]*?aria-label="Discord"/,
    );
  });
});
