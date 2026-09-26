/**
 * Who owns an Escape key press.
 *
 * Several surfaces react to Escape at the document level (the side panel leaves full screen,
 * the chat stops its stream), while the app's overlays (menus, Selects, dialogs, popovers)
 * dismiss themselves on the same key. Radix layers mark the event (`defaultPrevented`) from a
 * capture listener, but the app also has hand-rolled modals that do not, so a surface checks
 * both: the flag, and whether an overlay is open on top of it.
 */

/**
 * The roles the app's overlay primitives render, i.e. the layers that own the Escape key
 * while they are open: modals and Popover content (`dialog` / `alertdialog`), Select
 * (`listbox`) and DropdownMenu (`menu`).
 */
export const OVERLAY_ROLES = '[role="dialog"], [role="alertdialog"], [role="listbox"], [role="menu"]';

/**
 * The element's own top-level ancestor under `document.body`, i.e. the app tree.
 *
 * Anything the app renders in place sits inside it; anything portalled (every Radix overlay)
 * is a sibling of it. That is the only line that separates "on top of this surface" from
 * "hidden behind it" without measuring pixels.
 */
export function appRootOf(el: HTMLElement | null): HTMLElement | null {
  let node: HTMLElement | null = el;
  while (node?.parentElement && node.parentElement !== document.body) {
    node = node.parentElement;
  }
  return node;
}

/**
 * Whether an open overlay, rather than the surface at `anchor`, owns this Escape.
 *
 * Two kinds of overlay are really on top: one portalled out of the app tree (Radix mounts
 * those as direct children of `document.body`), and, when `container` is given, one rendered
 * inside it. An overlay elsewhere in the app tree is behind the surface and owns nothing.
 *
 * @param anchor    an element of the surface asking, used to find the app root
 * @param container the surface's own box, whose in-place overlays also count; null to count
 *                  portalled overlays only
 */
export function isEscapeOwnedByOverlay(anchor: HTMLElement | null, container: HTMLElement | null = null): boolean {
  const appRoot = appRootOf(anchor);
  return [...document.querySelectorAll(OVERLAY_ROLES)].some((el) => (
    (container?.contains(el) ?? false) || !appRoot?.contains(el)
  ));
}
