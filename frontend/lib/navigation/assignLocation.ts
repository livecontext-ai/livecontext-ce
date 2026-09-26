/**
 * Full document navigation to a URL (same origin or not).
 *
 * <p>A module for the same reason as {@link leaveForChat}: jsdom cannot perform (or let a test
 * observe) {@code window.location.assign}, so a navigation written inline is invisible to every
 * test. Through a module, a test mocks it and asserts where the page went, and when.
 */
export function assignLocation(url: string): void {
  if (typeof window === 'undefined') return;
  window.location.assign(url);
}
