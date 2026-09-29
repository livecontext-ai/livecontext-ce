/**
 * The look of every button that moves between the views of a run in the side panel ("back to
 * Run", "History", "Logs", "Analysis", "All epochs"): bordered, icon + the name of where it goes,
 * with the arrow on the side it leads to. One constant, so they cannot drift apart; the name gives
 * up the room first on a narrow panel (`min-w-0` + a `truncate` label), never the run identity
 * beside it.
 */
export const PANEL_NAV_BUTTON_CLASS =
  'flex h-6 min-w-0 flex-shrink items-center gap-1.5 rounded-lg border border-theme px-1.5 text-sm font-medium text-theme-secondary transition-colors hover:bg-theme-secondary hover:text-theme-primary';
