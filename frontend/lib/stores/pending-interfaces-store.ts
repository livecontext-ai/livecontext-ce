import { create } from 'zustand';

/**
 * Represents an interface node that is pending (rendered or awaiting user action).
 */
export interface PendingInterface {
  nodeId: string;
  interfaceId: string;
  label: string;
  status: 'rendered' | 'awaiting';
  actionMapping: Record<string, string>;
  addedAt: number;
  /** Whether this is the entry interface (shows first when multiple are pending) */
  isEntryInterface?: boolean;
}

interface PendingInterfacesState {
  interfaces: Map<string, PendingInterface>;
  activeNodeId: string | null;
  addPending: (iface: PendingInterface) => void;
  removePending: (nodeId: string) => void;
  setActive: (nodeId: string | null) => void;
  getAwaiting: () => PendingInterface[];
  clear: () => void;
}

export const usePendingInterfacesStore = create<PendingInterfacesState>((set, get) => ({
  interfaces: new Map(),
  activeNodeId: null,

  addPending: (iface) =>
    set((state) => {
      const newMap = new Map(state.interfaces);
      newMap.set(iface.nodeId, iface);

      let activeNodeId = state.activeNodeId;

      if (activeNodeId === null) {
        // First interface - always activate
        activeNodeId = iface.nodeId;
      } else if (iface.isEntryInterface && iface.status === 'awaiting') {
        // Entry interface takes priority
        activeNodeId = iface.nodeId;
      } else if (iface.status === 'awaiting') {
        // New awaiting interface - activate if current active is not an entry interface
        const currentActive = state.interfaces.get(activeNodeId);
        if (!currentActive?.isEntryInterface) {
          activeNodeId = iface.nodeId;
        }
      }

      return { interfaces: newMap, activeNodeId };
    }),

  removePending: (nodeId) =>
    set((state) => {
      const newMap = new Map(state.interfaces);
      newMap.delete(nodeId);

      let activeNodeId = state.activeNodeId;
      if (activeNodeId === nodeId) {
        // Pick next: prefer entry interface, then earliest awaiting, then any
        const awaiting = Array.from(newMap.values())
          .filter((i) => i.status === 'awaiting')
          .sort((a, b) => {
            if (a.isEntryInterface && !b.isEntryInterface) return -1;
            if (!a.isEntryInterface && b.isEntryInterface) return 1;
            return a.addedAt - b.addedAt;
          });
        activeNodeId = awaiting[0]?.nodeId ?? (newMap.size > 0 ? newMap.keys().next().value ?? null : null);
      }

      return { interfaces: newMap, activeNodeId };
    }),

  setActive: (nodeId) => set({ activeNodeId: nodeId }),

  getAwaiting: () => {
    const state = get();
    return Array.from(state.interfaces.values())
      .filter((i) => i.status === 'awaiting')
      .sort((a, b) => {
        // Entry interfaces first, then by addedAt
        if (a.isEntryInterface && !b.isEntryInterface) return -1;
        if (!a.isEntryInterface && b.isEntryInterface) return 1;
        return a.addedAt - b.addedAt;
      });
  },

  clear: () => set({ interfaces: new Map(), activeNodeId: null }),
}));

// Phase 6 (2026-05-18) - HMR-safe workspace-switch reset. Without this, a
// pending interface awaiting in OrgA workspace stays flagged as `awaiting`
// after the user switches to OrgB, causing the OrgB sidepanel to surface
// an orphan pending entry.
if (typeof window !== 'undefined') {
  const HMR_KEY = Symbol.for('__lc_orgReset:usePendingInterfacesStore');
  const g = globalThis as unknown as Record<symbol, (() => void) | undefined>;
  if (typeof g[HMR_KEY] === 'function') g[HMR_KEY]!();
  import('@/lib/stores/current-org-store').then(({ useCurrentOrgStore }) => {
    g[HMR_KEY] = useCurrentOrgStore.subscribe(
      (s) => s.currentOrgId,
      () => usePendingInterfacesStore.getState().clear(),
    );
  }).catch(() => {});
}
