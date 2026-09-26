/**
 * Leave the current page for the chat, with a full document load.
 *
 * <p>A full load rather than a router push, because the callers are finishing onboarding: the
 * next screen reads a fresh session, a fresh organization and the hand-off flags written a moment
 * earlier, and a client-side transition would carry the old tree across.
 *
 * <p><b>It exists as a module so that it can be observed.</b> Assigning {@code
 * window.location.href} inline is invisible to jsdom, which logs "Not implemented: navigation to
 * another Document" and leaves {@code location} untouched. A stray call to it therefore changes
 * nothing a test can see: that is exactly how a navigation left behind in the onboarding
 * completion path shipped a panel the browser left in the same tick it was mounted, with 45 tests
 * green. Through a module, a test mocks it and asserts whether leaving happened and when.
 *
 * @param locale the active app locale, so the chat opens in the language the person is using
 */
export function leaveForChat(locale: string): void {
  if (typeof window === 'undefined') return;
  window.location.href = `/${locale}/app/chat`;
}
