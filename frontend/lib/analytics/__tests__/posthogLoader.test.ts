// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';
import { loadPosthog } from '../posthogLoader';

describe('loadPosthog', () => {
  beforeEach(() => {
    delete (window as unknown as { posthog?: unknown }).posthog;
    document.head.innerHTML = '';
    document.body.innerHTML = '';
  });

  it('installs a stub on window.posthog and is idempotent', () => {
    const first = loadPosthog('https://us.i.posthog.com');
    expect(first).toBeTruthy();
    expect((window as unknown as { posthog: { __SV: number } }).posthog.__SV).toBe(1);

    const second = loadPosthog('https://us.i.posthog.com');
    expect(second).toBe(first);
  });

  it('does NOT load any script until init() is called', () => {
    loadPosthog('https://us.i.posthog.com');
    expect(document.querySelectorAll('script').length).toBe(0);
  });

  it('init() injects array.js from the assets host', () => {
    const ph = loadPosthog('https://us.i.posthog.com') as unknown as {
      init: (t: string, c: Record<string, unknown>) => void;
    };
    ph.init('phc_test', { api_host: 'https://us.i.posthog.com' });

    const srcs = Array.from(document.querySelectorAll('script')).map((s) => s.getAttribute('src'));
    expect(srcs.some((s) => s?.includes('us-assets.i.posthog.com/static/array.js'))).toBe(true);
  });

  it('queues method calls onto the array for array.js to flush on load', () => {
    const ph = loadPosthog('https://us.i.posthog.com') as unknown as {
      init: (t: string, c: Record<string, unknown>) => void;
      capture: (e: string, p?: Record<string, unknown>) => void;
    };
    ph.init('phc_test', { api_host: 'https://us.i.posthog.com' });
    ph.capture('evt', { a: 1 });

    expect((window as unknown as { posthog: unknown[] }).posthog).toContainEqual(['capture', 'evt', { a: 1 }]);
  });
});
