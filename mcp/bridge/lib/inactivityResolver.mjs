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
  if (credSecs != null && credSecs !== '') {
    const secs = Number(credSecs);
    if (!Number.isNaN(secs)) {
      // Credential-channel contract (parity with AgentLoopService.resolveInactivityWindowMs):
      // 0 = disabled, 10-7200 = custom window. Out-of-contract values (negative, 1-9, > 7200)
      // are ignored so a stray small value cannot arm a seconds-scale watchdog.
      if (secs === 0) return 0;
      if (secs >= 10 && secs <= 7200) return secs * 1000;
    }
    // malformed or out-of-contract credential -> fall through to the DTO field / default
  }
  const fieldSecs = typeof inactivityTimeout === 'number' ? inactivityTimeout : null;
  if (fieldSecs == null || Number.isNaN(fieldSecs)) return DEFAULT_INACTIVITY_MS;
  return fieldSecs > 0 ? fieldSecs * 1000 : 0;
}
