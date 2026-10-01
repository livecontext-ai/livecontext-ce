import { agendaFailureOf } from '@/lib/api/orchestrator/agenda.service';
import { readAgentNameConflict } from '@/lib/agents/agentNameConflict';

/**
 * Which sentence a refusal should become.
 *
 * <p>Pulled out of the page because every branch here is a promise about what the user
 * should do next, and getting one wrong is invisible: the page renders a confident,
 * grammatical sentence either way, and only someone who knows what the server actually
 * refused can tell it is the wrong one. Inside a 500-line component with a `t()` closure
 * these branches could not be exercised at all.
 *
 * <p>It takes the thrown error rather than a response body on purpose: `apiClient` throws
 * on every non-2xx, so a refusal only ever arrives as an exception. Reading its `message`
 * would show "HTTP 422: Unprocessable Entity" while the server was explaining precisely
 * why, in a field this reads instead.
 */
export interface AgendaErrorText {
  /** i18n key under `agenda`, or null when `detail` should be shown verbatim. */
  key: string | null;
  /** The server's own explanation, when it sent one worth showing. */
  detail?: string;
  /** Values for the placeholders of `key`. */
  values?: Record<string, string>;
}

/**
 * @returns the key to translate, or a `detail` to show as-is when the server's own words
 *          are more specific than anything this can say.
 */
export function agendaErrorText(error: unknown): AgendaErrorText {
  // Resuming a paused agent re-activates it, and another active agent may have taken its
  // name meanwhile: the server refuses with the name and the first free one. Say that, in
  // the user's language, instead of the server's English sentence.
  const nameConflict = readAgentNameConflict(error);
  if (nameConflict) {
    const name = nameConflict.name ?? '';
    return nameConflict.suggestedName
      ? { key: 'errors.agentNameTaken', values: { name, suggestion: nameConflict.suggestedName } }
      : { key: 'errors.agentNameTakenNoSuggestion', values: { name } };
  }
  const { reason, detail } = agendaFailureOf(error);
  switch (reason) {
    case 'PATTERN_NOT_SHIFTABLE':
      return { key: 'errors.patternNotShiftable' };
    case 'WEEKDAY_SET_NOT_MATCHED':
      return { key: 'errors.weekdaySetNotMatched' };
    case 'DAY_OF_MONTH_UNSAFE':
      return { key: 'errors.dayOfMonthUnsafe' };
    case 'NOT_ARMED':
      return { key: 'errors.notArmed' };
    case 'NOT_THE_NEXT_OCCURRENCE':
      return { key: 'errors.notTheNextOccurrence' };
    case 'VIEWER_ROLE':
      return { key: 'errors.viewerRole' };
    case 'NOT_FOUND':
      return { key: 'errors.notFound' };
    case 'SCHEDULE_REJECTED':
      // trigger-service refused it: an archived row, or an instant it could not parse.
      // Without this case the one refusal it is the authority on fell to the generic
      // message - the exact defect agendaFailureOf exists to prevent.
      return { key: 'errors.scheduleRejected' };
    case 'EXECUTION_REFUSED':
      // The provider's own words ("Provider deepseek is not configured") beat anything
      // this file could write, so they win when present.
      return detail ? { key: null, detail } : { key: 'errors.executionRefused' };
    default:
      return detail ? { key: null, detail } : { key: 'errors.generic' };
  }
}
