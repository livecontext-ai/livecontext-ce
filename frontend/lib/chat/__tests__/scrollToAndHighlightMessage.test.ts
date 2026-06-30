/**
 * @vitest-environment jsdom
 *
 * The "go to message" jump helper: scrolls to the message and flashes a ring on
 * its user bubble (the rounded-[18px] element, else the row), removing the class
 * on animationend so a re-jump restarts the animation.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { scrollToAndHighlightMessage } from '@/lib/chat/messageActivity';

describe('scrollToAndHighlightMessage', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    // jsdom does not implement scrollIntoView.
    Element.prototype.scrollIntoView = vi.fn();
  });

  it('scrolls to the message and flashes the user bubble, clearing it on animationend', () => {
    document.body.innerHTML = '<div id="message-m1"><div class="rounded-[18px] p-4">hi</div></div>';
    const row = document.getElementById('message-m1') as HTMLElement;
    const bubble = row.querySelector('[class*="rounded-[18px]"]') as HTMLElement;

    scrollToAndHighlightMessage('m1');

    expect(row.scrollIntoView).toHaveBeenCalledTimes(1);
    // The bubble (not the row) gets the highlight.
    expect(bubble.classList.contains('message-jump-highlight')).toBe(true);
    expect(row.classList.contains('message-jump-highlight')).toBe(false);

    // Animation end removes the class so a future jump can re-trigger it.
    bubble.dispatchEvent(new Event('animationend'));
    expect(bubble.classList.contains('message-jump-highlight')).toBe(false);
  });

  it('falls back to the row when there is no rounded bubble inside', () => {
    document.body.innerHTML = '<div id="message-m2">plain</div>';
    const row = document.getElementById('message-m2') as HTMLElement;

    scrollToAndHighlightMessage('m2');

    expect(row.classList.contains('message-jump-highlight')).toBe(true);
  });

  it('is a no-op (no throw) when the message is not in the DOM', () => {
    expect(() => scrollToAndHighlightMessage('does-not-exist')).not.toThrow();
  });
});
