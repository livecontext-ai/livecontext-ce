// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mockUseChangelog = vi.hoisted(() => vi.fn());
const mockTrack = vi.hoisted(() => vi.fn());
vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => mockTrack(...a) }));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => {
    const copy: Record<string, string> = {
      whatsNew: "What's new",
      dismiss: 'Got it',
      learnMore: 'See all updates',
      'latest.title': 'See what changed',
      'latest.body': 'One entry, the newest, shown once.',
      'latest.mediaAlt': 'The panel illustration',
      'latestCloud.title': 'The partner program is open',
      'latestCloud.body': 'Earn a share of your clients invoices.',
      'latestCloud.mediaAlt': 'The partner illustration',
      'latestCloud.action': 'Become a partner',
    };
    return copy[key] ?? key;
  },
}));

import ChangelogModal from '../ChangelogModal';
import { WELCOME_GIFT_FLAG } from '@/lib/onboarding/welcomeGiftHandoff';

vi.mock('@/hooks/useChangelog', () => ({
  useChangelog: () => mockUseChangelog(),
}));

const ENTRY = {
  key: '2026-09-whats-new',
  publishedAt: '2026-09-07',
  media: { type: 'image' as const, src: '/changelog/x.svg', width: 1200, height: 630 },
  learnMoreUrl: '/changelog',
  copy: 'latest' as const,
  action: null,
};

const CLOUD_ENTRY = {
  ...ENTRY,
  key: '2026-10-partner-program',
  copy: 'latestCloud' as const,
  action: { href: '/partners' },
};

describe('ChangelogModal', () => {
  let markSeen: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    sessionStorage.clear();
    markSeen = vi.fn();
    mockUseChangelog.mockReset();
    mockUseChangelog.mockReturnValue({
      entry: ENTRY, decision: 'announce', markSeen, isAvailable: true, isLoading: false,
    });
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    sessionStorage.clear();
  });

  const settle = () => act(() => { vi.advanceTimersByTime(1500); });

  it('opens by itself for a user who has not acknowledged the entry', () => {
    render(<ChangelogModal />);

    // Not immediately: the panel waits for the app shell to settle rather than landing on top of
    // a page that is still mounting.
    expect(screen.queryByText('See what changed')).not.toBeInTheDocument();

    settle();

    expect(screen.getByText('See what changed')).toBeInTheDocument();
    expect(screen.getByText('One entry, the newest, shown once.')).toBeInTheDocument();
    expect(screen.getByAltText('The panel illustration')).toHaveAttribute('src', '/changelog/x.svg');
  });

  it('acknowledges the entry as soon as it is SHOWN, not only when it is closed', () => {
    render(<ChangelogModal />);
    settle();

    // "Shown once per user" has to mean shown: a reader who closes the tab, or navigates away,
    // never reaches the close path, and the panel would greet them again on every load forever.
    expect(screen.getByText('See what changed')).toBeInTheDocument();
    expect(markSeen).toHaveBeenCalled();
  });

  it('reports changelog_shown exactly once per entry, even under StrictMode', () => {
    mockTrack.mockReset();
    render(<React.StrictMode><ChangelogModal /></React.StrictMode>);
    settle();

    expect(mockTrack.mock.calls.filter(([e]) => e === 'changelog_shown'))
      .toEqual([['changelog_shown', { entry_key: ENTRY.key, has_media: true }]]);
  });

  it('reports how the panel was closed: dismiss or learn_more', () => {
    mockTrack.mockReset();
    const first = render(<ChangelogModal />);
    settle();
    fireEvent.click(screen.getByTestId('changelog-dismiss'));
    expect(mockTrack).toHaveBeenCalledWith('changelog_closed', { entry_key: ENTRY.key, has_media: true, action: 'dismiss' });
    first.unmount();

    mockTrack.mockReset();
    render(<ChangelogModal />);
    settle();
    fireEvent.click(screen.getByText('See all updates'));
    expect(mockTrack).toHaveBeenCalledWith('changelog_closed', { entry_key: ENTRY.key, has_media: true, action: 'learn_more' });
  });

  it('reports nothing for an entry that is never shown', () => {
    mockTrack.mockReset();
    mockUseChangelog.mockReturnValue({ entry: ENTRY, decision: 'seal', markSeen, isAvailable: true, isLoading: false });
    render(<ChangelogModal />);
    settle();

    expect(mockTrack).not.toHaveBeenCalled();
  });

  it('acknowledges the entry when dismissed, so it never opens again', () => {
    render(<ChangelogModal />);
    settle();

    fireEvent.click(screen.getByTestId('changelog-dismiss'));

    expect(markSeen).toHaveBeenCalled();
    expect(screen.queryByText('See what changed')).not.toBeInTheDocument();
  });

  it('acknowledges the entry when the user leaves through "see all updates"', () => {
    render(<ChangelogModal />);
    settle();

    fireEvent.click(screen.getByText('See all updates'));

    // The user has seen the entry, whatever they do next with it.
    expect(markSeen).toHaveBeenCalled();
  });

  it('acknowledges when the dialog is closed through Escape, not only through the button', () => {
    render(<ChangelogModal />);
    settle();
    expect(screen.getByText('See what changed')).toBeInTheDocument();

    // Escape and the backdrop close through onOpenChange, a path no button click exercises. The
    // whole "no way to dismiss it that leaves it coming back" property lives there, so deleting
    // that prop must fail a test.
    act(() => { fireEvent.keyDown(document, { key: 'Escape', code: 'Escape' }); });

    expect(markSeen).toHaveBeenCalled();
    expect(screen.queryByText('See what changed')).not.toBeInTheDocument();
  });

  it('stays closed when the entry was already acknowledged', () => {
    mockUseChangelog.mockReturnValue({
      entry: ENTRY, decision: 'hidden', markSeen, isAvailable: true, isLoading: false,
    });

    render(<ChangelogModal />);
    settle();

    expect(screen.queryByText('See what changed')).not.toBeInTheDocument();
    expect(markSeen).not.toHaveBeenCalled();
  });

  it('acknowledges WITHOUT showing anything for an account the server sealed', () => {
    mockUseChangelog.mockReturnValue({
      entry: ENTRY, decision: 'seal', markSeen, isAvailable: true, isLoading: false,
    });

    render(<ChangelogModal />);
    settle();

    expect(markSeen).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('See what changed')).not.toBeInTheDocument();
  });

  it.each([WELCOME_GIFT_FLAG, 'lc_show_app_suggestions'])(
    'does not stack on top of an onboarding modal still queued (%s)',
    (flag) => {
      // A first run is a guided sequence; a release note has no business interrupting it. The hook
      // keeps the panel quiet during onboarding itself, and these flags cover the modals queued
      // right after it. Both are checked because they are queued together and either can be the
      // one still standing: the welcome gift stays flagged for as long as the reader reads it.
      //
      // The flag comes from the module that WRITES it, not from a literal. Spelled out, this case
      // went on passing after the gift modal was replaced, asserting a contract no producer could
      // break any more.
      sessionStorage.setItem(flag, '1');

      render(<ChangelogModal />);
      settle();

      expect(screen.queryByText('See what changed')).not.toBeInTheDocument();
    },
  );

  it('does not stack on the suggested-apps modal either', () => {
    sessionStorage.setItem('lc_show_app_suggestions', '1');

    render(<ChangelogModal />);
    settle();

    expect(screen.queryByText('See what changed')).not.toBeInTheDocument();
  });

  it('still announces when sessionStorage cannot be read at all', () => {
    // Private mode / storage disabled: assuming "onboarding in flight" there would suppress the
    // panel forever on a browser that simply cannot answer the question.
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });

    render(<ChangelogModal />);
    settle();

    expect(screen.getByText('See what changed')).toBeInTheDocument();
    getItem.mockRestore();
  });

  it('shows nothing when the deployment reports the feature as off', () => {
    mockUseChangelog.mockReturnValue({
      entry: ENTRY, decision: 'announce', markSeen, isAvailable: false, isLoading: false,
    });

    render(<ChangelogModal />);
    settle();

    expect(screen.queryByText('See what changed')).not.toBeInTheDocument();
  });

  it('renders nothing at all when this build ships no entry', () => {
    mockUseChangelog.mockReturnValue({
      entry: null, decision: 'hidden', markSeen, isAvailable: false, isLoading: false,
    });

    const { container } = render(<ChangelogModal />);
    settle();

    expect(container).toBeEmptyDOMElement();
  });

  it('renders a text-only entry when it carries no media', () => {
    mockUseChangelog.mockReturnValue({
      entry: { ...ENTRY, media: null }, decision: 'announce', markSeen, isAvailable: true, isLoading: false,
    });

    render(<ChangelogModal />);
    settle();

    expect(screen.getByText('See what changed')).toBeInTheDocument();
    expect(screen.queryByAltText('The panel illustration')).not.toBeInTheDocument();
  });

  it('hides the learn-more link when the entry has none', () => {
    mockUseChangelog.mockReturnValue({
      entry: { ...ENTRY, learnMoreUrl: null }, decision: 'announce', markSeen, isAvailable: true, isLoading: false,
    });

    render(<ChangelogModal />);
    settle();

    expect(screen.queryByText('See all updates')).not.toBeInTheDocument();
    expect(screen.getByTestId('changelog-dismiss')).toBeInTheDocument();
  });

  /**
   * Regression: a long entry clipped its own footer.
   *
   * DialogContent is a `grid` capped at `max-h-[90vh]`, and this panel passes
   * `overflow-hidden` (it must: the media is flush to the rounded corners and would
   * otherwise square them off), which twMerge-overrides the base `overflow-y-auto`.
   * With no scroll region of its own the middle row could not be clamped, so on a
   * short window the entry overflowed the cap and the reader lost part of the footer.
   * Measured on the shipped copy before the fix: the German, French and Portuguese
   * entries overflowed a 640px-tall viewport by 17px.
   *
   * Asserted on the CLASSES rather than on measured geometry because jsdom has no
   * layout: it computes no viewport, no `vh`, and every height is zero, so a pixel
   * assertion here would pass whatever the component does. `min-h-0` is the half that
   * is easy to drop as redundant-looking and is what lets a grid row shrink below its
   * content, so it is named explicitly.
   */
  it('lets the body scroll, so a long entry cannot clip the footer out of reach', () => {
    render(<ChangelogModal />);
    settle();

    const body = screen.getByText('One entry, the newest, shown once.').parentElement;
    expect(body).toHaveClass('overflow-y-auto');
    expect(body).toHaveClass('min-h-0');
  });

  it('keeps the line breaks of a long entry written as paragraphs and a list', () => {
    render(<ChangelogModal />);
    settle();

    expect(screen.getByText('One entry, the newest, shown once.')).toHaveClass('whitespace-pre-line');
  });

  describe('an entry with its own copy block and a call to action (the cloud partner entry)', () => {
    beforeEach(() => {
      mockUseChangelog.mockReturnValue({
        entry: CLOUD_ENTRY, decision: 'announce', markSeen, isAvailable: true, isLoading: false,
      });
    });

    it('reads its words from the block the entry names, not from the self-hosted one', () => {
      render(<ChangelogModal />);
      settle();

      expect(screen.getByText('The partner program is open')).toBeInTheDocument();
      expect(screen.getByText('Earn a share of your clients invoices.')).toBeInTheDocument();
      expect(screen.getByAltText('The partner illustration')).toBeInTheDocument();
      expect(screen.queryByText('See what changed')).not.toBeInTheDocument();
    });

    it('draws the call to action in the partner gold, opening the in-app path in a new tab', () => {
      render(<ChangelogModal />);
      settle();

      const action = screen.getByTestId('changelog-action');
      expect(action).toHaveTextContent('Become a partner');
      expect(action).toHaveAttribute('href', '/partners');
      expect(action).toHaveAttribute('target', '_blank');
      expect(action).toHaveAttribute('rel', 'noopener noreferrer');
      // The gold of the partner program (PARTNER_GOLD_BG), not the neutral fallback.
      expect(action.getAttribute('style')).toContain('linear-gradient');
    });

    it('acknowledges and reports the entry when the reader follows the call to action', () => {
      mockTrack.mockReset();
      render(<ChangelogModal />);
      settle();
      // The open effect has already acknowledged; the click path must acknowledge on its own.
      markSeen.mockClear();

      fireEvent.click(screen.getByTestId('changelog-action'));

      expect(markSeen).toHaveBeenCalled();
      expect(mockTrack).toHaveBeenCalledWith('changelog_closed', { entry_key: CLOUD_ENTRY.key, has_media: true, action: 'cta' });
      expect(screen.queryByText('The partner program is open')).not.toBeInTheDocument();
    });

    it('puts the call to action in the footer, where a short window cannot scroll it away', () => {
      render(<ChangelogModal />);
      settle();

      // Same row as the dismiss button, outside the scroll region that holds the long text.
      const action = screen.getByTestId('changelog-action');
      const dismiss = screen.getByTestId('changelog-dismiss');
      expect(action.parentElement).toBe(dismiss.parentElement);
      expect(action.closest('.overflow-y-auto')).toBeNull();
    });

    it('lets "Got it" step back to a secondary button next to the call to action', () => {
      render(<ChangelogModal />);
      settle();

      // The outline variant: the gold button is the one primary action of the panel.
      expect(screen.getByTestId('changelog-dismiss').className).toContain('bg-transparent');
    });
  });

  it('draws no call to action for an entry that has none, and keeps "Got it" primary', () => {
    render(<ChangelogModal />);
    settle();

    expect(screen.queryByTestId('changelog-action')).not.toBeInTheDocument();
    expect(screen.getByTestId('changelog-dismiss').className).not.toContain('bg-transparent');
  });
});
