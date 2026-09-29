'use client';

import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { useSharedConversation } from '@/contexts/SharedConversationContext';

/**
 * Whether the current viewer may DRIVE a run (fire a trigger, run an application action,
 * continue an interface, execute a step, resolve an approval).
 *
 * Inside a public share page (`/s/[token]`) the answer is always yes: the visitor acts
 * under the share link, not under their own workspace role, and the backend accepts
 * the share link's run actions. Without this, a logged-in visitor whose persisted
 * active workspace is a VIEWER role saw a working public app refuse every click.
 *
 * Everywhere else it is the workspace rule: a read-only VIEWER cannot drive runs.
 */
export function useCanDriveRuns(): boolean {
  const isShared = !!useSharedConversation();
  const canMutate = useCanMutateInCurrentOrg();
  return isShared || canMutate;
}
