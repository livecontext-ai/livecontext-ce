// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';

import { leaveForChat } from '../leaveForChat';

/**
 * Leaving the page for the chat.
 *
 * <p>The behaviour is one line; the reason it is a module is the point. Assigning
 * {@code window.location.href} inline is invisible to jsdom, which logs "Not implemented:
 * navigation to another Document" and leaves {@code location} untouched, so a caller that
 * navigates when it should not changes nothing any test can observe. That is not hypothetical: a
 * navigation left behind in the onboarding completion path mounted a panel and left the browser in
 * the same tick, and 45 tests stayed green. Behind a module, a caller's navigation can be asserted.
 */
describe('leaveForChat', () => {
  const realLocation = window.location;

  afterEach(() => {
    Object.defineProperty(window, 'location', { value: realLocation, configurable: true });
    vi.restoreAllMocks();
  });

  /** Replace location with something that records the assignment jsdom would swallow. */
  function captureNavigation(): { get href(): string } {
    const captured = { href: '' };
    Object.defineProperty(window, 'location', { value: captured, configurable: true });
    return captured;
  }

  it('goes to the chat in the locale it was given', () => {
    const location = captureNavigation();

    leaveForChat('fr');

    // The locale travels, because the next screen is the one the person keeps reading: landing
    // an onboarding finished in French on the English chat is a language change nobody asked for.
    expect(location.href).toBe('/fr/app/chat');
  });

  it('is a full document load, not a client-side path change', () => {
    const location = captureNavigation();

    leaveForChat('en');

    // A router push would carry the current tree across. The next screen reads a fresh session, a
    // fresh organization and the hand-off flags written a moment earlier, so it has to reload.
    expect(location.href).toBe('/en/app/chat');
  });
});
