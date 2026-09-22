/**
 * Has the credit ring's opening reveal already played on this page load?
 *
 * WHY THIS IS NOT COMPONENT STATE. `AppSidebar` renders `SidebarCreditRing`
 * from BOTH arms of its `sidebarCollapsed ? ... : ...` ternary - the 44px
 * button in the collapsed rail, and the avatar inside the user row when it is
 * open. React reconciles by POSITION, so those are two different components as
 * far as it is concerned: collapsing the sidebar unmounts one and mounts the
 * other, and any state living inside the ring is destroyed with it. Restructuring
 * the two arms into one would not help either, since the parent chains differ.
 *
 * Without a memory outside the component, every sidebar toggle replayed the
 * whole 1.3-second reveal.
 *
 * WHY A MODULE, AND NOT sessionStorage. The lifetime wanted here is exactly one
 * page load: "when you arrive on the app, the ring draws itself". A module
 * binding has that lifetime for free, and a reload - which is arriving again -
 * resets it. `sessionStorage` would outlive the visit and the animation would
 * never be seen twice, which is a different (and wrong) rule.
 *
 * It is deliberately NOT part of the render path: nothing re-renders when it
 * flips, it is only read once as a component mounts.
 */

let played = false;

/** True once the reveal has run on this page load. */
export function creditRingRevealPlayed(): boolean {
  return played;
}

/** Called by the ring the first time it mounts with a wallet to show. */
export function markCreditRingRevealPlayed(): void {
  played = true;
}

/**
 * Test seam. The flag's scope is one page load, and a test file is one "page":
 * without this, the second test to render a ring would silently get the
 * no-animation path and assert nothing about the reveal. Call it in `beforeEach`.
 */
export function resetCreditRingRevealForTests(): void {
  played = false;
}
