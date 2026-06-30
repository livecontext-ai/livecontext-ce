// Pure helper that turns the caller's full publication list (from
// `publicationService.getAllMyPublications()`) into the published / pending-review / rejected
// lookups the resource listing pages (/app/agent, /app/interface, /app/tables) use to render each
// card's Globe / Lock / clock / X marker and drive the visibility filter - WITHOUT a per-item
// status request. Kept React-free so the bucketing is unit-testable in isolation.

/** Minimal shape read off each publication - a subset of WorkflowPublication. */
export interface MyPublicationLite {
  publicationType?: string | null;
  status?: string | null;
  rejectionReason?: string | null;
  /** Key for AGENT publications. */
  agentConfigId?: string | null;
  /** Key for standalone TABLE / INTERFACE / SKILL publications. */
  resourceId?: string | null;
}

export interface PublicationStatusSets {
  /** Resource ids with an ACTIVE (live, shared) publication → Globe. */
  publishedIds: Set<string>;
  /** Resource ids with a PENDING_REVIEW publication → in-review clock. */
  pendingIds: Set<string>;
  /** Resource id → rejection reason for REJECTED publications → red X. */
  rejectedReasons: Map<string, string | null>;
}

/**
 * Bucket the caller's publications of one resource type by moderation status. The key is the
 * resource's own id - `agentConfigId` for AGENT, `resourceId` for TABLE/INTERFACE/SKILL - so the
 * sets line up with the ids the listing pages hold. INACTIVE (unpublished) and any publication of
 * another type are ignored, so an item absent from all three sets is simply private.
 */
export function buildPublicationStatusSets(
  pubs: MyPublicationLite[],
  type: 'AGENT' | 'INTERFACE' | 'TABLE' | 'SKILL',
): PublicationStatusSets {
  const publishedIds = new Set<string>();
  const pendingIds = new Set<string>();
  const rejectedReasons = new Map<string, string | null>();
  for (const pub of pubs) {
    if (pub.publicationType !== type) continue;
    const key = type === 'AGENT' ? pub.agentConfigId : pub.resourceId;
    if (!key) continue;
    if (pub.status === 'ACTIVE') publishedIds.add(key);
    else if (pub.status === 'PENDING_REVIEW') pendingIds.add(key);
    else if (pub.status === 'REJECTED') rejectedReasons.set(key, pub.rejectionReason ?? null);
  }
  return { publishedIds, pendingIds, rejectedReasons };
}
