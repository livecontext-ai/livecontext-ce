/**
 * An agent name is unique among the ACTIVE agents of a workspace. When a create, a rename or a
 * resume (re-activation) asks for a name another active agent holds, agent-service answers 409
 * with `{ error: 'AGENT_NAME_CONFLICT', name, suggestedName, existingAgentId }`, where
 * `suggestedName` is the first free name ("Nova (2)"). apiClient turns that into an ApiError
 * whose `code` is the `error` field and whose `details` is the body.
 *
 * Read by shape, not by `instanceof ApiError`, so it also works where the API client module is
 * mocked. `name` and `suggestedName` are null when the server did not send them.
 */
export interface AgentNameConflict {
  name?: string | null;
  suggestedName: string | null;
}

export function readAgentNameConflict(err: unknown): AgentNameConflict | null {
  if (!err || typeof err !== 'object') return null;
  const e = err as { code?: unknown; details?: { error?: unknown; name?: unknown; suggestedName?: unknown } };
  const isConflict = e.code === 'AGENT_NAME_CONFLICT' || e.details?.error === 'AGENT_NAME_CONFLICT';
  if (!isConflict) return null;
  const suggested = e.details?.suggestedName;
  const name = e.details?.name;
  return {
    name: typeof name === 'string' && name.trim() ? name : null,
    suggestedName: typeof suggested === 'string' && suggested.trim() ? suggested : null,
  };
}
