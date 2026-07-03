// Inactivity watchdog for a spawned CLI child.
//
// The bridge kills a CLI that emits no stdout for the configured window and ends the
// run as INACTIVITY_TIMEOUT (distinct from the total-wall-clock TIMEOUT). This module
// owns ONLY the timer mechanics so they are behaviorally testable
// (test/inactivityWatchdogBehavior.test.mjs spawns a real silent child); the kill +
// sentinel side effects stay in the caller's onTrip closure (server.mjs).
//
// Contract (mirrors resolveInactivityMs / AgentLoopService.resolveInactivityWindowMs):
//   - inactivityMs <= 0 (or missing) disables the watchdog: reset() is a no-op and
//     onTrip can never fire.
//   - reset() (re)arms the timer; callers arm it at spawn (a CLI that never emits
//     anything is caught too) and re-arm it on every stdout line (any output means
//     the CLI is alive).
//   - clear() disarms without firing (child close/error paths).
//   - onTrip fires at most once per arming; a fired timer does not re-arm itself.
export function createInactivityWatchdog(inactivityMs, onTrip) {
  let timer = null;
  const clear = () => {
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
  };
  const reset = () => {
    if (!inactivityMs || inactivityMs <= 0) return;
    clear();
    timer = setTimeout(() => {
      timer = null;
      onTrip();
    }, inactivityMs);
  };
  return { reset, clear, isArmed: () => timer !== null };
}
