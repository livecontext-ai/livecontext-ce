/**
 * Where a hand-positioned floating menu is allowed to sit.
 *
 * Radix menus keep themselves inside the viewport. The app's portalled menus
 * place themselves from a trigger rect instead, and had no such guarantee:
 * anchoring a 192px menu at `rect.left` is fine on a desktop and runs off the
 * right edge of a phone the moment the trigger sits on the right of a card.
 *
 * This is the one clamp for all of them. `usePortalMenu` (the canvas trigger
 * menus) calls in here rather than keeping a clamp of its own, so there is a
 * single place where "inside the screen" is defined. (The builder's info
 * popovers used to place themselves here too; they are Radix popovers now,
 * through `components/ui/info-popover`, and Radix keeps them on screen.)
 */

/** Gutter kept between a menu and the edge of the screen. */
export const MENU_VIEWPORT_MARGIN = 8;

/**
 * The width a `fixed` menu is actually laid out against.
 *
 * `documentElement.clientWidth`, not `window.innerWidth`: a fixed element is
 * positioned against the initial containing block, which EXCLUDES a classic
 * scrollbar, while `window.innerWidth` includes it. Using the window width
 * spends the whole gutter on the scrollbar and parks the menu underneath it -
 * the same trap `TriggerPanel` documents for its own viewport caps.
 */
function viewportWidth(): number | null {
  if (typeof document === 'undefined' && typeof window === 'undefined') return null;
  const client = typeof document !== 'undefined' ? document.documentElement?.clientWidth : 0;
  return client || (typeof window !== 'undefined' ? window.innerWidth : 0) || null;
}

/**
 * Clamp a menu's preferred left offset so the whole menu stays on screen.
 *
 * @param preferredLeft where the menu would like to start (already right- or
 *   left-aligned to its trigger by the caller)
 * @param menuWidth the menu's rendered width in px
 * @returns a left offset inside the viewport gutters. A menu wider than the
 *   screen has no valid position, so it is pinned to the left gutter and its own
 *   `max-width` is what keeps it readable. On the server, where there is no
 *   viewport to clamp against, the preferred offset is returned untouched.
 */
export function clampMenuLeft(
  preferredLeft: number,
  menuWidth: number,
  margin: number = MENU_VIEWPORT_MARGIN,
): number {
  const width = viewportWidth();
  if (width === null) return preferredLeft;
  const maxLeft = width - menuWidth - margin;
  if (maxLeft <= margin) return margin;
  return Math.min(Math.max(preferredLeft, margin), maxLeft);
}

/**
 * The same clamp for a menu that is CENTRED on its trigger (`translateX(-50%)`),
 * where the coordinate being placed is the middle of the menu rather than its
 * left edge.
 *
 * @param preferredCenter the trigger's horizontal midpoint
 * @param menuWidth the menu's rendered width in px
 * @returns a midpoint that puts both edges inside the gutters, or the centre of
 *   the screen when the menu is too wide for any midpoint to do that.
 */
export function clampMenuCenter(
  preferredCenter: number,
  menuWidth: number,
  margin: number = MENU_VIEWPORT_MARGIN,
): number {
  const width = viewportWidth();
  if (width === null) return preferredCenter;
  // Too wide for any midpoint to keep both edges in: centre it, so what is lost
  // is split evenly instead of all falling off one side.
  if (menuWidth + 2 * margin >= width) return width / 2;
  return clampMenuLeft(preferredCenter - menuWidth / 2, menuWidth, margin) + menuWidth / 2;
}
