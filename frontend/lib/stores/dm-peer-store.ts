/**
 * Active DM peer store.
 *
 * Holds the OTHER participant of the DM thread the user is currently viewing, so the shared
 * {@code AppHeader} can render their avatar + name in place of the model selector (reusing the
 * agent-avatar header slot) - instead of the DM view carrying its own local header.
 *
 * Ephemeral by design: {@code DmThreadView} sets the peer on mount (once the profile loads) and
 * clears it on unmount, so the header reverts to normal as soon as the user leaves the thread.
 */

import { create } from 'zustand';

export interface DmPeer {
  /** The other participant's numeric user id (the avatar endpoint is keyed by it). */
  userId: string;
  /** The other participant's chosen display name (never the real first/last name). */
  displayName: string;
}

interface DmPeerState {
  peer: DmPeer | null;
  setPeer: (peer: DmPeer | null) => void;
  clearPeer: () => void;
}

export const useDmPeerStore = create<DmPeerState>((set) => ({
  peer: null,
  setPeer: (peer) => set({ peer }),
  clearPeer: () => set({ peer: null }),
}));
