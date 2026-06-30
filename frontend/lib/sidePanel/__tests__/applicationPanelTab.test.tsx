/**
 * @vitest-environment jsdom
 *
 * Pins the auto-opened application tab descriptor. The load-bearing invariant is
 * `keepMounted: true` - dropping it re-introduces the "only the last app
 * resolved" bug (inactive app tabs unmount and their fetch is cancelled). This
 * is the actual fix site: AppHeader's `application` auto-open case builds its tab
 * through this helper.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('@/components/app/ApplicationSidePanel', () => ({
  ApplicationPanelContent: (props: { publicationId: string; runId?: string }) =>
    React.createElement('div', { 'data-testid': 'app-panel', ...props }),
}));

import { buildApplicationPanelTab, applicationPanelTabId } from '@/lib/sidePanel/applicationPanelTab';

describe('applicationPanelTabId', () => {
  it('scopes the id by runId so each execution gets its own tab', () => {
    expect(applicationPanelTabId('pub-1', 'run-1')).toBe('application-pub-1-run-1');
  });

  it('gives two runs of the same app DIFFERENT tab ids (the epoch-collapse bug)', () => {
    // Pre-fix both runs keyed to `application-pub-1` and collapsed onto one
    // panel tab, so the second card showed the first card's run/epoch.
    const a = applicationPanelTabId('pub-1', 'run-A');
    const b = applicationPanelTabId('pub-1', 'run-B');
    expect(a).not.toBe(b);
  });

  it('falls back to the legacy publication-only id when no runId is given', () => {
    expect(applicationPanelTabId('pub-1')).toBe('application-pub-1');
    expect(applicationPanelTabId('pub-1', null)).toBe('application-pub-1');
    expect(applicationPanelTabId('pub-1', undefined)).toBe('application-pub-1');
  });
});

describe('buildApplicationPanelTab', () => {
  it('sets keepMounted:true so each opened app stays mounted and resolves', () => {
    const tab = buildApplicationPanelTab({ publicationId: 'pub-1', title: 'Aerolens', runId: 'run-1' });
    expect(tab.keepMounted).toBe(true);
  });

  it('keys the tab by publication id + runId and uses the application width', () => {
    const tab = buildApplicationPanelTab({ publicationId: 'pub-1', title: 'Aerolens', runId: 'run-1' });
    expect(tab.id).toBe('application-pub-1-run-1');
    expect(tab.preferredWidth).toBe(0.35);
  });

  it('keys the tab by publication id alone when there is no run', () => {
    const tab = buildApplicationPanelTab({ publicationId: 'pub-1', title: 'Aerolens' });
    expect(tab.id).toBe('application-pub-1');
  });

  it('derives its id through applicationPanelTabId so the auto-open path and the chat card toggle ONE tab per execution (not duplicate)', () => {
    // AppHeader's execute-marker auto-open builds the tab via this helper, while
    // ApplicationVisualizeCard computes its id with applicationPanelTabId directly.
    // Both must agree for the same (publicationId, runId) - otherwise the auto-
    // opened tab and a later card click would open two tabs for one execution.
    expect(buildApplicationPanelTab({ publicationId: 'pub-7', runId: 'run-3' }).id)
      .toBe(applicationPanelTabId('pub-7', 'run-3'));
    expect(buildApplicationPanelTab({ publicationId: 'pub-7' }).id)
      .toBe(applicationPanelTabId('pub-7'));
  });

  it('threads publicationId + runId into the panel content', () => {
    const tab = buildApplicationPanelTab({ publicationId: 'pub-2', runId: 'run-9' });
    const el = tab.content as React.ReactElement<{ publicationId: string; runId?: string }>;
    expect(el.props.publicationId).toBe('pub-2');
    expect(el.props.runId).toBe('run-9');
  });

  it('falls back to a default label when none is given', () => {
    expect(buildApplicationPanelTab({ publicationId: 'pub-3' }).label).toBe('Application');
  });
});
