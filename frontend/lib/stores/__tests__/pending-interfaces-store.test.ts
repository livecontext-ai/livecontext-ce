/**
 * Tests for usePendingInterfacesStore - including the clear() method
 * used during run-switch invalidation.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { usePendingInterfacesStore, type PendingInterface } from '../pending-interfaces-store';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makePending(overrides: Partial<PendingInterface> = {}): PendingInterface {
  return {
    nodeId: 'node-1',
    interfaceId: 'iface-1',
    label: 'My Interface',
    status: 'awaiting',
    actionMapping: {},
    addedAt: Date.now(),
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

// Reset before each test
beforeEach(() => {
  usePendingInterfacesStore.getState().clear();
});

describe('usePendingInterfacesStore', () => {
  // =========================================================================
  // Initial State
  // =========================================================================

  describe('initial state', () => {
    it('should start with empty interfaces map', () => {
      const state = usePendingInterfacesStore.getState();
      expect(state.interfaces.size).toBe(0);
    });

    it('should start with null activeNodeId', () => {
      const state = usePendingInterfacesStore.getState();
      expect(state.activeNodeId).toBeNull();
    });
  });

  // =========================================================================
  // addPending
  // =========================================================================

  describe('addPending', () => {
    it('should add an interface to the map', () => {
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1', interfaceId: 'i1' }));

      const state = usePendingInterfacesStore.getState();
      expect(state.interfaces.size).toBe(1);
      expect(state.interfaces.has('n1')).toBe(true);
    });

    it('should set activeNodeId to the first added interface', () => {
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1' }));

      expect(usePendingInterfacesStore.getState().activeNodeId).toBe('n1');
    });

    it('entry interface should take priority for activeNodeId', () => {
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1', status: 'awaiting' }));
      addPending(makePending({ nodeId: 'n2', status: 'awaiting', isEntryInterface: true }));

      expect(usePendingInterfacesStore.getState().activeNodeId).toBe('n2');
    });

    it('awaiting interface should not override entry interface as active', () => {
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n-entry', status: 'awaiting', isEntryInterface: true }));
      addPending(makePending({ nodeId: 'n-regular', status: 'awaiting' }));

      expect(usePendingInterfacesStore.getState().activeNodeId).toBe('n-entry');
    });

    it('rendered interface should not steal active from existing', () => {
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1', status: 'awaiting' }));
      addPending(makePending({ nodeId: 'n2', status: 'rendered' }));

      expect(usePendingInterfacesStore.getState().activeNodeId).toBe('n1');
    });

    it('should replace existing interface with same nodeId', () => {
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1', label: 'V1' }));
      addPending(makePending({ nodeId: 'n1', label: 'V2' }));

      const state = usePendingInterfacesStore.getState();
      expect(state.interfaces.size).toBe(1);
      expect(state.interfaces.get('n1')?.label).toBe('V2');
    });

    it('should handle multiple interfaces', () => {
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1' }));
      addPending(makePending({ nodeId: 'n2' }));
      addPending(makePending({ nodeId: 'n3' }));

      expect(usePendingInterfacesStore.getState().interfaces.size).toBe(3);
    });
  });

  // =========================================================================
  // removePending
  // =========================================================================

  describe('removePending', () => {
    it('should remove an interface from the map', () => {
      const { addPending, removePending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1' }));
      removePending('n1');

      expect(usePendingInterfacesStore.getState().interfaces.size).toBe(0);
    });

    it('should pick next awaiting as active when active is removed', () => {
      const { addPending, removePending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1', status: 'awaiting', addedAt: 100 }));
      addPending(makePending({ nodeId: 'n2', status: 'awaiting', addedAt: 200 }));

      removePending('n1');

      expect(usePendingInterfacesStore.getState().activeNodeId).toBe('n2');
    });

    it('should prefer entry interface as next active', () => {
      const { addPending, removePending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1', status: 'awaiting', isEntryInterface: true, addedAt: 100 }));
      addPending(makePending({ nodeId: 'n2', status: 'awaiting', addedAt: 50 }));
      addPending(makePending({ nodeId: 'n3', status: 'awaiting', isEntryInterface: true, addedAt: 200 }));

      // Force active to n1
      usePendingInterfacesStore.getState().setActive('n1');
      removePending('n1');

      // n3 is next entry interface (entry interfaces sort before non-entry)
      expect(usePendingInterfacesStore.getState().activeNodeId).toBe('n3');
    });

    it('should set activeNodeId to null when last interface is removed', () => {
      const { addPending, removePending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1' }));
      removePending('n1');

      expect(usePendingInterfacesStore.getState().activeNodeId).toBeNull();
    });

    it('should be safe to remove non-existent nodeId', () => {
      expect(() => {
        usePendingInterfacesStore.getState().removePending('does-not-exist');
      }).not.toThrow();
    });
  });

  // =========================================================================
  // setActive
  // =========================================================================

  describe('setActive', () => {
    it('should set activeNodeId', () => {
      usePendingInterfacesStore.getState().setActive('n-custom');
      expect(usePendingInterfacesStore.getState().activeNodeId).toBe('n-custom');
    });

    it('should allow setting to null', () => {
      usePendingInterfacesStore.getState().setActive('n1');
      usePendingInterfacesStore.getState().setActive(null);
      expect(usePendingInterfacesStore.getState().activeNodeId).toBeNull();
    });
  });

  // =========================================================================
  // getAwaiting
  // =========================================================================

  describe('getAwaiting', () => {
    it('should return empty array when no interfaces', () => {
      expect(usePendingInterfacesStore.getState().getAwaiting()).toEqual([]);
    });

    it('should return only awaiting interfaces', () => {
      const { addPending, getAwaiting } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1', status: 'rendered' }));
      addPending(makePending({ nodeId: 'n2', status: 'awaiting' }));
      addPending(makePending({ nodeId: 'n3', status: 'rendered' }));

      const awaiting = usePendingInterfacesStore.getState().getAwaiting();
      expect(awaiting).toHaveLength(1);
      expect(awaiting[0].nodeId).toBe('n2');
    });

    it('should sort entry interfaces first', () => {
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1', status: 'awaiting', addedAt: 100 }));
      addPending(makePending({ nodeId: 'n2', status: 'awaiting', addedAt: 200, isEntryInterface: true }));
      addPending(makePending({ nodeId: 'n3', status: 'awaiting', addedAt: 50 }));

      const awaiting = usePendingInterfacesStore.getState().getAwaiting();
      expect(awaiting[0].nodeId).toBe('n2'); // entry first
      expect(awaiting[1].nodeId).toBe('n3'); // then by addedAt
      expect(awaiting[2].nodeId).toBe('n1');
    });
  });

  // =========================================================================
  // clear() - CRITICAL for run-switch invalidation
  // =========================================================================

  describe('clear (run-switch invalidation)', () => {
    it('should reset interfaces map to empty', () => {
      const { addPending, clear } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1' }));
      addPending(makePending({ nodeId: 'n2' }));
      addPending(makePending({ nodeId: 'n3' }));

      usePendingInterfacesStore.getState().clear();

      expect(usePendingInterfacesStore.getState().interfaces.size).toBe(0);
    });

    it('should reset activeNodeId to null', () => {
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1' }));
      expect(usePendingInterfacesStore.getState().activeNodeId).toBe('n1');

      usePendingInterfacesStore.getState().clear();
      expect(usePendingInterfacesStore.getState().activeNodeId).toBeNull();
    });

    it('should return empty getAwaiting after clear', () => {
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1', status: 'awaiting' }));
      addPending(makePending({ nodeId: 'n2', status: 'awaiting' }));

      usePendingInterfacesStore.getState().clear();
      expect(usePendingInterfacesStore.getState().getAwaiting()).toEqual([]);
    });

    it('should allow adding new interfaces after clear', () => {
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'old-n1' }));
      addPending(makePending({ nodeId: 'old-n2' }));

      usePendingInterfacesStore.getState().clear();

      usePendingInterfacesStore.getState().addPending(
        makePending({ nodeId: 'new-n1', label: 'New Interface' })
      );

      const state = usePendingInterfacesStore.getState();
      expect(state.interfaces.size).toBe(1);
      expect(state.interfaces.has('new-n1')).toBe(true);
      expect(state.interfaces.has('old-n1')).toBe(false);
      expect(state.activeNodeId).toBe('new-n1');
    });

    it('should be safe to clear when already empty', () => {
      expect(() => {
        usePendingInterfacesStore.getState().clear();
      }).not.toThrow();

      const state = usePendingInterfacesStore.getState();
      expect(state.interfaces.size).toBe(0);
      expect(state.activeNodeId).toBeNull();
    });

    it('should be safe to clear multiple times in a row', () => {
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1' }));

      usePendingInterfacesStore.getState().clear();
      usePendingInterfacesStore.getState().clear();
      usePendingInterfacesStore.getState().clear();

      const state = usePendingInterfacesStore.getState();
      expect(state.interfaces.size).toBe(0);
      expect(state.activeNodeId).toBeNull();
    });

    it('clear should create new Map instance (not mutate old reference)', () => {
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'n1' }));
      const oldMap = usePendingInterfacesStore.getState().interfaces;

      usePendingInterfacesStore.getState().clear();
      const newMap = usePendingInterfacesStore.getState().interfaces;

      expect(oldMap).not.toBe(newMap);
      expect(oldMap.size).toBe(1); // old reference unchanged
      expect(newMap.size).toBe(0);
    });

    it('simulated run switch: old run interfaces cleared before new run adds', () => {
      // Run A adds interfaces
      const { addPending } = usePendingInterfacesStore.getState();
      addPending(makePending({ nodeId: 'runA-iface1', status: 'awaiting', addedAt: 100 }));
      addPending(makePending({ nodeId: 'runA-iface2', status: 'awaiting', addedAt: 200 }));

      expect(usePendingInterfacesStore.getState().interfaces.size).toBe(2);

      // User switches to Run B → clear
      usePendingInterfacesStore.getState().clear();

      // Run B adds its own interfaces
      usePendingInterfacesStore.getState().addPending(
        makePending({ nodeId: 'runB-iface1', status: 'awaiting', addedAt: 300 })
      );

      const state = usePendingInterfacesStore.getState();
      expect(state.interfaces.size).toBe(1);
      expect(state.interfaces.has('runA-iface1')).toBe(false);
      expect(state.interfaces.has('runA-iface2')).toBe(false);
      expect(state.interfaces.has('runB-iface1')).toBe(true);
      expect(state.activeNodeId).toBe('runB-iface1');
    });
  });

  // =========================================================================
  // State Immutability
  // =========================================================================

  describe('state immutability', () => {
    it('addPending should create a new Map instance', () => {
      const mapBefore = usePendingInterfacesStore.getState().interfaces;
      usePendingInterfacesStore.getState().addPending(makePending({ nodeId: 'n1' }));
      const mapAfter = usePendingInterfacesStore.getState().interfaces;

      expect(mapBefore).not.toBe(mapAfter);
    });

    it('removePending should create a new Map instance', () => {
      usePendingInterfacesStore.getState().addPending(makePending({ nodeId: 'n1' }));
      const mapBefore = usePendingInterfacesStore.getState().interfaces;
      usePendingInterfacesStore.getState().removePending('n1');
      const mapAfter = usePendingInterfacesStore.getState().interfaces;

      expect(mapBefore).not.toBe(mapAfter);
    });
  });
});
