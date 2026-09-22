import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import path from 'path';

const read = (rel: string) => readFileSync(path.resolve(__dirname, '../../../', rel), 'utf8');

// The docs used to mount a SECOND theme toggle in the shared header, on top of the
// footer one that every public page already carries. It was the only header control
// of its kind on the whole public site, so the docs navbar read differently from
// /about, /changelog and the landing. It is gone; these assertions keep it gone.
//
// The DocsThemeToggle component itself has since been deleted too: the blog
// pages were its last consumers and the blog was removed.
describe('docs header carries no theme toggle', () => {
  const layoutSrc = read('app/docs/layout.tsx');

  it('does not mount DocsThemeToggle in the docs shell', () => {
    expect(layoutSrc).not.toMatch(/DocsThemeToggle/);
  });

  it('passes no headerExtra to LandingShell, so the docs navbar matches the rest of the public site', () => {
    expect(layoutSrc).not.toMatch(/headerExtra/);
  });

  it('still owns a decoupled docs theme, now switched from the footer toggle', () => {
    // Removing the button must not couple the docs back to the marketing theme:
    // the docs keep their own provider + storage key, the footer toggle drives it.
    expect(layoutSrc).toMatch(/themeStorageKey="docs-theme"/);
    expect(layoutSrc).toMatch(/themeRespectStored/);
  });
});
