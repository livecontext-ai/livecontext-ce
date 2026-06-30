/**
 * Advanced turn-limit overrides (V100 AgentEntity columns) shared by the agent
 * create/edit modal. POST and PUT /agents both accept these three top-level fields;
 * the backend `applyGuardOverrides` uses `containsKey` semantics - a present key is
 * set, an ABSENT key keeps the column untouched (NULL → YAML defaults). So the modal
 * must send a field ONLY when the user actually changed it from its hydrated value,
 * otherwise an untouched agent would be pinned to the UI defaults.
 */

export interface TurnLimits {
  maxPerResourcePerTurn: number;
  loopIdenticalStop: number;
  loopConsecutiveStop: number;
}

/** UI defaults - mirror the backend YAML defaults (and ChatConfigPanel's display defaults). */
export const TURN_LIMIT_DEFAULTS: TurnLimits = {
  maxPerResourcePerTurn: 5,
  loopIdenticalStop: 15,
  loopConsecutiveStop: 40,
};

type AgentTurnLimitColumns = {
  maxPerResourcePerTurn?: number | null;
  loopIdenticalStop?: number | null;
  loopConsecutiveStop?: number | null;
};

/**
 * Resolve the initial (hydrate-on-edit) turn-limit values from an agent's stored
 * columns, falling back to the UI defaults for a null/absent column (create, or an
 * agent that never overrode them).
 */
export function initialTurnLimits(agent: AgentTurnLimitColumns | null | undefined): TurnLimits {
  return {
    maxPerResourcePerTurn: agent?.maxPerResourcePerTurn ?? TURN_LIMIT_DEFAULTS.maxPerResourcePerTurn,
    loopIdenticalStop: agent?.loopIdenticalStop ?? TURN_LIMIT_DEFAULTS.loopIdenticalStop,
    loopConsecutiveStop: agent?.loopConsecutiveStop ?? TURN_LIMIT_DEFAULTS.loopConsecutiveStop,
  };
}

/**
 * The subset of turn-limit fields that changed from their initial value - exactly the
 * keys to merge into the agent create/update payload. An untouched field is omitted so
 * the backend leaves its column as-is (create: NULL → YAML default; edit: prior value).
 */
export function buildChangedTurnLimits(current: TurnLimits, initial: TurnLimits): Partial<TurnLimits> {
  const out: Partial<TurnLimits> = {};
  if (current.maxPerResourcePerTurn !== initial.maxPerResourcePerTurn) out.maxPerResourcePerTurn = current.maxPerResourcePerTurn;
  if (current.loopIdenticalStop !== initial.loopIdenticalStop) out.loopIdenticalStop = current.loopIdenticalStop;
  if (current.loopConsecutiveStop !== initial.loopConsecutiveStop) out.loopConsecutiveStop = current.loopConsecutiveStop;
  return out;
}
