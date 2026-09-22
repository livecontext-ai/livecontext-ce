import { describe, expect, it } from 'vitest';
import { AGENDA_EMPTY_KEYS, selectAgendaEmptyState, type AgendaEmptyInput } from '../agendaEmptyState';
import { AGENDA_KIND_ORDER } from '../agendaLaunchKinds';

/**
 * Which empty message the page is allowed to show.
 *
 * "Nothing scheduled here / Schedules from your workflows, applications and agents show up
 * on this calendar" is a statement about the WORKSPACE. Shown while a filter is active it
 * is simply false, and the user has no way to tell - the page looks equally confident
 * either way. Every control on this page narrows the view, so every one of them has to
 * suppress that message.
 */
describe('selectAgendaEmptyState', () => {
  const base: AgendaEmptyInput = {
    loading: false,
    resourceTypes: ['WORKFLOW', 'APPLICATION', 'AGENT'],
    search: '',
    showPast: true,
    showPaused: true,
    occurrenceCount: 0,
  };

  it('says nothing while there is something to draw', () => {
    expect(selectAgendaEmptyState({ ...base, occurrenceCount: 1 })).toBe('none');
  });

  it('says nothing while still loading, so the empty state does not flash', () => {
    expect(selectAgendaEmptyState({ ...base, loading: true })).toBe('none');
  });

  it('never claims an empty workspace when the load FAILED', () => {
    // The input the module was not given, and so the one lie it could not avoid telling.
    // A failed load leaves zero occurrences and zero markers with no filter narrowing
    // anything, which is indistinguishable from an empty workspace - so a workspace full of
    // schedules was told it had none, once the error toast had auto-dismissed.
    expect(selectAgendaEmptyState({ ...base, failed: true })).toBe('failed');
  });

  it('reports the failure ahead of every filter verdict', () => {
    // With no data, "your filters hide everything" is also a claim about a workspace this
    // page has not managed to read. Most-specific-wins does not apply: nothing is known.
    expect(selectAgendaEmptyState({ ...base, failed: true, search: 'zzz' })).toBe('failed');
    expect(selectAgendaEmptyState({ ...base, failed: true, resourceTypes: [] })).toBe('failed');
    expect(selectAgendaEmptyState({ ...base, failed: true, showPast: false })).toBe('failed');
  });

  it('says nothing at all when a failed load still has something to draw', () => {
    // A refresh that fails over a view already holding data must not blank it.
    expect(selectAgendaEmptyState({ ...base, failed: true, occurrenceCount: 1 })).toBe('none');
  });

  it('claims an empty workspace ONLY when no filter is narrowing the view', () => {
    expect(selectAgendaEmptyState(base)).toBe('workspace');
  });

  it('does not claim an empty workspace when a resource kind is deselected', () => {
    // The partial case is the one that slipped through: a workspace whose only schedules
    // are agents, with the Agents chip clicked off, was told it had nothing scheduled.
    expect(selectAgendaEmptyState({ ...base, resourceTypes: ['WORKFLOW', 'APPLICATION'] }))
      .toBe('filters');
    expect(selectAgendaEmptyState({ ...base, resourceTypes: ['WORKFLOW'] })).toBe('filters');
  });

  it('does not claim an empty workspace when any include toggle is off', () => {
    expect(selectAgendaEmptyState({ ...base, showPast: false })).toBe('filters');
    expect(selectAgendaEmptyState({ ...base, showPaused: false })).toBe('filters');
  });

  it('names the search when a search is what emptied the view', () => {
    // Telling someone who typed "zzz" to turn a resource kind back on is not an answer.
    expect(selectAgendaEmptyState({ ...base, search: 'zzz' })).toBe('search');
    expect(selectAgendaEmptyState({ ...base, search: '   ' })).toBe('workspace');
  });

  it('names the deselected kinds when every kind is off, ahead of a search', () => {
    // Most specific wins: with no kind selected, nothing can match whatever was typed.
    expect(selectAgendaEmptyState({ ...base, resourceTypes: [] })).toBe('no-kind');
    expect(selectAgendaEmptyState({ ...base, resourceTypes: [], search: 'zzz' })).toBe('no-kind');
  });

  describe('the launch-kind filter narrows too', () => {
    it('never claims the workspace is empty because kinds were deselected', () => {
      // The module's rule is that EVERY control narrows the view, and this one was
      // missing from it. Turning Chat off is the natural reaction to a chatty month, and
      // it used to produce "Nothing is scheduled here" - a claim about the workspace,
      // made because of something the user did.
      expect(selectAgendaEmptyState({ ...base, triggerTypes: ['SCHEDULE'] })).toBe('filters');
    });

    it('names the LAUNCH kinds when none is left, not the resource chips', () => {
      // 'no-kind' reads "No resource kind selected / Turn a resource kind back on",
      // which points a user who emptied the launch-kind list at a control they never
      // touched. The two states being distinct was not enough: the MESSAGE has to be.
      expect(selectAgendaEmptyState({ ...base, triggerTypes: [] })).toBe('no-launch-kind');
      expect(selectAgendaEmptyState({ ...base, resourceTypes: [] })).toBe('no-kind');
    });

    it('sends each of the two to a message that names the control it is about', async () => {
      // Asserting the KEYS differ is what the old test did and it passed while both
      // resolved to the resource-kind sentence. This reads the strings.
      const en = (await import('@/messages/en.json')).default as unknown as {
        agenda: { empty: Record<string, string> };
      };
      const launch = AGENDA_EMPTY_KEYS['no-launch-kind'];
      const resource = AGENDA_EMPTY_KEYS['no-kind'];

      expect(en.agenda.empty[launch.title.split('.')[1]]).toMatch(/launch kind/i);
      expect(en.agenda.empty[resource.title.split('.')[1]]).toMatch(/resource kind/i);
    });

    it('still says "workspace" when every kind is selected', () => {
      expect(selectAgendaEmptyState({ ...base, triggerTypes: AGENDA_KIND_ORDER })).toBe('workspace');
    });

    it('treats an absent list as "all selected", so an un-updated caller keeps its verdict', () => {
      expect(selectAgendaEmptyState(base)).toBe('workspace');
    });
  });

  it('gives every state a distinct message pair', () => {
    // Two states sharing one message is how "No resource kind selected" ended up being
    // shown to someone whose search simply missed.
    const pairs = Object.values(AGENDA_EMPTY_KEYS);
    const titles = pairs.map((p) => p.title);
    expect(new Set(titles).size).toBe(titles.length);
    expect(pairs.every((p) => p.title && p.description)).toBe(true);
  });
});
