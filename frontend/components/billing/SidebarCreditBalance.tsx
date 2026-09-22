'use client';

/**
 * The wallet in the sidebar, in two pieces that share one hook.
 *
 * {@link SidebarCreditRing} is the always-visible signal: a ring around the
 * user's avatar, discreet inside the monthly grant and gold above it. Nothing
 * is added to the top bar, so this ring is the whole indicator. It draws itself
 * when the reader arrives, once per visit - this component is what remembers
 * that, because `AppSidebar` mounts it from both arms of its collapsed/expanded
 * ternary and the ring itself cannot survive the toggle.
 *
 * {@link SidebarCreditMenuSection} is the detail, and it lives at the top of
 * the user menu that the avatar opens. It used to be a hover card; a menu is a
 * better home for it, because reaching the figures no longer depends on
 * hovering a 44px target and holding still.
 *
 * CLOUD ONLY: CE bills in dollars against no monthly grant, so there is no
 * denominator for a percentage. Both pieces self-gate rather than trusting
 * their caller.
 */

import React, { useEffect, useState } from 'react';
import { useCreditWallet } from '@/lib/hooks/useCreditWallet';
import { IS_CE } from '@/lib/edition';
import {
  creditRingRevealPlayed,
  markCreditRingRevealPlayed,
} from '@/lib/billing/credit-ring-reveal';
import { CreditAvatarRing, CreditBalancePanel, useCreditTriggerLabel } from './CreditBalance';

/**
 * How much clear space sits between the avatar and the ring, per side.
 *
 * With a 32px avatar this makes the whole block 44px, which is `w-11` on the
 * spacing scale - the reason it is 6 and not an arbitrary number. The callers
 * size their button from the same total, so the ring is INSIDE the button's
 * content box rather than spilling out of it.
 */
export const CREDIT_RING_GAP = 6;
export const CREDIT_RING_AVATAR = 32;
export const CREDIT_RING_BOX = CREDIT_RING_AVATAR + CREDIT_RING_GAP * 2;

export function SidebarCreditRing({
  avatarSize = CREDIT_RING_AVATAR,
  gap = CREDIT_RING_GAP,
  children,
}: {
  avatarSize?: number;
  gap?: number;
  children: React.ReactNode;
}) {
  const { balance, allowance, gauge, isLoading } = useCreditWallet();
  const hasAllowance = allowance !== null;
  // Above the early returns: it is a hook. `amountVisible` is false because
  // this surface shows no number - the ring is the only visible content.
  const label = useCreditTriggerLabel({ balance, allowance, gauge, amountVisible: false });

  /*
   * Latched into state at mount, rather than read on every render.
   *
   * NOT because a later read would interrupt anything - it would not, since the
   * ring consumes `animate` only to seed its phase and ignores it afterwards.
   * The latch is so that this component's answer does not depend on knowing
   * that: the flag flips under it the moment the effect below runs, and a
   * component whose prop silently contradicts its own state one render later is
   * a trap for whoever reads it next.
   */
  const [animate] = useState(() => !creditRingRevealPlayed());
  const showRing = !IS_CE && !isLoading && balance !== null && hasAllowance;
  useEffect(() => {
    // Marked when a ring is actually on screen, not merely when this component
    // mounted: the wallet resolves after the sidebar does, and marking early
    // would spend the reveal on the frames where there was nothing to reveal.
    if (showRing) markCreditRingRevealPlayed();
  }, [showRing]);

  const box = avatarSize + gap * 2;

  /*
   * The wrapper RESERVES the ring's space instead of letting the ring overflow.
   *
   * The ring used to be an overlay pulled out by negative offsets, which meant
   * it lived outside the avatar's box and therefore outside the button's
   * content box too: no amount of padding on that button could contain it, and
   * in the collapsed rail it escaped a 32px button entirely. Sizing the wrapper
   * to the full ring and centring the avatar inside it puts everything back
   * inside normal layout, so the button's padding behaves like padding again.
   */
  const frame = (
    <span
      className="relative inline-flex flex-shrink-0 items-center justify-center"
      style={{ width: box, height: box }}
    >
      {children}
    </span>
  );

  // No ring, but the avatar still has to be here. There is no skeleton state
  // either: a 0% ring would read as a statement about the account rather than
  // as loading, and a ring where no denominator is knowable would claim
  // "0% used", which is precisely what we could not read.
  if (!showRing) return frame;

  return (
    <span
      data-testid="sidebar-credit-avatar"
      aria-label={label}
      className="relative inline-flex flex-shrink-0 items-center justify-center"
      style={{ width: box, height: box }}
    >
      {children}
      <CreditAvatarRing
        percent={gauge.fillPct}
        gold={gauge.isOver}
        avatarSize={avatarSize}
        gap={gap}
        animate={animate}
      />
    </span>
  );
}

/**
 * The figures, as the first section of the user menu.
 *
 * Rendered above every menu group and before the first separator, so the
 * balance is the first thing the menu says. The menu's own "Credits" and
 * "Pricing" rows were removed as this grew: an entry that navigates to the
 * usage page is redundant next to a readout that IS the link there, and the
 * Upgrade CTA in this section is the cloud chrome's only upsell.
 */
export function SidebarCreditMenuSection({
  viewUsage,
  onUpgrade,
}: {
  /** Where the readout leads: a URL for the browser, a handler for the app. */
  viewUsage: { href: string; onNavigate: () => void };
  onUpgrade: () => void;
}) {
  const { balance, subBalance, paygBalance, allowance, gauge, isLoading } = useCreditWallet();

  if (IS_CE) return null;
  if (isLoading || balance === null) return null;

  return (
    <div data-testid="sidebar-credit-menu-section" className="px-1.5 pt-1 pb-2">
      <CreditBalancePanel
        balance={balance}
        allowance={allowance}
        gauge={gauge}
        subBalance={subBalance}
        paygBalance={paygBalance}
        onUpgrade={onUpgrade}
        viewUsage={viewUsage}
      />
    </div>
  );
}
