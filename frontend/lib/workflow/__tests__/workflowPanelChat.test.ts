import { beforeEach, describe, expect, it } from 'vitest';
import {
  consumeUserModeToggle,
  markUserModeToggle,
  isFromWorkflowPanelChat,
  reactionToAgentMarker,
  rememberWorkflowPanelConversation,
} from '@/lib/workflow/workflowPanelChat';

const WF = 'wf-1';
const PANEL_CONV = 'conv-panel';

describe('workflowPanelChat', () => {
  beforeEach(() => {
    rememberWorkflowPanelConversation(WF, PANEL_CONV);
  });

  describe('isFromWorkflowPanelChat', () => {
    it("recognises the panel's own conversation and nothing else", () => {
      expect(isFromWorkflowPanelChat(WF, { conversationId: PANEL_CONV })).toBe(true);
      expect(isFromWorkflowPanelChat(WF, { conversationId: 'conv-elsewhere' })).toBe(false);
      expect(isFromWorkflowPanelChat(WF, {})).toBe(false);
      expect(isFromWorkflowPanelChat('wf-never-opened', { conversationId: PANEL_CONV })).toBe(false);
    });

    it('keeps the conversation while the remounted panel has not found it again (it reports null)', () => {
      rememberWorkflowPanelConversation(WF, null);
      expect(isFromWorkflowPanelChat(WF, { conversationId: PANEL_CONV })).toBe(true);
    });
  });

  describe('reactionToAgentMarker - the panel agent edits the plan', () => {
    const edit = { type: 'workflow', id: WF, planChanged: true, conversationId: PANEL_CONV };

    it('goes back to editing when a run is on screen', () => {
      expect(reactionToAgentMarker(edit, { workflowId: WF, boundRunId: 'run-1', shownVersion: 11 }))
        .toEqual({ kind: 'edit' });
    });

    it('does nothing when the canvas is already editing (it re-imports HEAD on its own)', () => {
      expect(reactionToAgentMarker(edit, { workflowId: WF, boundRunId: null, shownVersion: 11 }))
        .toEqual({ kind: 'none' });
    });

    it('ignores a load / present / run_node marker (planChanged false)', () => {
      expect(reactionToAgentMarker({ ...edit, planChanged: false }, { workflowId: WF, boundRunId: 'run-1', shownVersion: 11 }))
        .toEqual({ kind: 'none' });
    });

    it('ignores an agent chatting elsewhere about the same workflow', () => {
      expect(reactionToAgentMarker({ ...edit, conversationId: 'conv-elsewhere' }, { workflowId: WF, boundRunId: 'run-1', shownVersion: 11 }))
        .toEqual({ kind: 'none' });
    });

    it('ignores another workflow', () => {
      expect(reactionToAgentMarker({ ...edit, id: 'wf-other' }, { workflowId: WF, boundRunId: 'run-1', shownVersion: 11 }))
        .toEqual({ kind: 'none' });
    });
  });

  describe('reactionToAgentMarker - a run is launched, replayed or resumed', () => {
    const run = { type: 'workflow_run', id: WF, runId: 'run-2', planVersion: 13 };

    it('overlays without reloading when editing the very version the run executes', () => {
      expect(reactionToAgentMarker(run, { workflowId: WF, boundRunId: null, shownVersion: 13 }))
        .toEqual({ kind: 'bindRun', runId: 'run-2', keepPlan: true });
    });

    it("loads the run's own plan when it runs another version (pinned, or replayed on an older one)", () => {
      expect(reactionToAgentMarker(run, { workflowId: WF, boundRunId: null, shownVersion: 14 }))
        .toEqual({ kind: 'bindRun', runId: 'run-2', keepPlan: false });
    });

    it("loads the run's own plan when another run is on screen (its plan is not this run's)", () => {
      expect(reactionToAgentMarker(run, { workflowId: WF, boundRunId: 'run-1', shownVersion: 13 }))
        .toEqual({ kind: 'bindRun', runId: 'run-2', keepPlan: false });
    });

    it('keeps the previous overlay behaviour in edit mode when a version is unknown', () => {
      expect(reactionToAgentMarker({ ...run, planVersion: undefined }, { workflowId: WF, boundRunId: null, shownVersion: 13 }))
        .toEqual({ kind: 'bindRun', runId: 'run-2', keepPlan: true });
      expect(reactionToAgentMarker(run, { workflowId: WF, boundRunId: null, shownVersion: null }))
        .toEqual({ kind: 'bindRun', runId: 'run-2', keepPlan: true });
    });

    it('binds present_run / present_application too, and ignores a marker with no run', () => {
      expect(reactionToAgentMarker({ ...run, type: 'present_application' }, { workflowId: WF, boundRunId: null, shownVersion: 13 }).kind)
        .toBe('bindRun');
      expect(reactionToAgentMarker({ ...run, runId: undefined }, { workflowId: WF, boundRunId: null, shownVersion: 13 }))
        .toEqual({ kind: 'none' });
      expect(reactionToAgentMarker({ ...run, type: 'present_table' }, { workflowId: WF, boundRunId: null, shownVersion: 13 }))
        .toEqual({ kind: 'none' });
    });
  });

  describe('the toggle mark', () => {
    it('matches only the run change the toggle asked for, and is spent once read', () => {
      markUserModeToggle(WF, 'run-1');
      expect(consumeUserModeToggle(WF, 'run-1')).toBe(true);
      expect(consumeUserModeToggle(WF, 'run-1')).toBe(false);

      markUserModeToggle(WF, null);
      expect(consumeUserModeToggle(WF, 'run-2')).toBe(false); // another change got there first
      expect(consumeUserModeToggle(WF, null)).toBe(false);    // and the stale mark is gone
    });

    it('is per workflow', () => {
      markUserModeToggle('wf-other', null);
      expect(consumeUserModeToggle(WF, null)).toBe(false);
      expect(consumeUserModeToggle('wf-other', null)).toBe(true);
    });
  });
});
