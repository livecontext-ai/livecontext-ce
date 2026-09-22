import { ALL_RESOURCE_TYPES } from '@/hooks/useAgendaPreferences';
import type { ResourceType } from '@/lib/api/orchestrator/agenda.service';
import { AGENDA_KIND_ORDER, type AgendaKind } from './agendaLaunchKinds';

/**
 * Which "nothing to show" message is TRUE for the current view.
 *
 * <p>Pulled out of the page as a pure function because getting it wrong is invisible: the
 * page renders a confident sentence either way, and only a reader who knows the workspace
 * can tell that "Nothing scheduled here" is a lie. The rule is worth stating once, in one
 * place, where it can be read and tested.
 *
 * <p>The rule: <b>"Nothing is scheduled here" is a claim about the WORKSPACE</b>, so it may
 * only be made when nothing the user did is narrowing the view. Every control on this page
 * narrows it - not just the search box and the resource chips, but the three include
 * toggles as well. Any of them being active means the honest message is "your filters hide
 * everything", not "you have nothing".
 */
export type AgendaEmptyKind =
  | 'none'
  | 'workspace'
  | 'no-kind'
  | 'no-launch-kind'
  | 'search'
  | 'filters'
  | 'failed';

export interface AgendaEmptyInput {
  loading: boolean;
  /**
   * True when the last load threw.
   *
   * Without it an empty agenda and a FAILED one are the same input, and the page told a
   * workspace full of schedules that it had nothing scheduled - the error toast having
   * auto-dismissed by then. The module's whole job is to only make claims that are true,
   * and it could not tell these apart because it was never given the fact.
   */
  failed?: boolean;
  resourceTypes: ResourceType[];
  search: string;
  showPast: boolean;
  showPaused: boolean;
  /**
   * The launch kinds still selected.
   *
   * <p>The module's own rule is that EVERY control narrows the view, and this one was
   * missing: deselecting kinds until nothing is left produced "Nothing is scheduled
   * here", a claim about the workspace made because of something the user did. It became
   * easy to walk into once agent runs joined the calendar, since turning Chat off is the
   * natural reaction to a busy month. Optional so a caller that has not been updated
   * keeps its previous verdicts rather than silently reading "all kinds off".
   */
  triggerTypes?: readonly AgendaKind[];
  occurrenceCount: number;
}

/**
 * @returns `none` while there is something to draw (or while loading), otherwise the
 *          specific empty state to show.
 */
export function selectAgendaEmptyState(input: AgendaEmptyInput): AgendaEmptyKind {
  // Occurrences only. The page used to also draw a rail of dated-less markers and counted
  // them here; once that rail went, counting them would have made an agenda holding nothing
  // but markers show neither content nor an empty state - a blank page claiming nothing.
  const nothingVisible = input.occurrenceCount === 0;
  if (input.loading || !nothingVisible) return 'none';
  // Checked before anything else: with no data, every other verdict here would be a
  // statement about a workspace this page has not managed to read.
  if (input.failed) return 'failed';

  const isSearching = input.search.trim().length > 0;
  const kinds = input.triggerTypes ?? AGENDA_KIND_ORDER;
  const narrowing =
    input.resourceTypes.length < ALL_RESOURCE_TYPES.length
    || kinds.length < AGENDA_KIND_ORDER.length
    || isSearching
    || !input.showPast
    || !input.showPaused;

  if (!narrowing) return 'workspace';
  // Most specific first: a user who has turned every kind off, or typed a query, gets told
  // about the thing they did rather than a generic "check your filters".
  if (input.resourceTypes.length === 0) return 'no-kind';
  // Its own state, not 'no-kind'. That message reads "No resource kind selected / Turn a
  // resource kind back on", so a user who emptied the LAUNCH-kind list was sent to a
  // control they had not touched - and the two states being distinct is exactly why the
  // "every state has a distinct message pair" test could not see it.
  if (kinds.length === 0) return 'no-launch-kind';
  if (isSearching) return 'search';
  return 'filters';
}

/** The i18n keys for each empty state, so the page never has to map them inline. */
export const AGENDA_EMPTY_KEYS: Record<
  Exclude<AgendaEmptyKind, 'none'>,
  { title: string; description: string }
> = {
  workspace: { title: 'empty.title', description: 'empty.description' },
  failed: { title: 'errors.loadTitle', description: 'errors.loadMessage' },
  'no-kind': { title: 'empty.allFilteredTitle', description: 'empty.allFilteredDescription' },
  'no-launch-kind': {
    title: 'empty.noLaunchKindTitle',
    description: 'empty.noLaunchKindDescription',
  },
  search: { title: 'empty.searchTitle', description: 'empty.searchDescription' },
  filters: { title: 'empty.filteredTitle', description: 'empty.filteredDescription' },
};
