// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * Pins the agent-fleet inspector's per-family access display. The summary is GRANT-driven
 * (getGrant reads the <family>Grant sentinel, never the id list) so a grant='all' family
 * with an EMPTY list still shows "All" - the regression the backend resolveResources fix
 * also closed. The R/W badge only renders once a family is granted (grant !== 'none').
 *
 * next-intl is only imported for the ReturnType<typeof useTranslations> type; a stub `t`
 * echoes its key, so the grant badges read `grant_all` / `grant_none` / `grant_custom`.
 */
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/components/ui/label', () => ({ Label: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));

import { AgentFamilyAccessSection } from '../AgentFamilyAccessSection';

const t = ((k: string) => k) as unknown as ReturnType<typeof import('next-intl').useTranslations>;

afterEach(() => cleanup());

describe('AgentFamilyAccessSection - fleet per-family grant + R/W display', () => {
  it('shows "All" for a grant=all family even with an EMPTY id list (reads the sentinel, not the list)', () => {
    render(
      <AgentFamilyAccessSection
        agent={{ toolsConfig: { workflowsGrant: 'all', workflows: [], workflowAccessMode: 'write' } }}
        t={t}
      />,
    );
    // The granted family surfaces "All" (NOT omitted/none) + its R/W badge.
    expect(screen.getAllByText('grant_all').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('accessModeReadWrite').length).toBeGreaterThanOrEqual(1);
  });

  it('shows "None" and NO R/W badge for every ungranted family', () => {
    render(<AgentFamilyAccessSection agent={{ toolsConfig: { mode: 'all' } }} t={t} />);
    expect(screen.getAllByText('grant_none').length).toBe(5); // 5 families, all none
    expect(screen.queryByText('accessModeRead')).not.toBeInTheDocument();
    expect(screen.queryByText('accessModeReadWrite')).not.toBeInTheDocument();
  });

  it('shows "Custom" + a Read badge for a custom family in read mode', () => {
    render(
      <AgentFamilyAccessSection
        agent={{ toolsConfig: { tablesGrant: 'custom', tables: [1, 2], tableAccessMode: 'read' } }}
        t={t}
      />,
    );
    expect(screen.getAllByText('grant_custom').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('accessModeRead').length).toBeGreaterThanOrEqual(1);
  });

  it('an UNRECOGNISED grant resolves to "None" (deny-by-default - never shows access it lacks)', () => {
    render(
      <AgentFamilyAccessSection
        agent={{ toolsConfig: { workflowsGrant: 'bogus', workflows: ['x'] } }}
        t={t}
      />,
    );
    expect(screen.getAllByText('grant_none').length).toBe(5);
    expect(screen.queryByText('accessModeReadWrite')).not.toBeInTheDocument();
  });
});
