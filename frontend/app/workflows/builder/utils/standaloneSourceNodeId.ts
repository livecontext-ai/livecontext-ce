/**
 * Single source of truth for the `sourceNodeId` sent to the trigger-service
 * `create` endpoints for standalone resources (webhooks, schedules, chat/form
 * endpoints).
 *
 * The backend uses `(tenant_id, source_node_id)` as an idempotency key (see
 * V136 unique index + `findByTenantIdAndSourceNodeId` dedup in the four
 * Standalone*Service.create methods). For the key to actually dedup, the
 * SAME string must be produced every time the same logical React-Flow node
 * asks the server to create its standalone row - across page refreshes,
 * StrictMode double-mounts, and whatever entry point (palette click, drag
 * drop, or form auto-create fallback) fires the call.
 *
 * What's stable vs not:
 *  - `node.id` - stable. On import, `NodeCreationService.ts` restores it from
 *    `trigger.graphNodeId` which is saved by `triggerProcessor.ts`.
 *  - `node.data.id` - NOT stable. `NodeCreationService.ts:215` regenerates
 *    the `idSuffix = ${Date.now()}-${random}` on every import.
 *  - `Date.now()` / `Math.random()` - NEVER stable. Previous code paths in
 *    `NodeCreatorPanel.tsx` used these, which is why the dedup never fired
 *    and every refresh burned quota.
 *
 * Therefore: always key on `node.id`.
 */

export type StandaloneTriggerKind = 'webhook' | 'schedule' | 'chat' | 'form';

/**
 * Build the canonical `sourceNodeId` for a React-Flow node.
 *
 * @param kind    trigger kind (one per standalone resource table)
 * @param nodeId  React-Flow `node.id` (NOT `node.data.id`) - the value that
 *                round-trips as `trigger.graphNodeId` in the saved plan.
 */
export function buildStandaloneSourceNodeId(kind: StandaloneTriggerKind, nodeId: string): string {
  return `${kind}-${nodeId}`;
}
