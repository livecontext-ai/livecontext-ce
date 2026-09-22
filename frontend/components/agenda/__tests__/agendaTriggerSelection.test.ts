import { describe, expect, it } from 'vitest';
import type { AgendaMarker, AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';
import {
  agendaTriggerKey,
  occurrenceMatchesTriggerTypes,
  occurrenceUsesTrigger,
} from '../agendaTriggerSelection';
import { AGENDA_KIND_ORDER } from '../agendaLaunchKinds';

const baseOccurrence: AgendaOccurrence = {
  id: 'occurrence-1',
  kind: 'PAST',
  startAt: '2026-09-14T09:00:00Z',
  resourceType: 'WORKFLOW',
  resourceId: 'workflow-1',
  name: 'Daily report',
  armed: true,
  isNextFire: false,
  overridden: false,
  moveAllSupported: false,
  status: 'COMPLETED',
};

function marker(overrides: Partial<AgendaMarker> = {}): AgendaMarker {
  return {
    resourceType: 'WORKFLOW',
    resourceId: 'workflow-1',
    name: 'Daily report',
    triggerType: 'MANUAL',
    armed: true,
    ...overrides,
  };
}

describe('agenda trigger selection', () => {
  it('gives two schedules on the same workflow distinct identities', () => {
    expect(agendaTriggerKey(marker({ triggerType: 'SCHEDULE', scheduleId: 'schedule-1' })))
      .not.toBe(agendaTriggerKey(marker({ triggerType: 'SCHEDULE', scheduleId: 'schedule-2' })));
  });

  it('gives two same-kind triggers on the same workflow distinct identities', () => {
    expect(agendaTriggerKey(marker({ triggerId: 'trigger:first_manual' })))
      .not.toBe(agendaTriggerKey(marker({ triggerId: 'trigger:second_manual' })));
  });

  it('matches a schedule only to its own dated occurrences', () => {
    const selected = marker({ triggerType: 'SCHEDULE', scheduleId: 'schedule-1' });

    expect(occurrenceUsesTrigger({ ...baseOccurrence, triggerType: 'SCHEDULE', scheduleId: 'schedule-1' }, selected))
      .toBe(true);
    expect(occurrenceUsesTrigger({ ...baseOccurrence, triggerType: 'SCHEDULE', scheduleId: 'schedule-2' }, selected))
      .toBe(false);
  });

  it('finds past uses of a manual production trigger', () => {
    const selected = marker({ triggerType: 'MANUAL', triggerId: 'trigger:first_manual' });

    expect(occurrenceUsesTrigger({
      ...baseOccurrence,
      triggerType: 'MANUAL',
      triggerId: 'trigger:first_manual',
    }, selected)).toBe(true);
    expect(occurrenceUsesTrigger({
      ...baseOccurrence,
      triggerType: 'MANUAL',
      triggerId: 'trigger:second_manual',
    }, selected)).toBe(false);
  });

  it('matches a past schedule use by its plan trigger id when the schedule row is absent', () => {
    const selected = marker({
      triggerType: 'SCHEDULE',
      scheduleId: 'schedule-1',
      triggerId: 'trigger:daily',
    });

    expect(occurrenceUsesTrigger({
      ...baseOccurrence,
      triggerType: 'SCHEDULE',
      triggerId: 'trigger:daily',
    }, selected)).toBe(true);
  });

  it('never borrows a same-kind trigger from another resource', () => {
    const selected = marker({ triggerType: 'MANUAL' });

    expect(occurrenceUsesTrigger({ ...baseOccurrence, resourceId: 'workflow-2', triggerType: 'MANUAL' }, selected))
      .toBe(false);
  });

  it('keeps unattributed legacy history only in the default all-kinds view', () => {
    // An unattributed entry is a legacy workflow epoch, so what decides is whether any
    // WORKFLOW kind was deselected. Every one of them still selected means nothing the
    // user did is an opinion about this entry.
    expect(occurrenceMatchesTriggerTypes(baseOccurrence, AGENDA_KIND_ORDER)).toBe(true);
    expect(occurrenceMatchesTriggerTypes(
      baseOccurrence,
      ['SCHEDULE', 'WEBHOOK', 'MANUAL', 'CHAT', 'FORM', 'DATASOURCE', 'WORKFLOW', 'ERROR'],
    )).toBe(true);

    expect(occurrenceMatchesTriggerTypes(baseOccurrence, [])).toBe(false);
    expect(occurrenceMatchesTriggerTypes(baseOccurrence, ['MANUAL'])).toBe(false);
  });

  describe('agent runs', () => {
    const agentRun: AgendaOccurrence = {
      ...baseOccurrence,
      id: 'agent-run:exec-1',
      resourceType: 'AGENT',
      resourceId: 'agent-1',
      name: 'Support agent',
      launchSource: 'SUB_AGENT',
      triggerType: undefined,
    };

    describe('when a trigger belonging to the agent itself is selected', () => {
      const agentSchedule = marker({
        resourceType: 'AGENT', resourceId: 'agent-1', triggerType: 'SCHEDULE', scheduleId: 's1',
      });
      const scheduledRun = { ...agentRun, launchSource: 'SCHEDULE' } as AgendaOccurrence;

      it('finds its runs when that kind is unambiguous on the agent', () => {
        // The run records the launch KIND, never which schedule row fired it. With one
        // schedule on the agent the two questions have the same answer, and refusing to
        // answer emptied the calendar and reported "no results" for an agent that ran.
        expect(occurrenceUsesTrigger(scheduledRun, agentSchedule, [agentSchedule])).toBe(true);
      });

      it('refuses to guess when the agent has TWO triggers of that kind', () => {
        const second = marker({
          resourceType: 'AGENT', resourceId: 'agent-1', triggerType: 'SCHEDULE', scheduleId: 's2',
        });

        // Attributing the run to either one would be a claim the data cannot support.
        expect(occurrenceUsesTrigger(scheduledRun, agentSchedule, [agentSchedule, second]))
          .toBe(false);
      });

      it('never matches a different launch kind, or another agent', () => {
        expect(occurrenceUsesTrigger(agentRun, agentSchedule, [agentSchedule])).toBe(false);
        expect(occurrenceUsesTrigger(
          { ...scheduledRun, resourceId: 'agent-2' } as AgendaOccurrence,
          agentSchedule,
          [agentSchedule],
        )).toBe(false);
      });

      it('falls back to matching nothing when the catalogue is not supplied', () => {
        // A caller that has not been updated keeps the previous behaviour rather than
        // silently attributing runs on an unknown catalogue.
        expect(occurrenceUsesTrigger(scheduledRun, agentSchedule)).toBe(false);
      });
    });

    it('filters an agent run by its LAUNCH kind, which no workflow trigger can express', () => {
      expect(occurrenceMatchesTriggerTypes(agentRun, AGENDA_KIND_ORDER)).toBe(true);
      expect(occurrenceMatchesTriggerTypes(agentRun, ['SUB_AGENT'])).toBe(true);
      expect(occurrenceMatchesTriggerTypes(agentRun, ['CHAT'])).toBe(false);
    });

    it('lets one chip cover both vocabularies where they mean the same thing', () => {
      // A chat-triggered workflow and a conversation turn with an agent are both "chat".
      // Two controls saying that would be a worse answer to the same question.
      const chatWorkflow = { ...baseOccurrence, triggerType: 'CHAT' } as AgendaOccurrence;
      const chatAgentRun = { ...agentRun, launchSource: 'CHAT' } as AgendaOccurrence;

      expect(occurrenceMatchesTriggerTypes(chatWorkflow, ['CHAT'])).toBe(true);
      expect(occurrenceMatchesTriggerTypes(chatAgentRun, ['CHAT'])).toBe(true);
    });

    it('does not sweep away unattributed WORKFLOW history when an agent kind is deselected', () => {
      // An unattributed entry is a legacy epoch whose plan snapshot names no trigger.
      // Deselecting Sub-agent says nothing about it, so measuring "is anything filtered"
      // against the full 11-kind vocabulary made an agent chip silently remove workflow
      // history it has no opinion about.
      const withoutAnAgentKind = AGENDA_KIND_ORDER.filter((kind) => kind !== 'SUB_AGENT');
      expect(occurrenceMatchesTriggerTypes(baseOccurrence, withoutAnAgentKind)).toBe(true);

      // Deselecting a WORKFLOW kind still hides it: that choice IS about this entry.
      const withoutAWorkflowKind = AGENDA_KIND_ORDER.filter((kind) => kind !== 'MANUAL');
      expect(occurrenceMatchesTriggerTypes(baseOccurrence, withoutAWorkflowKind)).toBe(false);
    });

    it('treats a run whose launch the backend could not name as unattributed', () => {
      const unattributed = { ...agentRun, launchSource: undefined } as AgendaOccurrence;

      expect(occurrenceMatchesTriggerTypes(unattributed, AGENDA_KIND_ORDER)).toBe(true);
      expect(occurrenceMatchesTriggerTypes(unattributed, ['SUB_AGENT'])).toBe(false);
    });
  });
});
