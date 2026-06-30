// Resolve the bridge inactivity watchdog window (ms) for an agent run.
//
// Precedence: a per-agent override on the credentials map (__inactivityTimeoutSeconds__, set by the
// orchestrator / agent-service producers from the agent's inactivity_timeout column) > the DTO
// inactivityTimeout field > the 5-minute default. secs <= 0 disables the watchdog (returns 0); a
// blank or non-numeric value falls back to the default. Mirrors the Java consumer
// AgentLoopService.resolveInactivityWindowMs so the two execution paths agree.

export const DEFAULT_INACTIVITY_MS = 5 * 60 * 1000;

export function resolveInactivityMs(inactivityTimeout, credentials) {
  const credSecs = credentials && credentials.__inactivityTimeoutSeconds__;
  const secs = (credSecs != null && credSecs !== '')
    ? Number(credSecs)
    : (typeof inactivityTimeout === 'number' ? inactivityTimeout : null);
  if (secs == null || Number.isNaN(secs)) return DEFAULT_INACTIVITY_MS;
  return secs > 0 ? secs * 1000 : 0;
}
