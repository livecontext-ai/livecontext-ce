/**
 * @vitest-environment jsdom
 *
 * What an agent run looks like on the calendar.
 *
 * The backend now merges agent executions into the agenda's history, and the only thing
 * that distinguishes one from a workflow fire on screen is its LAUNCH KIND: a chat turn,
 * a schedule, a webhook, a workflow node, another agent, a task, the widget. These tests
 * cover the two places a user reads that - the chip's glyph and the menu's sentence -
 * plus the failure the first implementation had, which was silent: the chip rendered a
 * workflow NodeIcon with an undefined id for every agent-only kind, so a sub-agent run
 * drew a blank square and said nothing at all.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as React from 'react';
import { OccurrenceChip } from '../OccurrenceChip';
import { OccurrenceMenu } from '../OccurrenceMenu';
import { AgendaKindIcon } from '../AgendaKindIcon';
import { AGENDA_KIND_ORDER } from '../agendaLaunchKinds';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
  useOptionalTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
}));

vi.mock('@dnd-kit/core', () => ({
  useDraggable: () => ({ attributes: {}, listeners: {}, setNodeRef: () => {}, isDragging: false }),
}));

function agentRun(overrides: Partial<AgendaOccurrence> = {}): AgendaOccurrence {
  return {
    id: 'agent-run:exec-1',
    kind: 'PAST',
    startAt: '2026-09-14T09:12:00Z',
    endAt: '2026-09-14T09:12:08Z',
    resourceType: 'AGENT',
    resourceId: 'ag-1',
    name: 'Support agent',
    armed: true,
    isNextFire: false,
    overridden: false,
    moveAllSupported: false,
    status: 'COMPLETED',
    launchSource: 'SUB_AGENT',
    conversationId: 'conv-5',
    ...overrides,
  };
}

function renderMenu(occurrence: AgendaOccurrence) {
  return render(
    <OccurrenceMenu
      occurrence={occurrence}
      anchor={{ x: 10, y: 10 }}
      timezone="UTC"
      busy={false}
      canMutate
      onClose={() => {}}
      onRunNow={() => {}}
      onMove={() => {}}
      onTogglePause={() => {}}
      onOpenResource={() => {}}
    />,
  );
}

describe('an agent run on the calendar', () => {
  describe('the menu', () => {
    it('names how the run was launched', () => {
      renderMenu(agentRun());

      expect(screen.getByText('launchedBy:{"kind":"kind.sub_agent"}')).toBeTruthy();
    });

    it('says nothing about the launch when the backend could not attribute it', () => {
      // An unknown source must read as silence, not as a guess: the sentence is drawn
      // where the user goes to find out WHY their agent ran.
      renderMenu(agentRun({ launchSource: undefined }));

      expect(screen.queryByText(/^launchedBy/)).toBeNull();
    });

    it('does not claim to know the launch of a run that has not happened', () => {
      renderMenu(agentRun({ kind: 'PLANNED', launchSource: 'SCHEDULE', status: 'PLANNED' }));

      expect(screen.queryByText(/^launchedBy/)).toBeNull();
    });

    it('offers to open the CONVERSATION the run happened in, not the agent', () => {
      renderMenu(agentRun());

      expect(screen.queryByText('menu.openConversation')).toBeTruthy();
      expect(screen.queryByText('menu.open')).toBeNull();
    });

    it('falls back to opening the resource when the run had no conversation', () => {
      renderMenu(agentRun({ conversationId: undefined }));

      expect(screen.queryByText('menu.open')).toBeTruthy();
      expect(screen.queryByText('menu.openConversation')).toBeNull();
    });

    it('offers no write action on a run: it already happened', () => {
      renderMenu(agentRun());

      expect(screen.queryByText('menu.runNow')).toBeNull();
      expect(screen.queryByText('menu.move')).toBeNull();
      expect(screen.queryByText('menu.pause')).toBeNull();
    });

    it('reports a stopped run with its own word rather than a failure', () => {
      renderMenu(agentRun({ status: 'CANCELLED' }));

      expect(screen.getByText('status.cancelled')).toBeTruthy();
    });
  });

  describe('the chip', () => {
    it('draws the launch kind rather than falling back to the resource icon', () => {
      // Asserting only "an svg is present" could not fail: the pre-change chip fell back
      // to the AGENT resource icon, also an svg. What distinguishes the two is WHICH
      // glyph, so this compares the chip against both possibilities rendered alone.
      const { container } = render(
        <OccurrenceChip occurrence={agentRun()} timezone="UTC" canMutate onSelect={() => {}} />,
      );
      // The same chip with the launch kind removed is the pre-change rendering: it falls
      // back to the resource icon. Nothing else about the two differs, so the markup
      // differing IS the glyph having changed.
      const fallback = render(
        <OccurrenceChip
          occurrence={agentRun({ launchSource: undefined })}
          timezone="UTC"
          canMutate
          onSelect={() => {}}
        />,
      ).container;

      expect(container.querySelector('svg')).toBeTruthy();
      expect(container.innerHTML).not.toBe(fallback.innerHTML);
    });

    it('names the launch kind in its accessible label, which the glyph cannot', () => {
      const { container } = render(
        <OccurrenceChip occurrence={agentRun()} timezone="UTC" canMutate onSelect={() => {}} />,
      );

      // The glyph is aria-hidden and disappears below 8rem of cell width; the label is
      // what a screen reader and a narrow month cell are left with.
      const label = container.querySelector('button')?.getAttribute('aria-label');
      expect(label).toContain('Support agent');
      expect(label).toContain('kind.sub_agent');
    });

    it('says nothing about the launch of a PLANNED chip', () => {
      const planned = agentRun({ kind: 'PLANNED', status: 'PLANNED', launchSource: 'SCHEDULE' });
      const { container } = render(
        <OccurrenceChip occurrence={planned} timezone="UTC" canMutate onSelect={() => {}} />,
      );

      expect(container.querySelector('button')?.getAttribute('aria-label')).not.toContain('kind.');
    });

    it('is not draggable: history cannot be moved', () => {
      const { container } = render(
        <OccurrenceChip occurrence={agentRun()} timezone="UTC" canMutate onSelect={() => {}} />,
      );

      expect(container.querySelector('button')?.className).not.toContain('cursor-grab');
    });
  });

  describe('the shared kind glyph', () => {
    it('draws something for every kind the filter list offers', () => {
      // A filter row with no glyph is a row of text the user cannot recognise at a glance,
      // and it is the exact shape of the bug above: one vocabulary, two icon sources.
      for (const kind of AGENDA_KIND_ORDER) {
        const { container, unmount } = render(<AgendaKindIcon kind={kind} />);
        expect(container.querySelector('svg'), kind).toBeTruthy();
        unmount();
      }
    });

    it('gives every kind the SAME footprint, whichever source drew it', () => {
      // "An svg exists" was true while the two branches sat at different sizes in one
      // filter row: NodeIcon draws a 24px tile with a 14px glyph, a bare lucide icon
      // draws neither. Measuring the outer box is what makes the row even.
      const boxes = AGENDA_KIND_ORDER.map((kind) => {
        const { container, unmount } = render(<AgendaKindIcon kind={kind} />);
        const box = container.firstElementChild as HTMLElement;
        const className = box.className;
        unmount();
        return { kind, className };
      });

      for (const { kind, className } of boxes) {
        expect(className, kind).toContain('h-6');
        expect(className, kind).toContain('w-6');
      }
    });

    it('falls back to a glyph for a launch kind this build does not know', () => {
      // A backend deployed ahead of the frontend. Rendering nothing left an empty box on
      // the chip, which is the same silence this component was written to remove.
      const { container } = render(
        <AgendaKindIcon kind={'SOMETHING_NEW' as never} />,
      );

      expect(container.querySelector('svg')).toBeTruthy();
    });
  });
});
