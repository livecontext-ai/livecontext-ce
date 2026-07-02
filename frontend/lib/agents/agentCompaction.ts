/**
 * Per-agent compaction overrides (V350 AgentEntity columns: compactionEnabled +
 * compactionAfterTurns, plus the summariser-model pair compactionModelProvider +
 * compactionModelName) shared by the agent create/edit modal. POST and PUT /agents
 * both accept these top-level fields; the backend setter uses `containsKey` semantics
 * - a present key is set, an ABSENT key keeps the column untouched (NULL → inherit the
 * conversation override, then the platform default). So the modal must send a field
 * ONLY when the user actually changed it from its hydrated value, otherwise an
 * untouched agent would be pinned to the UI defaults instead of inheriting.
 *
 * The summariser-model pair is both-or-neither: the backend rejects a partial pair
 * with 400 invalid_compaction_model, and both '' clears the columns (back to
 * inherit). Resolution ladder: conversation setting > agent compaction setting >
 * platform default (a cost-efficient platform model) - the primary chat model is
 * never a tier, so cleared agent columns mean the platform default unless the
 * conversation overrides. '' in the UI state means "no override".
 *
 * Mirrors {@link ./agentTurnLimits} for the compaction enable + cadence pair.
 */

export interface CompactionSettings {
  compactionEnabled: boolean;
  compactionAfterTurns: number;
  /** Summariser-model override; '' = inherit. Always paired with compactionModelName. */
  compactionModelProvider: string;
  /** Summariser-model override; '' = inherit. Always paired with compactionModelProvider. */
  compactionModelName: string;
}

/** UI defaults - a fresh agent shows compaction OFF with a 5-turn cadence (the YAML default) and no summariser-model override. */
export const COMPACTION_DEFAULTS: CompactionSettings = {
  compactionEnabled: false,
  compactionAfterTurns: 5,
  compactionModelProvider: '',
  compactionModelName: '',
};

type AgentCompactionColumns = {
  compactionEnabled?: boolean | null;
  compactionAfterTurns?: number | null;
  compactionModelProvider?: string | null;
  compactionModelName?: string | null;
};

/**
 * Resolve the initial (hydrate-on-edit) compaction values from an agent's stored
 * columns. A null/absent enable column shows OFF; a null/absent cadence falls back to
 * the UI default (so the field has a sensible starting value without pinning it).
 * The summariser-model pair hydrates only when BOTH columns are non-blank (a partial
 * pair is treated as unset, matching the backend resolver).
 */
export function initialCompaction(agent: AgentCompactionColumns | null | undefined): CompactionSettings {
  const provider = typeof agent?.compactionModelProvider === 'string' ? agent.compactionModelProvider.trim() : '';
  const name = typeof agent?.compactionModelName === 'string' ? agent.compactionModelName.trim() : '';
  const hasModelPair = provider !== '' && name !== '';
  return {
    compactionEnabled: agent?.compactionEnabled === true,
    compactionAfterTurns:
      typeof agent?.compactionAfterTurns === 'number'
        ? agent.compactionAfterTurns
        : COMPACTION_DEFAULTS.compactionAfterTurns,
    compactionModelProvider: hasModelPair ? provider : '',
    compactionModelName: hasModelPair ? name : '',
  };
}

/**
 * The subset of compaction fields that changed from their initial value - exactly the
 * keys to merge into the agent create/update payload. An untouched field is omitted so
 * the backend leaves its column as-is (create: NULL → inherit; edit: prior value).
 *
 * The summariser-model pair is emitted as a WHOLE when either half changed: both
 * non-blank = the new override, both '' = explicit clear (the backend treats a blank
 * pair as "reset to inherit"). A half-set pair (never produced by the UI, which writes
 * both halves together) is normalised to the explicit clear so the payload can never
 * trip the backend's 400 invalid_compaction_model partial-pair guard.
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
  const modelPairChanged =
    current.compactionModelProvider !== initial.compactionModelProvider ||
    current.compactionModelName !== initial.compactionModelName;
  if (modelPairChanged) {
    const hasBoth = current.compactionModelProvider !== '' && current.compactionModelName !== '';
    out.compactionModelProvider = hasBoth ? current.compactionModelProvider : '';
    out.compactionModelName = hasBoth ? current.compactionModelName : '';
  }
  return out;
}
