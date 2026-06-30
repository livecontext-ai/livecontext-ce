/**
 * Per-agent compaction overrides (V350 AgentEntity columns: compactionEnabled +
 * compactionAfterTurns) shared by the agent create/edit modal. POST and PUT /agents
 * both accept these top-level fields; the backend setter uses `containsKey` semantics
 * - a present key is set, an ABSENT key keeps the column untouched (NULL → inherit the
 * conversation override, then the platform default). So the modal must send a field
 * ONLY when the user actually changed it from its hydrated value, otherwise an
 * untouched agent would be pinned to the UI defaults instead of inheriting.
 *
 * Mirrors {@link ./agentTurnLimits} for the compaction enable + cadence pair.
 */

export interface CompactionSettings {
  compactionEnabled: boolean;
  compactionAfterTurns: number;
}

/** UI defaults - a fresh agent shows compaction OFF with a 5-turn cadence (the YAML default). */
export const COMPACTION_DEFAULTS: CompactionSettings = {
  compactionEnabled: false,
  compactionAfterTurns: 5,
};

type AgentCompactionColumns = {
  compactionEnabled?: boolean | null;
  compactionAfterTurns?: number | null;
};

/**
 * Resolve the initial (hydrate-on-edit) compaction values from an agent's stored
 * columns. A null/absent enable column shows OFF; a null/absent cadence falls back to
 * the UI default (so the field has a sensible starting value without pinning it).
 */
export function initialCompaction(agent: AgentCompactionColumns | null | undefined): CompactionSettings {
  return {
    compactionEnabled: agent?.compactionEnabled === true,
    compactionAfterTurns:
      typeof agent?.compactionAfterTurns === 'number'
        ? agent.compactionAfterTurns
        : COMPACTION_DEFAULTS.compactionAfterTurns,
  };
}

/**
 * The subset of compaction fields that changed from their initial value - exactly the
 * keys to merge into the agent create/update payload. An untouched field is omitted so
 * the backend leaves its column as-is (create: NULL → inherit; edit: prior value).
 */
export function buildChangedCompaction(
  current: CompactionSettings,
  initial: CompactionSettings,
): Partial<CompactionSettings> {
  const out: Partial<CompactionSettings> = {};
  if (current.compactionEnabled !== initial.compactionEnabled) {
    out.compactionEnabled = current.compactionEnabled;
  }
  if (current.compactionAfterTurns !== initial.compactionAfterTurns) {
    out.compactionAfterTurns = current.compactionAfterTurns;
  }
  return out;
}
