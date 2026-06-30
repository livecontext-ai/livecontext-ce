import { create } from 'zustand';

/**
 * One-shot signal that the NEXT freshly-executed application run should auto-open
 * the right side panel.
 *
 * Set (`arm`) when the user authorizes an `application:execute` from the chat
 * authorization card - the user said "exécute ouvrira le right side panel". The
 * first {@link ApplicationVisualizeCard} that mounts with a fresh runId consumes
 * the flag and opens its panel, then clears it so reloads / later cards never
 * auto-open. Deliberately tiny + module-global so the deep-in-the-message-tree
 * card doesn't need props threaded down from ChatCore.
 *
 * `armedAt` doubles as a freshness fence: if the approved execute never produces a
 * card (it failed), the arm self-expires after {@link FRESHNESS_MS} so a much later
 * unrelated application card can't be hijacked into auto-opening.
 */
const FRESHNESS_MS = 5 * 60_000; // generous enough for a slow execute, bounded for a failed one

interface AppRunAutoOpenState {
  armedAt: number | null;
  /** Arm the one-shot (an authorized execute is about to produce a run). */
  arm: () => void;
  /** Consume the one-shot: true exactly once after a recent arm, then clears. */
  consume: () => boolean;
  /** Disarm without consuming (e.g. the turn was declined). */
  clear: () => void;
}

export const useAppRunAutoOpenStore = create<AppRunAutoOpenState>((set, get) => ({
  armedAt: null,
  arm: () => set({ armedAt: Date.now() }),
  consume: () => {
    const { armedAt } = get();
    if (armedAt == null) return false;
    set({ armedAt: null });
    return Date.now() - armedAt < FRESHNESS_MS;
  },
  clear: () => set({ armedAt: null }),
}));
