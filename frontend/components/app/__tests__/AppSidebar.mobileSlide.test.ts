/**
 * The mobile left drawer must SLIDE open and closed, as the right side panel animates.
 *
 * It used to be `hidden` while closed and `absolute` while open: `display` cannot
 * transition, so the drawer popped in with no motion. Closed, it now stays rendered
 * off-canvas (`-translate-x-full`) and invisible, and the transform is transitioned.
 * The desktop (md:) column must keep its layout: static when the mobile flag is off,
 * relative when on, never translated, and animated on its width like the right side panel.
 */
import { describe, expect, it, vi } from 'vitest';

// Importing the module loads the whole shell: stub what it pulls from the runtime.
vi.mock('@/hooks/useAppVersion', () => ({
  useAppVersion: () => ({ version: null, isLoading: false, isError: false }),
}));
vi.mock('next-intl', () => ({ useLocale: () => 'en', useTranslations: () => (k: string) => k }));
vi.mock('@/i18n/navigation', () => ({
  usePathname: () => '/en/app/chat',
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));

import { appSidebarClasses } from '../AppSidebar';

const classes = (open: boolean, collapsed = false) => appSidebarClasses(open, collapsed).split(/\s+/);

describe('appSidebarClasses (mobile slide)', () => {
  it('keeps the closed drawer rendered off-canvas instead of display:none', () => {
    const closed = classes(false);
    expect(closed).not.toContain('hidden');
    expect(closed).toContain('-translate-x-full');
    expect(closed).toContain('invisible');
  });

  it('brings the open drawer on screen, same width as when closed so only the position moves', () => {
    const open = classes(true);
    expect(open).toContain('translate-x-0');
    expect(open).not.toContain('invisible');
    expect(open).toContain('w-64');
    expect(classes(false)).toContain('w-64');
  });

  it('transitions translate (Tailwind v4), the inline drag transform, and visibility', () => {
    expect(classes(false)).toContain('transition-[translate,transform,visibility]!');
  });

  // Regression: globals.css has an UNLAYERED `* { transition-property: color, ..., transform, ... }`
  // rule, which beats every Tailwind utility. Without the important modifier the real computed
  // transition list had neither `width` nor `translate`: measured in Chromium, the desktop
  // collapse and the mobile slide both jumped in a single frame.
  it('marks every transition utility important so the global * transition rule cannot override it', () => {
    for (const open of [true, false]) {
      for (const collapsed of [true, false]) {
        const transitionUtilities = classes(open, collapsed).filter((c) => /^(md:)?(transition|duration|ease)-/.test(c));
        expect(transitionUtilities.length).toBeGreaterThan(0);
        for (const c of transitionUtilities) expect(c.endsWith('!'), c).toBe(true);
      }
    }
  });

  it('leaves the desktop column untranslated, visible and width-animated', () => {
    for (const open of [true, false]) {
      const c = classes(open);
      // Width, 300 ms ease-in-out: the same motion as the right side panel.
      expect(c).toEqual(expect.arrayContaining(['md:translate-x-0', 'md:visible', 'md:transition-[width]!', 'duration-300!', 'md:ease-in-out!']));
    }
    expect(classes(false, true)).toContain('md:w-16');
    expect(classes(false, false)).toContain('md:w-64');
    expect(classes(false)).toContain('md:static');
    expect(classes(true)).toContain('md:relative');
    expect(classes(false)).toEqual(expect.arrayContaining(['md:inset-auto', 'md:z-auto']));
    expect(classes(true)).toContain('md:inset-auto');
  });

  it('keeps vertical scrolling and pinch-zoom native on mobile, and resets touch-action on desktop', () => {
    expect(classes(true)).toEqual(expect.arrayContaining(['touch-pan-y', 'touch-pinch-zoom', 'md:touch-auto']));
  });
});
